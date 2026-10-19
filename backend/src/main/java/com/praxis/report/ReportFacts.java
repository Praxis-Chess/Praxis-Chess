package com.praxis.report;

import java.util.List;
import java.util.UUID;

/**
 * Everything the Pattern Report states, computed in code (Phase 12).
 *
 * <p>The page renders every figure from here. "Serious" means blunders and
 * mistakes; inaccuracies are counted but drive nothing.
 *
 * <p>Two separate axes, never ranked together: what went wrong (the verified
 * cause, or time trouble) and the tactic involved. One mistake can have both.
 *
 * @param playerMoves   the player's own moves in the analysed games: the base of every rate
 * @param byPhase       serious mistakes per phase, with the player's moves in it
 * @param byMoveRange   the same by full moves 1-10, 11-20, 21-30, 31+
 * @param causes        verified causes over diagnosed serious mistakes (NONE excluded)
 * @param tactics       named tactics over diagnosed serious mistakes
 * @param noNamedTactic diagnosed serious mistakes where the rules proved no tactic (positional or other)
 * @param noCause       diagnosed serious mistakes the rules found no cause for (mechanism NONE)
 * @param minSample     the one threshold for every figure: fewer mistakes than this behind it, and
 *                      it is not ranked (weaknesses) or is marked as too few (rates, clock)
 * @param topCauses     the costliest causes by total winning chances lost, at most three
 * @param topTactics    the costliest tactics your opponent had, ranked the same way, at most two
 * @param perGame       serious mistakes per analysed game: the baseline for openings
 * @param openings      openings at least a fifth above that baseline, with three games or more
 * @param worstPhase    the phase with the most serious mistakes per 100 moves
 * @param worstRange    the move range with the most serious mistakes per 100 moves
 */
public record ReportFacts(
        int gamesAnalysed,
        int mistakes,
        int blunders,
        int seriousMistakes,
        int inaccuracies,
        int serious,
        int diagnosed,
        int diagnosedSerious,
        int playerMoves,
        List<Rate> byPhase,
        List<Rate> byMoveRange,
        List<Count> causes,
        List<Count> tactics,
        int noNamedTactic,
        int noCause,
        int minSample,
        TimePressure timePressure,
        List<Weakness> topCauses,
        List<Weakness> topTactics,
        double perGame,
        List<OpeningRow> openings,
        String worstPhase,
        String worstRange
) {

    /** A share of diagnosed serious mistakes. */
    public record Count(String key, String label, int count, int blunders, int pct) {}

    /**
     * @param moves      the player's moves in this group
     * @param perHundred serious mistakes per 100 of those moves, one decimal
     * @param enough     at least minSample mistakes behind the rate
     */
    public record Rate(String key, String label, int count, int blunders, int moves, double perHundred,
                       boolean enough) {}

    /**
     * @param withClock   serious mistakes with a recorded clock
     * @param underThirty of those, made with 30 seconds or less left
     * @param enough      whether withClock is large enough to say anything
     */
    public record TimePressure(int withClock, int underThirty, int blundersUnderThirty, int pct, boolean enough) {}

    /** @param vsAverage seriousPerGame divided by the player's own per-game rate */
    public record OpeningRow(String eco, String name, int games, int serious, int blunders,
                             double seriousPerGame, double vsAverage) {}

    /**
     * One weakness, chosen and worded in code.
     *
     * @param kind      CAUSE, TIME_PRESSURE or TACTIC
     * @param base      what count is a share of
     * @param lost      winning-chance points lost in total (win% drop, 0-100 per move): the ranking
     * @param avgLost   lost per mistake, one decimal
     * @param drill     what to practise, one sentence
     * @param positions every position behind it, costliest first
     */
    public record Weakness(String kind, String key, String title, int count, int base, int pct, int blunders,
                           int lost, double avgLost, String drill, List<Example> positions) {}

    /**
     * @param move "12...Nf6": full move number and side, as a chess reader writes it
     * @param lost winning-chance points this move lost, one decimal
     */
    public record Example(UUID gameId, int ply, String move, String severity, String fen,
                          String betterMove, String playedOn, String opening, double lost) {}
}
