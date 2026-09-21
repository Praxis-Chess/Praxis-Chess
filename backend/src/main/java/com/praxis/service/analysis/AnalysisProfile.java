package com.praxis.service.analysis;

/**
 * How hard to work on one game.
 *
 * The pipeline's limits were all chosen for a library run — 100-odd games in one
 * pass, where three Ollama calls per game is already 35-50 minutes of inference.
 * A practice game is one game, played deliberately, on its own executor, with a
 * user watching a progress bar. The same numbers are far too mean there: the
 * player is shown eight flagged moves and told the engine had nothing to say
 * about five of them.
 *
 * Nothing about the MEASUREMENT changes between profiles. Severity thresholds,
 * the accuracy formula and the win-% maths live in {@link MistakeCandidateFilter}
 * and are shared, because a practice game is compared against other games and a
 * profile that moved the thresholds would be a different ruler. These knobs only
 * govern how much engine time and how much prose each flagged move gets.
 *
 * @param sweepMoveTimeMs per-position movetime for the first pass over every ply
 * @param multiPvDepth    depth for the per-candidate MultiPV enrichment
 * @param multiPvLines    how many engine lines to keep per candidate
 * @param maxOllamaCalls  how many mistakes get a written explanation; the rest
 *                        persist engine-only as {@code SKIPPED}
 */
public record AnalysisProfile(
        String name,
        int sweepMoveTimeMs,
        int multiPvDepth,
        int multiPvLines,
        int maxOllamaCalls
) {

    /**
     * Bulk analysis of the Chess.com library. These are the historical values —
     * this profile exists to name them, not to change them.
     */
    public static final AnalysisProfile LIBRARY =
            new AnalysisProfile("library", 100, 18, 3, 3);

    /**
     * A single practice game, analysed on {@code practiceExecutor} while the
     * player waits.
     *
     * The ceiling is not taste, it is the client: PlayImprove polls the report
     * for REPORT_MAX_TRIES × REPORT_POLL_MS = five minutes and then gives up and
     * tells the user the game is still queued. An unbounded number of Ollama
     * calls would let a badly-played game — where nearly every move is flagged —
     * run past that and produce a report nobody is still waiting for.
     *
     * Ten calls is roughly two minutes of inference, and the MultiPV sweep is
     * overlapped with it rather than added to it, so a typical game lands around
     * three minutes with headroom against the five-minute cutoff.
     *
     * Note this shares the one synchronized StockfishService with library
     * analysis, so a practice game analysed during a Re-analyze All still waits
     * its turn — deeper settings make that wait longer for both sides. That
     * contention predates this profile; it is not introduced by it.
     */
    public static final AnalysisProfile PRACTICE =
            new AnalysisProfile("practice", 200, 20, 4, 10);
}
