package com.praxis.domain.enums;

/**
 * Work that counts as having examined your chess on a given day.
 *
 * The bar is effort, not presence. Opening the app is not on this list and must
 * not be added: a streak that can be kept by launching Praxis for five seconds
 * measures attendance, which is not the thing worth building a habit around.
 *
 * Two candidates were deliberately left out for the same reason — examining an
 * insight is one click, and opening a game page is a navigation. If either earns
 * a place later it has to be gated on something that costs attention (moves
 * stepped through, time genuinely engaged), not on a component mounting.
 */
public enum MeaningfulActivity {
    /** A drill attempt was graded. */
    DRILL_COMPLETED,
    /** A drill session was played to the end. */
    SESSION_COMPLETED,
    /** An analysis run finished having analysed at least one game. */
    ANALYSIS_COMPLETED,
    /** A practice game against the engine was played to a conclusion. */
    PRACTICE_GAME_PLAYED
}
