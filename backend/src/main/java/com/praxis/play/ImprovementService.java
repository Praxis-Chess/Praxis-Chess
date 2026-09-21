package com.praxis.play;

import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.play.domain.PracticeGame;
import com.praxis.play.repository.PracticeGameRepository;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Did this game go better than the ones before it?
 *
 * Two rules keep this honest, and both are easy to get wrong:
 *
 * 1. LIKE FOR LIKE. A practice game is compared only against other practice
 *    games. Chess.com games are a different population — a different opponent
 *    class entirely — and comparing across that gap would produce a number that
 *    is arithmetically fine and means nothing. The single exception is
 *    opening-specific accuracy, because an ECO is the same object either way,
 *    and even that is labelled as crossing the boundary.
 *
 * 2. ONE GAME IS NOT A TREND. Below MIN_SAMPLE comparable games this reports
 *    the game and explicitly declines to claim direction. Same discipline as
 *    Evidence.MIN_SAMPLE in the reasoning layer.
 */
@Service
public class ImprovementService {

    /** Below this, differences are noise. Mirrors Evidence.MIN_SAMPLE. */
    static final int MIN_SAMPLE = 5;

    /** Rolling window for the baseline. */
    static final int WINDOW = 5;

    /** Error counts are normalised to this many player moves, so long games do not look worse. */
    static final int PER_MOVES = 40;

    private final PracticeGameRepository practiceGames;
    private final GameRepository games;
    private final MoveErrorRepository moveErrors;

    private final PracticeAnalysisProgress progress;

    public ImprovementService(PracticeGameRepository practiceGames, GameRepository games,
                              MoveErrorRepository moveErrors, PracticeAnalysisProgress progress) {
        this.practiceGames = practiceGames;
        this.games = games;
        this.moveErrors = moveErrors;
        this.progress = progress;
    }

    public enum Direction { BETTER, WORSE, UNCHANGED, UNKNOWN }

    /**
     * @param label      what was measured
     * @param value      this game
     * @param baseline   the comparable average, or null when there isn't one
     * @param direction  BETTER/WORSE only when there is enough sample to say
     * @param note       why, in one line
     */
    public record Comparison(String label, String value, String baseline,
                             Direction direction, String note) {}

    /**
     * How far the engine has got, while {@code analysed} is still false.
     *
     * Null when nothing is running — either the analysis has not been picked off
     * the queue yet, or it is over. A null here means "no counts available", which
     * the client must render as an indeterminate wait rather than as zero progress.
     *
     * @param stage SWEEPING | ENRICHING | EXPLAINING
     * @param total always counted, never estimated
     */
    public record AnalysisProgress(String stage, int done, int total) {}

    /**
     * @param gameId          the archived Game row, or null before the game is
     *                        archived. This is the id the analysis endpoints take
     *                        — {@code practiceGameId} is a different row and will
     *                        404 against them.
     * @param progress        live stage counts while unanalysed; null once done
     * @param sampleSize      how many rated practice games back the baseline
     * @param trendClaimable  false when the sample is too small to claim direction
     */
    public record ImprovementReport(
            UUID practiceGameId,
            UUID gameId,
            AnalysisProgress progress,
            boolean analysed,
            boolean rated,
            String verdict,
            List<Comparison> comparisons,
            List<String> improved,
            List<String> stillToWork,
            int sampleSize,
            boolean trendClaimable,
            String caveat
    ) {}

    public ImprovementReport report(PracticeGame pg) {
        if (pg.getGameId() == null) {
            return notAnalysed(pg, "This game has not been analysed yet.");
        }
        Optional<Game> archived = games.findById(pg.getGameId());
        if (archived.isEmpty()) {
            return notAnalysed(pg, "The analysed game could not be found.");
        }

        Game game = archived.get();

        // The Game row is created and linked to the practice game at ARCHIVE
        // time; the engine measures it afterwards, asynchronously. So the row
        // existing says nothing about whether there is anything to report.
        //
        // Reporting on it anyway produced a report that was structurally valid
        // and entirely empty — "Accuracy — vs —", "Blunders per 40 moves 0.0" —
        // and because that report says analysed:true, the controller returned
        // 200, the client stopped polling, and the user was left looking at a
        // finished-looking report of a game that had not been measured. The
        // real numbers (87.1%, 1.6 blunders) landed seconds later and were
        // never fetched.
        //
        // 0.0 blunders was the worse half: an unmeasured game and a flawless
        // one are not the same claim, and only one of them was true.
        if (game.getAnalysisStatus() != AnalysisStatus.ANALYZED) {
            return switch (game.getAnalysisStatus()) {
                case FAILED -> notAnalysed(pg, "The engine could not analyse this game.");
                default -> notAnalysed(pg, "Analysing the game — the report is a moment away.");
            };
        }

        List<MoveError> errors = moveErrors.findByGameId(game.getId());
        int playerMoves = playerMoveCount(game);

        // Baseline: earlier rated practice games only.
        List<PracticeGame> history = practiceGames.findRatedFinished(pg.getUsername()).stream()
                .filter(p -> !p.getId().equals(pg.getId()))
                .filter(p -> p.getGameId() != null)
                .limit(WINDOW)
                .toList();

        int sample = history.size();
        boolean claimable = sample >= MIN_SAMPLE;

        List<Comparison> out = new ArrayList<>();
        List<String> improved = new ArrayList<>();
        List<String> todo = new ArrayList<>();

        // --- accuracy ---
        Double acc = game.hasAccuracy() ? game.getAccuracy() : null;
        Double accBase = averageAccuracy(history);
        out.add(compare("Accuracy", fmtPct(acc), fmtPct(accBase),
                direction(acc, accBase, 2.0, true, claimable),
                "Against your recent practice games, not your Chess.com games."));
        classify(out.get(out.size() - 1), improved, todo, "Accuracy");

        // --- blunder rate ---
        double blunders = rate(count(errors, Severity.BLUNDER), playerMoves);
        Double blunderBase = averageRate(history, Severity.BLUNDER, null);
        out.add(compare("Blunders per " + PER_MOVES + " moves", fmt(blunders), fmt(blunderBase),
                direction(blunders, blunderBase, 0.3, false, claimable),
                "Rate, not raw count, so a long game is not penalised."));
        classify(out.get(out.size() - 1), improved, todo, "Blunder rate");

        // --- the phase this game was targeting ---
        GamePhase targetPhase = phaseOf(pg.getTargetedWeakness());
        if (targetPhase != null) {
            double phaseRate = rate(countPhase(errors, targetPhase), playerMoves);
            Double phaseBase = averageRate(history, null, targetPhase);
            out.add(compare(human(targetPhase) + " mistakes per " + PER_MOVES + " moves",
                    fmt(phaseRate), fmt(phaseBase),
                    direction(phaseRate, phaseBase, 0.3, false, claimable),
                    "The weakness this opponent was built to press."));
            classify(out.get(out.size() - 1), improved, todo, human(targetPhase) + " play");
        }

        // --- opening accuracy: the one metric allowed to cross populations ---
        if (pg.getTargetEco() != null && acc != null) {
            Double ecoBase = ecoAccuracy(pg.getUsername(), pg.getTargetEco());
            out.add(compare("Accuracy in " + displayEco(pg), fmtPct(acc), fmtPct(ecoBase),
                    direction(acc, ecoBase, 2.0, true, ecoBase != null),
                    "Compared with all your games in this opening — the one measure that "
                            + "carries across opponents, because the opening is the same object."));
            classify(out.get(out.size() - 1), improved, todo, "Play in " + displayEco(pg));
        }

        String verdict = verdict(pg, claimable, improved, todo);
        String caveat = !pg.isRated()
                ? "You took a move back, so this game is not counted toward your progress."
                : claimable ? null
                : "Only " + sample + " earlier practice game" + (sample == 1 ? "" : "s")
                  + " to compare against — not yet a trend.";

        // No progress on a finished report: the run is over, and a bar that
        // lingers after the numbers arrive is just noise.
        return new ImprovementReport(pg.getId(), game.getId(), null, true, pg.isRated(), verdict,
                List.copyOf(out), List.copyOf(improved), List.copyOf(todo),
                sample, claimable, caveat);
    }

    // --- verdict ---

    private String verdict(PracticeGame pg, boolean claimable,
                           List<String> improved, List<String> todo) {
        if (!pg.isRated()) {
            return "Played with takebacks — shown, but not counted.";
        }
        if (!claimable) {
            return "Game recorded. Not enough practice games yet to say whether you are improving.";
        }
        if (improved.isEmpty() && todo.isEmpty()) return "About the same as your recent games.";
        if (todo.isEmpty()) return "Better than your recent practice games.";
        if (improved.isEmpty()) return "Below your recent practice games.";
        return "Mixed: some of it better, some of it not.";
    }

    private void classify(Comparison c, List<String> improved, List<String> todo, String label) {
        if (c.direction() == Direction.BETTER) improved.add(label);
        else if (c.direction() == Direction.WORSE) todo.add(label);
    }

    // --- measurement helpers ---

    /**
     * @param higherIsBetter accuracy rises when you improve; error rates fall
     * @param claimable      without enough sample, direction is UNKNOWN however
     *                       large the difference looks
     */
    private static Direction direction(Double value, Double baseline, double threshold,
                                       boolean higherIsBetter, boolean claimable) {
        if (value == null || baseline == null || !claimable) return Direction.UNKNOWN;
        double delta = value - baseline;
        if (Math.abs(delta) < threshold) return Direction.UNCHANGED;
        boolean up = delta > 0;
        return (up == higherIsBetter) ? Direction.BETTER : Direction.WORSE;
    }

    private static Comparison compare(String label, String value, String baseline,
                                      Direction d, String note) {
        return new Comparison(label, value, baseline, d, note);
    }

    private double rate(int count, int playerMoves) {
        if (playerMoves <= 0) return 0;
        return round1((double) count * PER_MOVES / playerMoves);
    }

    private static int count(List<MoveError> errors, Severity severity) {
        return (int) errors.stream().filter(e -> e.getSeverity() == severity).count();
    }

    private static int countPhase(List<MoveError> errors, GamePhase phase) {
        return (int) errors.stream().filter(e -> e.getGamePhase() == phase).count();
    }

    /** Half the plies are the opponent's; error rates are per PLAYER move. */
    private int playerMoveCount(Game game) {
        String pgn = game.getRawPgn();
        if (pgn == null) return 0;
        int moveTextStart = pgn.indexOf("\n\n");
        String moves = moveTextStart >= 0 ? pgn.substring(moveTextStart) : pgn;
        int plies = 0;
        for (String tok : moves.trim().split("\\s+")) {
            if (tok.isBlank() || tok.endsWith(".") || tok.matches("[01]-[01]|1/2-1/2|\\*")) continue;
            plies++;
        }
        return Math.max(1, plies / 2);
    }

    private Double averageAccuracy(List<PracticeGame> history) {
        List<Double> vals = history.stream()
                .map(p -> games.findById(p.getGameId()).orElse(null))
                .filter(g -> g != null && g.hasAccuracy())
                .map(Game::getAccuracy)
                .toList();
        return vals.isEmpty() ? null
                : round1(vals.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private Double averageRate(List<PracticeGame> history, Severity severity, GamePhase phase) {
        List<Double> rates = new ArrayList<>();
        for (PracticeGame p : history) {
            Game g = games.findById(p.getGameId()).orElse(null);
            if (g == null) continue;
            List<MoveError> errs = moveErrors.findByGameId(g.getId());
            int n = severity != null ? count(errs, severity) : countPhase(errs, phase);
            rates.add(rate(n, playerMoveCount(g)));
        }
        return rates.isEmpty() ? null
                : round1(rates.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    /** Accuracy in one ECO across ALL games — the metric allowed to cross populations. */
    private Double ecoAccuracy(String username, String eco) {
        List<Double> vals = games.findByUsernameOrderByPlayedAtDesc(username).stream()
                .filter(Game::hasAccuracy)
                .filter(g -> g.getOpeningEco() != null && g.getOpeningEco().equalsIgnoreCase(eco))
                .map(Game::getAccuracy)
                .toList();
        return vals.isEmpty() ? null
                : round1(vals.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static GamePhase phaseOf(String targetedWeakness) {
        if (targetedWeakness == null || !targetedWeakness.startsWith("phase:")) return null;
        try {
            return GamePhase.valueOf(targetedWeakness.substring("phase:".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String displayEco(PracticeGame pg) {
        return pg.getTargetOpening() != null && !pg.getTargetOpening().isBlank()
                ? pg.getTargetOpening() : pg.getTargetEco();
    }

    private static String human(GamePhase p) {
        String s = p.name().toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private ImprovementReport notAnalysed(PracticeGame pg, String why) {
        // getGameId() is reported as it stands — null before archiving, set after.
        // It is not proof the game was measured; `analysed` is the only field that
        // says that, and the client must gate on it rather than on an id existing.
        AnalysisProgress live = progress.of(pg.getGameId())
                .map(s -> new AnalysisProgress(s.stage().name(), s.done(), s.total()))
                .orElse(null);
        return new ImprovementReport(pg.getId(), pg.getGameId(), live, false, pg.isRated(), why,
                List.of(), List.of(), List.of(), 0, false, null);
    }

    private static String fmt(Double v)    { return v == null ? "—" : String.valueOf(round1(v)); }
    private static String fmt(double v)    { return String.valueOf(round1(v)); }
    private static String fmtPct(Double v) { return v == null ? "—" : round1(v) + "%"; }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
