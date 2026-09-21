package com.praxis.play.patterns.detectors;

import com.praxis.domain.MoveError;
import com.praxis.domain.enums.GamePhase;
import com.praxis.play.patterns.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One phase of the game costing far more than the others.
 *
 * Not a {@link PerGameDetector}: the claim is a comparison of rates across the
 * whole window, not a thing each game either shows or does not. Asking "was this
 * game's middlegame bad?" per game and counting would answer a different
 * question and need a second threshold to do it.
 *
 * Rates are per 40 player moves, matching ImprovementService, so a long game is
 * not mistaken for a bad one.
 */
@Component
public class PhaseWeaknessDetector implements PatternDetector {

    private static final int PER_MOVES = 40;

    /** How much worse one phase must be than the mean of the others. */
    private static final double DOMINANCE = 1.5;

    /** Below this the rate is too jumpy to compare — one blunder swings it. */
    private static final double MIN_RATE_TO_CLAIM = 1.0;

    @Override public String id()    { return "phase-weakness"; }
    @Override public String title() { return "One phase costs you most"; }

    @Override public String why() {
        return "Mistakes are not spread evenly across a game. Knowing which phase leaks "
             + "points tells you what to study, rather than studying everything at once.";
    }

    @Override public String whatToDo() {
        return "Work on the phase that is losing the points, not the one that feels "
             + "hardest — they are often different.";
    }

    @Override
    public Optional<PracticePattern> detect(List<AnalysedGame> window) {
        if (window.size() < PerGameDetector.MIN_SAMPLE) return Optional.empty();

        Map<GamePhase, Integer> errors = new EnumMap<>(GamePhase.class);
        for (GamePhase phase : GamePhase.values()) errors.put(phase, 0);

        int moveTally = 0;
        for (AnalysedGame game : window) {
            moveTally += game.playerMoveCount();
            for (MoveError error : game.errors()) {
                GamePhase phase = error.getGamePhase();
                if (phase != null) errors.merge(phase, 1, Integer::sum);
            }
        }
        if (moveTally == 0) return Optional.empty();

        // Accumulators above are mutated in a loop, so they cannot be captured
        // by the lambdas below. Freeze them once the loop is done rather than
        // re-deriving them inside each stream.
        final int playerMoves = moveTally;

        Map<GamePhase, Double> rates = new EnumMap<>(GamePhase.class);
        errors.forEach((phase, count) ->
                rates.put(phase, count * (double) PER_MOVES / playerMoves));

        GamePhase worstFound = null;
        double worstFoundRate = 0;
        for (Map.Entry<GamePhase, Double> e : rates.entrySet()) {
            if (e.getValue() > worstFoundRate) {
                worstFoundRate = e.getValue();
                worstFound = e.getKey();
            }
        }
        if (worstFound == null || worstFoundRate < MIN_RATE_TO_CLAIM) return Optional.empty();

        final GamePhase worst = worstFound;
        final double worstRate = worstFoundRate;

        double othersMean = rates.entrySet().stream()
                .filter(e -> e.getKey() != worst)
                .mapToDouble(Map.Entry::getValue)
                .average().orElse(0);

        // A phase can only be "the weak one" relative to something. When the
        // others are all zero there is no contrast to report, only a total.
        if (othersMean <= 0 || worstRate < othersMean * DOMINANCE) return Optional.empty();

        List<Occurrence> evidence = new ArrayList<>();
        for (AnalysedGame game : window) {
            long inPhase = game.errors().stream()
                    .filter(e -> e.getGamePhase() == worst)
                    .count();
            if (inPhase > 0) {
                evidence.add(Occurrence.forGame(game.gameId(),
                        inPhase + " " + human(worst).toLowerCase() + " mistake"
                                + (inPhase == 1 ? "" : "s") + " (" + game.playedOn() + ")"));
            }
        }

        String finding = "Your " + human(worst).toLowerCase() + " costs "
                + round1(worstRate) + " mistakes per " + PER_MOVES
                + " moves, against " + round1(othersMean) + " elsewhere.";

        return Optional.of(new PracticePattern(
                id(), title(), finding, why(), whatToDo(),
                PerGameDetector.strengthFor(window.size()),
                evidence.size(), window.size(),
                null,   // no single motif to drill — a phase is not a position
                List.copyOf(evidence)));
    }

    private static String human(GamePhase phase) {
        return switch (phase) {
            case OPENING    -> "Opening";
            case MIDDLEGAME -> "Middlegame";
            case ENDGAME    -> "Endgame";
        };
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
