package com.praxis.evidence;

import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalInt;

/**
 * When the engine first saw it — the difficulty half of "when did it go bad?".
 *
 * <p>A mistake the engine spots at depth 2 and a mistake it needs depth 18 to
 * find are different mistakes, and deserve different sentences. The first is an
 * oversight; the second is a trap, and telling a player they were careless when
 * they walked into something only a deep search refutes is both wrong and
 * discouraging. The pipeline has never been able to tell them apart because it
 * only kept the engine's final answer.
 *
 * <p>The curve itself comes from {@code StockfishService.Search}. Everything here
 * is arithmetic on it: no searching, no board.
 */
public final class DepthCurve {

    private DepthCurve() {}

    /**
     * How hard the mistake was to see. Boundaries are conventions, not findings,
     * and are stated here rather than scattered through report code.
     */
    public enum Visibility {
        /** Depth 1–4: there to be seen by anyone looking. */
        SHALLOW,
        /** Depth 5–12: a real oversight, the bulk of club-level mistakes. */
        MEDIUM,
        /** Depth 13+: the engine had to work for it. */
        DEEP,
        /** The search never showed the drop at all within the depth it ran. */
        NEVER
    }

    /**
     * The shallowest depth at which the position after the move is already worth
     * {@code dropPawns} less to the player than the position before it.
     *
     * @param afterMove   depth → evaluation of the position after the move, in
     *                    pawns from White's point of view
     * @param parentScore evaluation before the move, same scale
     * @param playerIsWhite whose loss is being measured
     */
    public static OptionalInt visibilityDepth(NavigableMap<Integer, Double> afterMove,
                                              double parentScore,
                                              boolean playerIsWhite,
                                              double dropPawns) {
        if (afterMove == null) return OptionalInt.empty();
        for (Map.Entry<Integer, Double> entry : afterMove.entrySet()) {
            if (entry.getValue() == null) continue;
            double loss = playerIsWhite
                    ? parentScore - entry.getValue()
                    : entry.getValue() - parentScore;
            if (loss >= dropPawns) return OptionalInt.of(entry.getKey());
        }
        return OptionalInt.empty();
    }

    /**
     * The shallowest depth at which the played move already scores
     * {@code dropPawns} worse than the best move <i>at that same depth</i>.
     *
     * <p>This is the plan's D1, and the like-for-like form of the measure: both
     * curves come from searches of the position before the move, so a shallow
     * depth is compared with a shallow depth. Setting a depth-2 score against a
     * depth-14 verdict would report the difference between the two searches, not
     * the moment the move was seen to be bad.
     *
     * @param playedCurve the played move's score at each depth (searchmoves)
     * @param bestCurve   the unrestricted search's score at each depth
     */
    public static OptionalInt visibilityDepth(NavigableMap<Integer, Double> playedCurve,
                                              NavigableMap<Integer, Double> bestCurve,
                                              boolean playerIsWhite,
                                              double dropPawns) {
        if (playedCurve == null || bestCurve == null) return OptionalInt.empty();
        // "Seen" means seen and KEPT: the shallowest depth from which every deeper
        // depth also shows the drop. A shallow search is noisy, and on a real game
        // an inaccuracy read "visible at depth 1" from a spike the deeper search
        // took back. Walking down from the deepest depth finds where the verdict
        // last became stable.
        Integer seenFrom = null;
        for (Map.Entry<Integer, Double> entry : playedCurve.descendingMap().entrySet()) {
            Double played = entry.getValue();
            Double best = bestCurve.get(entry.getKey());
            if (played == null || best == null) continue;
            double loss = playerIsWhite ? best - played : played - best;
            if (loss < dropPawns) break;
            seenFrom = entry.getKey();
        }
        return seenFrom == null ? OptionalInt.empty() : OptionalInt.of(seenFrom);
    }

    /**
     * The drop worth calling "seen" for a move whose full-depth drop is
     * {@code finalLoss}: half of it, and never less than a third of a pawn.
     *
     * <p>A fixed one-pawn bar made every inaccuracy invisible (its whole drop is
     * under a pawn) while treating a mate — a hundred-pawn drop — as seen the
     * moment the engine noticed a one-pawn wobble.
     */
    public static double threshold(double finalLoss) {
        return Math.max(0.3, finalLoss / 2);
    }

    public static Visibility classify(OptionalInt depth) {
        if (depth.isEmpty()) return Visibility.NEVER;
        return classify(depth.getAsInt());
    }

    public static Visibility classify(int depth) {
        if (depth <= 0) return Visibility.NEVER;
        if (depth <= 4) return Visibility.SHALLOW;
        if (depth <= 12) return Visibility.MEDIUM;
        return Visibility.DEEP;
    }

    /**
     * True when the evaluation was still moving at the deepest depth searched.
     *
     * <p>A curve that has not settled means the number at the end is not the
     * engine's verdict, it is where the engine happened to stop. Citing it as if
     * it were settled is the quiet way an evidence graph starts lying.
     */
    public static boolean unsettled(NavigableMap<Integer, Double> curve, double tolerancePawns) {
        if (curve == null || curve.size() < 2) return true;
        var it = curve.descendingMap().entrySet().iterator();
        Double last = it.next().getValue();
        Double previous = it.next().getValue();
        if (last == null || previous == null) return true;
        return Math.abs(last - previous) > tolerancePawns;
    }
}
