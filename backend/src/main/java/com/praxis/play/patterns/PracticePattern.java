package com.praxis.play.patterns;

import java.util.List;

/**
 * One tendency, with everything needed to believe it and act on it.
 *
 * @param id          stable key, e.g. "castling-delay"
 * @param title       the tendency, as a short phrase
 * @param finding     one sentence with the numbers in it
 * @param why         authored constant: what this costs. Not generated, and not
 *                    about this player — it is a fixed chess principle, true
 *                    whoever is reading.
 * @param whatToDo    authored constant: the corrective principle, not a move
 * @param strength    whether the sample supports stating this plainly
 * @param drillMotif  the motif to practise, or null when there is no position
 *                    whose best move is the lesson. Habit patterns (castling,
 *                    development) deliberately carry null: the engine's choice
 *                    in those positions is whatever the position demands, which
 *                    is usually not the thing the pattern is about.
 * @param evidence    the games this came from, so the claim can be checked
 */
public record PracticePattern(
        String id,
        String title,
        String finding,
        String why,
        String whatToDo,
        Strength strength,
        int gamesAffected,
        int gamesConsidered,
        String drillMotif,
        List<Occurrence> evidence
) {}
