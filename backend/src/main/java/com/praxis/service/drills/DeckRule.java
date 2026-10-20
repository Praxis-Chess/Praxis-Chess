package com.praxis.service.drills;

/**
 * The review deck's rule (docs/design/POLISH_PLAN.md, D3 and D8, decided 20 Oct):
 *
 * - Every analysed blunder and mistake from a Chess.com game can become a card;
 *   inaccuracies never do, and practice games stay out.
 * - About {@link #TARGET_ACTIVE_CARDS} cards are active at a time. When there are
 *   more candidates than room, blunders go first, then the Training Plan's focus
 *   positions, then the most recent.
 * - Cards are created after each analysis run, and once at startup (which is the
 *   one-off backfill of games analysed before this rule). Creation is idempotent:
 *   one card per game and ply.
 * - A new card can be drilled the day it is created; at most
 *   {@link #NEW_PER_DAY} new cards are introduced a day. Only reviews are spaced.
 */
public final class DeckRule {

    private DeckRule() {}

    /** The deck's size: room for new cards is this minus the active cards. */
    public static final int TARGET_ACTIVE_CARDS = 120;

    /** New cards introduced a day (D3c): fits the 15–20 minute session with its reviews. */
    public static final int NEW_PER_DAY = 10;
}
