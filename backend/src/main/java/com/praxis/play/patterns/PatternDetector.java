package com.praxis.play.patterns;

import java.util.List;
import java.util.Optional;

/**
 * One rule about how a player plays.
 *
 * A detector is not just a threshold; it is a named chess concept, so it owes
 * the reader {@link #why()} and {@link #whatToDo()} as well as a count. Those
 * two are authored constants identical for every player — there is no per-game
 * inference in them to be wrong about, and nothing for a model to fabricate.
 *
 * A detector missing either is not finished. If we cannot say why a tendency
 * costs something, we should not be telling a player they have it.
 *
 * Most detectors ask the same question of each game independently and then
 * apply the recurrence rule; those extend {@link PerGameDetector} rather than
 * implementing this directly, so the rule lives in one place.
 */
public interface PatternDetector {

    /** Stable key, e.g. "castling-delay". Survives wording changes. */
    String id();

    String title();

    /** What this costs. One sentence, fixed. */
    String why();

    /** The corrective principle — not a move. */
    String whatToDo();

    /**
     * The motif whose positions would practise this, or null when no position's
     * best move is the lesson. See PracticePattern.drillMotif.
     */
    default String drillMotif() {
        return null;
    }

    /**
     * @param window games already filtered to rated, finished and analysed,
     *               newest first, capped at the window size
     * @return the pattern, or empty when this tendency is not present often
     *         enough to name
     */
    Optional<PracticePattern> detect(List<AnalysedGame> window);
}
