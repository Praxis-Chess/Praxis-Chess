package com.praxis.play.patterns;

import java.util.List;

/**
 * @param gamesConsidered size of the window actually used, after filtering
 * @param claimable       false below MIN_SAMPLE: the counts are still returned,
 *                        but no tendency is named
 * @param caveat          null since the Play redesign: below the threshold the page
 *                        shows progress (gamesConsidered of minGames) instead
 * @param openingCaveat   set when every game in the window is one opening, which
 *                        is the normal case: the opponent steers to the player's
 *                        weakest ECO, so a habit and an unfamiliar opening look
 *                        alike from here. Belongs above the patterns it qualifies.
 * @param notMeasured     what this report does NOT look at, and why. An absence
 *                        the reader cannot see reads as a clean bill.
 */
public record PracticePatternReport(
        int gamesConsidered,
        boolean claimable,
        String caveat,
        String openingCaveat,
        List<PracticePattern> patterns,
        List<String> notMeasured,
        /** The games threshold: patterns are named from this many analysed practice games. */
        int minGames,
        /** The one opening every game in the window was aimed at, or null when they differ. */
        String aimedAt
) {}
