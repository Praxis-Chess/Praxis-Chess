package com.praxis.play.patterns.detectors;

import com.praxis.domain.MoveError;
import com.praxis.domain.enums.AnalysisState;
import com.praxis.domain.enums.TacticalMotif;
import com.praxis.play.patterns.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The same tactical mistake, game after game.
 *
 * Reported as a share of *written-up* mistakes, never as a raw count. Motif is
 * set only on EXPLAINED errors, and how many of those a game gets is the
 * analysis profile's budget — three per game before AnalysisProfile existed, ten
 * after. A raw total across that boundary would conflate "you did it more" with
 * "we wrote up more of them".
 *
 * This is the one detector with a drill link: every row behind it carries a real
 * FEN and an engine best move, which is exactly what a drill card is.
 */
@Component
public class MotifRecurrenceDetector implements PatternDetector {

    private static final int MIN_GAMES_WITH_MOTIF = PerGameDetector.MIN_RECURRENCE;
    private static final int MIN_SAMPLE = PerGameDetector.MIN_SAMPLE;

    /** Says nothing a player can act on; never worth naming as a tendency. */
    private static final TacticalMotif UNINFORMATIVE = TacticalMotif.OTHER;

    @Override public String id()    { return "recurring-motif"; }
    @Override public String title() { return "The same tactic keeps catching you"; }

    @Override public String why() {
        return "A tactic you miss once is bad luck; the same one across several games is "
             + "a shape you are not seeing yet, and opponents will keep finding it.";
    }

    @Override public String whatToDo() {
        return "Drill this specific motif from your own positions until you spot the "
             + "shape before the engine has to point at it.";
    }

    // drillMotif() is deliberately NOT overridden. Which motif dominates is a
    // property of one report, not of the detector, and this is a singleton — a
    // field holding it would leak between requests and race under concurrency.
    // The value goes straight onto the PracticePattern built below.

    @Override
    public Optional<PracticePattern> detect(List<AnalysedGame> window) {
        if (window.size() < MIN_SAMPLE) return Optional.empty();

        // Games each motif appeared in, and total explained mistakes overall.
        Map<TacticalMotif, List<Occurrence>> byMotif = new LinkedHashMap<>();
        int explainedTotal = 0;

        for (AnalysedGame game : window) {
            for (MoveError error : game.errors()) {
                if (error.getAnalysisState() != AnalysisState.EXPLAINED) continue;
                explainedTotal++;

                TacticalMotif motif = error.getTacticalMotif();
                if (motif == null || motif == UNINFORMATIVE) continue;

                List<Occurrence> hits = byMotif.computeIfAbsent(motif, m -> new ArrayList<>());
                // One occurrence per game per motif: three hanging pieces in one
                // game is one game's worth of evidence, not three.
                boolean already = hits.stream().anyMatch(o -> o.gameId().equals(game.gameId()));
                if (already) continue;

                hits.add(Occurrence.atPly(game.gameId(), error.getMoveNumber(),
                        humanMotif(motif) + " on move " + fullMove(error.getMoveNumber())
                                + " (" + game.playedOn() + ")"));
            }
        }

        Optional<Map.Entry<TacticalMotif, List<Occurrence>>> top = byMotif.entrySet().stream()
                .filter(e -> e.getValue().size() >= MIN_GAMES_WITH_MOTIF)
                .max(Comparator.comparingInt(e -> e.getValue().size()));

        if (top.isEmpty()) return Optional.empty();

        TacticalMotif motif = top.get().getKey();
        List<Occurrence> hits = top.get().getValue();

        String finding = humanMotif(motif) + " showed up in "
                + PerGameDetector.ratio(hits.size(), window.size())
                + ", across " + explainedTotal + " written-up mistakes.";

        return Optional.of(new PracticePattern(
                id(), title(), finding, why(), whatToDo(),
                PerGameDetector.strengthFor(window.size()),
                hits.size(), window.size(),
                motif.name(),
                List.copyOf(hits)));
    }

    /** "HANGING_PIECE" -> "Hanging pieces". */
    static String humanMotif(TacticalMotif motif) {
        return switch (motif) {
            case HANGING_PIECE      -> "Hanging pieces";
            case FORK               -> "Forks";
            case PIN                -> "Pins";
            case SKEWER             -> "Skewers";
            case BACK_RANK          -> "Back-rank tactics";
            case DISCOVERED_ATTACK  -> "Discovered attacks";
            case POSITIONAL         -> "Positional slips";
            case OTHER              -> "Other";
        };
    }

    private static int fullMove(int ply) {
        return (ply + 1) / 2;
    }
}
