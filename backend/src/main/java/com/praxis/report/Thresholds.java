package com.praxis.report;

/**
 * The thresholds every page uses, named by what they count (principle 5, "one
 * threshold", in DESIGN_SYSTEM.md). Two of them, not one per feature: before,
 * the Play page alone used eight, and the same word ("enough") meant five games
 * on one screen and ten mistakes on the next.
 */
public final class Thresholds {

    private Thresholds() {}

    /**
     * Any claim about games: an opening to target or to review, a weaker colour,
     * a practice tendency, a practice game's direction. Fewer games than this
     * and the page says so instead of claiming anything.
     */
    public static final int MIN_GAMES = 5;

    /**
     * Any rate or share: fewer mistakes than this behind a figure and it is drawn
     * faded, marked few, and never ranked or called the worst.
     */
    public static final int MIN_MISTAKES = 10;

    /** Every rate is mistakes per this many of the player's own moves. */
    public static final int PER_MOVES = 100;
}
