package com.praxis.service.analysis;

/**
 * Where a single game's analysis reports how far it has got.
 *
 * The library run has {@code AnalysisProgressTracker}, which counts whole games
 * and is the right granularity for "47 of 101 games". A practice game is one
 * game, so that tracker can only ever say 1/1 — and a practice game does not
 * register with it at all, because it runs on a different executor and would
 * corrupt the library run's counts if it did.
 *
 * Hence this: the same information at the granularity one game actually has,
 * pushed by the pipeline and read by whoever is waiting. Every count here is a
 * real total known at the time it is reported — plies come from the parsed game,
 * candidates from the filter, explanations from the profile's budget. Nothing is
 * extrapolated, because a percentage nobody counted is the kind that sits at 90%.
 */
public interface AnalysisProgressSink {

    /** Discards everything. The library run's per-game progress is already tracked. */
    AnalysisProgressSink NOOP = new AnalysisProgressSink() {};

    /** Evaluating every position. {@code total} is the ply count. */
    default void sweeping(int done, int total) {}

    /** Deep MultiPV search per flagged move. {@code total} is the candidate count. */
    default void enriching(int done, int total) {}

    /** Asking the model for prose. {@code total} is the profile's budget, capped by candidates. */
    default void explaining(int done, int total) {}

    /** No further updates will follow, whether the analysis succeeded or not. */
    default void finished() {}
}
