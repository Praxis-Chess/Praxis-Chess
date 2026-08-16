package com.praxis.prax.intelligence;

import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.service.EcoTable;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Deterministic chess analytics. No model goes anywhere near this class —
 * everything here is a query or arithmetic over it (Reasoning Plan §3).
 *
 * Scope rule (§7.6): aggregates count ANALYZED games only. Unanalysed games
 * have null accuracy and no move errors, and would silently drag every average
 * toward nothing.
 */
@Service
public class ChessIntelligence {

    private final GameRepository gameRepository;
    private final MoveErrorRepository moveErrorRepository;
    private final EcoTable ecoTable;

    public ChessIntelligence(GameRepository gameRepository, MoveErrorRepository moveErrorRepository,
                             EcoTable ecoTable) {
        this.gameRepository = gameRepository;
        this.moveErrorRepository = moveErrorRepository;
        this.ecoTable = ecoTable;
    }

    /**
     * 93 of 100 analysed games arrive from Chess.com with a blank opening_name
     * but a populated opening_eco. Grouping on the name alone therefore discards
     * almost the entire library — which is why "what openings should I learn"
     * came back with nothing. Resolve the name from the ECO table, and fall back
     * to the bare code so a game is never silently dropped.
     */
    private String openingLabel(Game g) {
        String name = g.getOpeningName();
        if (name != null && !name.isBlank()) return name;
        String eco = g.getOpeningEco();
        if (eco == null || eco.isBlank()) return null;
        String looked = ecoTable.lookup(eco);
        return (looked != null && !looked.isBlank()) ? looked : eco;
    }

    // ── records returned to tools ────────────────────────────────────────────

    public record ColorStats(String color, int games, int wins, double winPct, Double avgAccuracy) {}

    public record OpeningStat(String eco, String name, int games, int wins, double winPct,
                              Double avgAccuracy, String color) {}

    public record PhaseStat(String phase, int errors, int blunders, Double avgAccuracy, int games) {}

    public record MotifStat(String motif, int count, int blunders, List<String> exampleGameIds) {}

    public record GameSummary(String id, String playedAt, String color, String result,
                              String eco, String opening, Double accuracy, int mistakes) {}

    public record Recommendation(String eco, String name, int games, double winPct,
                                 Double avgAccuracy, double score, String rationale) {}

    // ── base fetch ───────────────────────────────────────────────────────────

    private List<Game> analyzed(String username) {
        return gameRepository.findByUsernameOrderByPlayedAtDesc(username).stream()
                .filter(g -> g.getAnalysisStatus() == AnalysisStatus.ANALYZED)
                .toList();
    }

    /**
     * Models pass "both", "all" or "any" when they mean "no filter". A strict
     * match on those silently returns zero rows, which reads as "you have no
     * games" — the worst possible failure for a tool meant to prevent
     * hallucination. Anything that is not a real colour means no filter.
     */
    private static String normColor(String c) {
        if (c == null) return null;
        return switch (c.trim().toLowerCase()) {
            case "white", "w" -> "white";
            case "black", "b" -> "black";
            default -> null;
        };
    }

    /** Same reasoning for result filters. */
    private static String normResult(String r) {
        if (r == null) return null;
        return switch (r.trim().toLowerCase()) {
            case "win", "won", "wins" -> "win";
            case "loss", "lose", "lost", "losses" -> "loss";
            case "draw", "drawn", "draws" -> "draw";
            default -> null;
        };
    }

    private static boolean isWin(Game g) {
        return "win".equalsIgnoreCase(g.getResult());
    }

    private static Double avgAccuracy(List<Game> games) {
        // hasAccuracy(), not nonNull: a stored 0.0 is a failed measurement, and
        // counting 61 of them as real scores is what produced a reported
        // "average accuracy 15.4%" for a player whose games score 58-88%.
        var vals = games.stream().filter(Game::hasAccuracy).map(Game::getAccuracy).toList();
        if (vals.isEmpty()) return null;
        return round1(vals.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // ── profile ──────────────────────────────────────────────────────────────

    public Map<String, Object> playerProfile(String username) {
        List<Game> all = gameRepository.findByUsernameOrderByPlayedAtDesc(username);
        List<Game> games = all.stream()
                .filter(g -> g.getAnalysisStatus() == AnalysisStatus.ANALYZED).toList();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("totalGames", all.size());
        profile.put("analyzedGames", games.size());
        profile.put("white", colorStats(games, "white"));
        profile.put("black", colorStats(games, "black"));

        // Opening families by first move, inferred from ECO letter ranges.
        Map<String, Long> families = games.stream()
                .filter(g -> g.getOpeningEco() != null && !g.getOpeningEco().isBlank())
                .collect(Collectors.groupingBy(g -> ecoFamily(g.getOpeningEco()), Collectors.counting()));
        long famTotal = families.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Double> openingProfile = new LinkedHashMap<>();
        families.forEach((k, v) -> openingProfile.put(k, famTotal == 0 ? 0 : round2((double) v / famTotal)));
        profile.put("openingProfile", openingProfile);

        profile.put("phaseProfile", phasePerformance(username, null, null));
        profile.put("topMotifs", mistakePatterns(username, null, null, null, 3)
                .stream().map(MotifStat::motif).toList());
        return profile;
    }

    private ColorStats colorStats(List<Game> games, String color) {
        List<Game> subset = games.stream()
                .filter(g -> color.equalsIgnoreCase(g.getPlayerColor())).toList();
        int wins = (int) subset.stream().filter(ChessIntelligence::isWin).count();
        return new ColorStats(color, subset.size(), wins,
                subset.isEmpty() ? 0 : round2((double) wins / subset.size()), avgAccuracy(subset));
    }

    /** ECO letter → the move family it belongs to. Coarse but honest. */
    private static String ecoFamily(String eco) {
        char c = Character.toUpperCase(eco.charAt(0));
        return switch (c) {
            case 'B', 'C' -> "e4";
            case 'D', 'E' -> "d4";
            default -> "other";
        };
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ── openings ─────────────────────────────────────────────────────────────

    public List<OpeningStat> openingPerformance(String username, String openingFilter,
                                                String rawColor, int minGames) {
        String color = normColor(rawColor);
        List<Game> games = analyzed(username).stream()
                .filter(g -> color == null || color.equalsIgnoreCase(g.getPlayerColor()))
                .filter(g -> openingFilter == null || matchesOpening(g, openingFilter))
                .toList();

        Map<String, List<Game>> byOpening = games.stream()
                .filter(g -> openingLabel(g) != null)
                .collect(Collectors.groupingBy(this::openingLabel));

        return byOpening.entrySet().stream()
                .filter(e -> e.getValue().size() >= minGames)
                .map(e -> {
                    List<Game> gs = e.getValue();
                    int wins = (int) gs.stream().filter(ChessIntelligence::isWin).count();
                    return new OpeningStat(
                            gs.get(0).getOpeningEco(), e.getKey(), gs.size(), wins,
                            round2((double) wins / gs.size()), avgAccuracy(gs),
                            color == null ? "both" : color);
                })
                .sorted(Comparator.comparingInt(OpeningStat::games).reversed())
                .toList();
    }

    private boolean matchesOpening(Game g, String filter) {
        String f = filter.toLowerCase();
        String label = openingLabel(g);
        return (label != null && label.toLowerCase().contains(f))
                || (g.getOpeningEco() != null && g.getOpeningEco().equalsIgnoreCase(filter));
    }

    // ── phases ───────────────────────────────────────────────────────────────

    public List<PhaseStat> phasePerformance(String username, String openingFilter, String rawColor) {
        String color = normColor(rawColor);
        List<Game> games = analyzed(username).stream()
                .filter(g -> color == null || color.equalsIgnoreCase(g.getPlayerColor()))
                .filter(g -> openingFilter == null || matchesOpening(g, openingFilter))
                .toList();
        // Fetched per game id, so the rows are already scoped — never dereference
        // MoveError.game here, it is LAZY and this method is not transactional.
        List<MoveError> errors = games.stream()
                .flatMap(g -> moveErrorRepository.findByGameId(g.getId()).stream())
                .toList();

        List<PhaseStat> out = new ArrayList<>();
        for (String phase : List.of("OPENING", "MIDDLEGAME", "ENDGAME")) {
            List<MoveError> inPhase = errors.stream()
                    .filter(e -> e.getGamePhase() != null && phase.equals(e.getGamePhase().name()))
                    .toList();
            int blunders = (int) inPhase.stream()
                    .filter(e -> e.getSeverity() != null && "BLUNDER".equals(e.getSeverity().name()))
                    .count();
            out.add(new PhaseStat(phase, inPhase.size(), blunders, avgAccuracy(games), games.size()));
        }
        return out;
    }

    // ── mistakes ─────────────────────────────────────────────────────────────

    public List<MotifStat> mistakePatterns(String username, String motifFilter, String phase,
                                           String openingFilter, int limit) {
        List<Game> games = analyzed(username).stream()
                .filter(g -> openingFilter == null || matchesOpening(g, openingFilter))
                .toList();

        // Pair each error with its game id up front — reading e.getGame() later
        // would hit a lazy proxy with no session attached.
        record Row(UUID gameId, MoveError err) {}
        List<Row> rows = games.stream()
                .flatMap(g -> moveErrorRepository.findByGameId(g.getId()).stream()
                        .map(e -> new Row(g.getId(), e)))
                .toList();

        List<Row> errors = rows.stream()
                .filter(r -> phase == null || (r.err().getGamePhase() != null
                        && phase.equalsIgnoreCase(r.err().getGamePhase().name())))
                .filter(r -> motifFilter == null || (r.err().getTacticalMotif() != null
                        && motifFilter.equalsIgnoreCase(r.err().getTacticalMotif().name())))
                .toList();

        Map<String, List<Row>> byMotif = errors.stream()
                .filter(r -> r.err().getTacticalMotif() != null)
                .collect(Collectors.groupingBy(r -> r.err().getTacticalMotif().name()));

        return byMotif.entrySet().stream()
                .map(e -> new MotifStat(
                        e.getKey(),
                        e.getValue().size(),
                        (int) e.getValue().stream()
                                .filter(r -> r.err().getSeverity() != null
                                        && "BLUNDER".equals(r.err().getSeverity().name()))
                                .count(),
                        e.getValue().stream().limit(3)
                                .map(r -> r.gameId().toString()).toList()))
                .sorted(Comparator.comparingInt(MotifStat::count).reversed())
                .limit(limit <= 0 ? 5 : limit)
                .toList();
    }

    // ── games ────────────────────────────────────────────────────────────────

    public List<GameSummary> findGames(String username, String openingFilter, String rawColor,
                                       String rawResult, Integer sinceDays, int limit) {
        String color = normColor(rawColor);
        String result = normResult(rawResult);
        OffsetDateTime cutoff = sinceDays == null ? null
                : OffsetDateTime.now().minusDays(sinceDays);

        return analyzed(username).stream()
                .filter(g -> color == null || color.equalsIgnoreCase(g.getPlayerColor()))
                .filter(g -> result == null || result.equalsIgnoreCase(g.getResult()))
                .filter(g -> openingFilter == null || matchesOpening(g, openingFilter))
                .filter(g -> cutoff == null || (g.getPlayedAt() != null && g.getPlayedAt().isAfter(cutoff)))
                .limit(limit <= 0 ? 10 : Math.min(limit, 50))
                .map(this::toSummary)
                .toList();
    }

    public Optional<Map<String, Object>> gameDetail(String gameId) {
        return gameRepository.findById(UUID.fromString(gameId)).map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("game", toSummary(g));
            // Bounded on purpose: a long game's full error list, each carrying a
            // ~60-character FEN, is enough to overflow a 4k context on its own.
            m.put("errors", moveErrorRepository.findByGameId(g.getId()).stream()
                    .sorted(Comparator.comparing(
                            (MoveError e) -> e.getSeverity() == null ? 9 : switch (e.getSeverity().name()) {
                                case "BLUNDER" -> 0;
                                case "MISTAKE" -> 1;
                                default -> 2;
                            }))
                    .limit(8)
                    .map(e -> Map.of(
                    // Stored as a ply index; a chess reader expects the full move
                    // number and side, or "move 16" reads as move 16 rather than 8.
                    "move", ((e.getMoveNumber() + 1) / 2)
                            + (e.getMoveNumber() % 2 == 1 ? ". " : "... ")
                            + String.valueOf(e.getMovePlayed()),
                    "ply", e.getMoveNumber(),
                    "movePlayed", String.valueOf(e.getMovePlayed()),
                    "betterMove", String.valueOf(e.getBetterMove()),
                    "severity", e.getSeverity() == null ? "" : e.getSeverity().name(),
                    "motif", e.getTacticalMotif() == null ? "" : e.getTacticalMotif().name(),
                    "phase", e.getGamePhase() == null ? "" : e.getGamePhase().name(),
                    "fen", String.valueOf(e.getFenPosition()),
                    // These rows say WHAT went wrong, never why. Terse on purpose:
                    // written as a sentence, the model relays it to the player
                    // ("I would need to call analyze_position...") instead of
                    // acting on it.
                    "explainWith", "analyze_position(fen, movePlayed)"
            )).toList());
            return m;
        });
    }

    private GameSummary toSummary(Game g) {
        return new GameSummary(
                g.getId().toString(),
                g.getPlayedAt() == null ? null : g.getPlayedAt().toLocalDate().toString(),
                g.getPlayerColor(), g.getResult(), g.getOpeningEco(), openingLabel(g),
                g.getAccuracy(), moveErrorRepository.findByGameId(g.getId()).size());
    }

    // ── recommendations ──────────────────────────────────────────────────────

    /**
     * Ranking is deterministic (§7.5). The model explains this ordering; it never
     * produces one. Score favours openings the player already plays often AND
     * performs well in — depth before breadth.
     */
    public List<Recommendation> recommendOpenings(String username, String rawColor) {
        String color = normColor(rawColor);
        List<OpeningStat> stats = openingPerformance(username, null, color, Evidence_MIN);
        if (stats.isEmpty()) return List.of();

        int maxGames = stats.stream().mapToInt(OpeningStat::games).max().orElse(1);

        return stats.stream().map(s -> {
            double familiarity = (double) s.games() / maxGames;
            double accuracy = s.avgAccuracy() == null ? 0 : s.avgAccuracy() / 100.0;
            double score = round2(familiarity * 0.45 + s.winPct() * 0.25 + accuracy * 0.30);
            String rationale = familiarity > 0.6
                    ? "already your most-played line"
                    : accuracy > 0.7 ? "you play it accurately when it appears"
                    : "played occasionally with mixed results";
            return new Recommendation(s.eco(), s.name(), s.games(), s.winPct(),
                    s.avgAccuracy(), score, rationale);
        }).sorted(Comparator.comparingDouble(Recommendation::score).reversed()).limit(5).toList();
    }

    private static final int Evidence_MIN = 3;

    // ── individual mistakes ──────────────────────────────────────────────────

    /**
     * The worst individual moves this player has made, worst first.
     *
     * This closes a real gap in the tool surface. "Show me my worst blunder" had
     * no tool that answered it: the aggregates report motif frequencies, and
     * find_games filters whole games, so the model had to guess a game and then
     * hope it contained the blunder. It guessed wrong every time — and twice
     * covered for that by inventing an explanation. Now the question maps
     * directly onto a query, and each row carries the fen and movePlayed that
     * analyze_position needs.
     *
     * Ordered by win-percentage drop, which is how much the move actually cost,
     * rather than by severity label — a BLUNDER that threw away a won position
     * outranks one played in an already-lost game.
     */
    public List<Map<String, Object>> worstMistakes(String username, String severity,
                                                   String phase, int limit) {
        String sev = severity == null || severity.isBlank() ? null
                : severity.trim().toUpperCase(Locale.ROOT);
        String ph = phase == null || phase.isBlank() ? null
                : phase.trim().toUpperCase(Locale.ROOT);

        // Fetch-joined: these rows are read outside a transaction and each one
        // needs its game for the date, opening and colour.
        return moveErrorRepository.findAllByUsernameWithGame(username).stream()
                .filter(e -> sev == null || (e.getSeverity() != null && sev.equals(e.getSeverity().name())))
                .filter(e -> ph == null || (e.getGamePhase() != null && ph.equals(e.getGamePhase().name())))
                .sorted(Comparator.comparingDouble(ChessIntelligence::cost).reversed())
                .limit(Math.max(1, Math.min(limit, 10)))
                .map(e -> {
                    Game g = e.getGame();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("gameId", g.getId().toString());
                    m.put("playedOn", g.getPlayedAt() == null ? null
                            : g.getPlayedAt().toLocalDate().toString());
                    m.put("opening", openingLabel(g));
                    m.put("yourColor", g.getPlayerColor());
                    m.put("move", ((e.getMoveNumber() + 1) / 2)
                            + (e.getMoveNumber() % 2 == 1 ? ". " : "... ") + e.getMovePlayed());
                    m.put("movePlayed", e.getMovePlayed());
                    m.put("betterMove", e.getBetterMove());
                    m.put("severity", e.getSeverity() == null ? "" : e.getSeverity().name());
                    m.put("phase", e.getGamePhase() == null ? "" : e.getGamePhase().name());
                    m.put("motif", e.getTacticalMotif() == null ? "" : e.getTacticalMotif().name());
                    m.put("winPctDrop", e.getWinPctDrop());
                    m.put("fen", e.getFenPosition());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /** How much the move cost. Falls back to the raw eval swing pre-re-analysis. */
    private static double cost(MoveError e) {
        if (e.getWinPctDrop() != null) return Math.abs(e.getWinPctDrop());
        if (e.getEvalBefore() != null && e.getEvalAfter() != null) {
            return Math.abs(e.getEvalBefore() - e.getEvalAfter());
        }
        return 0.0;
    }
}
