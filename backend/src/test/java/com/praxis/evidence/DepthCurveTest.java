package com.praxis.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "When did the engine first see it" — arithmetic on a depth curve.
 *
 * The point of the measure is to separate an oversight from a trap. Getting the
 * sign wrong for one colour would make every one of Black's mistakes look
 * invisible and every one of White's look obvious, which is exactly the class of
 * bug that hides for months because both halves look plausible on their own.
 */
@DisplayName("Depth curve")
class DepthCurveTest {

    private static NavigableMap<Integer, Double> curve(double... scoresFromDepthOne) {
        NavigableMap<Integer, Double> out = new TreeMap<>();
        for (int i = 0; i < scoresFromDepthOne.length; i++) out.put(i + 1, scoresFromDepthOne[i]);
        return out;
    }

    @Test
    @DisplayName("finds the first depth where the player's loss reaches the threshold")
    void findsTheFirstDepthShowingTheDrop() {
        // White was level; after the move the engine gradually finds Black is winning.
        var after = curve(0.1, 0.0, -0.4, -2.6, -3.1);

        var depth = DepthCurve.visibilityDepth(after, 0.2, true, 2.0);

        assertThat(depth).hasValue(4);
        assertThat(DepthCurve.classify(depth)).isEqualTo(DepthCurve.Visibility.SHALLOW);
    }

    @Test
    @DisplayName("the same curve means the opposite for Black")
    void lossIsMeasuredFromThePlayersSide() {
        // Identical numbers. For Black, an evaluation falling towards White is a
        // gain, so no loss is ever visible.
        var after = curve(0.1, 0.0, -0.4, -2.6, -3.1);

        assertThat(DepthCurve.visibilityDepth(after, 0.2, false, 2.0)).isEmpty();

        // Reverse the curve and Black's loss appears where White's did.
        var black = curve(-0.1, 0.0, 0.4, 2.6, 3.1);
        assertThat(DepthCurve.visibilityDepth(black, -0.2, false, 2.0)).hasValue(4);
    }

    @Test
    @DisplayName("a drop the search never showed is NEVER, not depth zero")
    void aDropNeverSeenIsReportedAsSuch() {
        var after = curve(0.1, 0.05, 0.0, -0.1);

        var depth = DepthCurve.visibilityDepth(after, 0.2, true, 2.0);

        assertThat(depth).isEmpty();
        assertThat(DepthCurve.classify(depth)).isEqualTo(DepthCurve.Visibility.NEVER);
    }

    /**
     * The plan's D1 compares the played move with the best move at the SAME
     * depth. A shallow search overstates or understates everything, so a depth-2
     * score set against a depth-14 verdict measures the difference between two
     * searches, not the moment the move was seen to be bad.
     */
    @Test
    @DisplayName("like for like: each depth against the best move at that depth")
    void comparesEachDepthWithTheSameDepth() {
        // Both curves drift upward together as the search deepens; the played
        // move only falls a pawn behind the best move at depth 3.
        var best = curve(0.5, 1.0, 1.5, 2.0);
        var played = curve(0.4, 0.9, 0.4, 0.8);

        assertThat(DepthCurve.visibilityDepth(played, best, true, 1.0)).hasValue(3);
        // Against the final verdict alone (2.0), depth 1 would already look like a
        // 1.6-pawn drop — the misreading the like-for-like form exists to avoid.
        assertThat(DepthCurve.visibilityDepth(played, 2.0, true, 1.0)).hasValue(1);
    }

    /**
     * Seen means seen and kept. A shallow search is noisy; on a real game an
     * inaccuracy read "visible at depth 1" from a spike the deeper search took
     * back.
     */
    @Test
    @DisplayName("a shallow spike the deeper search takes back does not count")
    void aSpikeThatDisappearsIsNotSeeing() {
        var best = curve(1.0, 1.0, 1.0, 1.0, 1.0);

        // Spike at depth 1, gone at 2, then seen for good from depth 3.
        assertThat(DepthCurve.visibilityDepth(curve(-0.5, 0.9, -0.3, -0.4, -0.5), best, true, 1.0))
                .hasValue(3);
        // Spike at depth 1 that never comes back: not seen at all.
        assertThat(DepthCurve.visibilityDepth(curve(-0.5, 0.9, 0.9, 0.9, 0.9), best, true, 1.0))
                .isEmpty();
    }

    @Test
    @DisplayName("the bar scales with how bad the move finally is")
    void thresholdScalesWithTheDrop() {
        // An inaccuracy is never a whole pawn, so a fixed one-pawn bar hid them all.
        assertThat(DepthCurve.threshold(0.4)).isEqualTo(0.3);
        assertThat(DepthCurve.threshold(3.0)).isEqualTo(1.5);
        // A mate is not "seen" at the first one-pawn wobble.
        assertThat(DepthCurve.threshold(100.0)).isEqualTo(50.0);
    }

    @Test
    @DisplayName("the bands are where the report says they are")
    void bands() {
        assertThat(DepthCurve.classify(1)).isEqualTo(DepthCurve.Visibility.SHALLOW);
        assertThat(DepthCurve.classify(4)).isEqualTo(DepthCurve.Visibility.SHALLOW);
        assertThat(DepthCurve.classify(5)).isEqualTo(DepthCurve.Visibility.MEDIUM);
        assertThat(DepthCurve.classify(12)).isEqualTo(DepthCurve.Visibility.MEDIUM);
        assertThat(DepthCurve.classify(13)).isEqualTo(DepthCurve.Visibility.DEEP);
    }

    @Test
    @DisplayName("a curve still moving at the end is not a verdict")
    void unsettledCurvesAreFlagged() {
        assertThat(DepthCurve.unsettled(curve(0.1, 0.1, 0.1, 0.12), 0.1)).isFalse();
        assertThat(DepthCurve.unsettled(curve(0.1, 0.1, 0.1, -2.4), 0.1)).isTrue();
        // Too short to tell — treated as unsettled rather than quietly trusted.
        assertThat(DepthCurve.unsettled(curve(0.1), 0.1)).isTrue();
    }
}
