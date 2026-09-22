package com.praxis.service.settings;

import java.util.List;
import java.util.Optional;

/**
 * Estimates how long a game takes to analyse under a PROPOSED configuration,
 * from timings MEASURED under the current one.
 *
 * Each stage is scaled by the setting that drives it:
 * <ul>
 *   <li><b>sweep</b> — every position gets {@code sweepMs} of search, plus a fixed
 *       per-position overhead (parsing, engine round trip) measured from the
 *       samples: {@code plies × (sweepMs + overhead)};</li>
 *   <li><b>deep check</b> — per flagged move, scaled by the number of candidate
 *       lines (roughly linear) and by {@link #DEPTH_FACTOR} per extra ply;</li>
 *   <li><b>explanations</b> — per written explanation, capped by the setting.</li>
 * </ul>
 * The deep check and the explanations run concurrently (the explanation
 * consumer works while the engine enriches the next move), so a game costs the
 * sweep plus the LONGER of the two, not their sum.
 *
 * Only the depth factor is a rule of thumb rather than a measurement: each
 * extra ply of search costs roughly 1.5–2× the time. Everything else is
 * measured on this machine.
 */
public final class AnalysisCostModel {

    private AnalysisCostModel() {}

    /** Time multiplier per extra ply of search depth (the one assumed figure). */
    public static final double DEPTH_FACTOR = 1.7;

    /** Below this many measured games, no estimate is offered. */
    public static final int MIN_SAMPLES = 3;

    public record Params(int sweepMs, int depth, int lines, Integer maxExplanations) {}

    public record Sample(int plies, int candidates, int explanations,
                         long sweepMs, long enrichMs, long explainMs) {}

    /**
     * Per-unit costs derived from samples measured under {@code measuredAt}.
     *
     * @param explanationsMeasured false when no sample wrote an explanation,
     *                             so the explanation cost is unknown (treated as 0)
     */
    public record Rates(double overheadPerPlyMs, double perCandidateMs, double perExplanationMs,
                        double avgPlies, double avgCandidates, int samples,
                        boolean explanationsMeasured, Params measuredAt) {}

    public static Optional<Rates> rates(List<Sample> samples, Params measuredAt) {
        if (samples == null || samples.size() < MIN_SAMPLES) return Optional.empty();

        long plies = 0, candidates = 0, explanations = 0, sweep = 0, enrich = 0, explain = 0;
        for (Sample s : samples) {
            plies += s.plies();
            candidates += s.candidates();
            explanations += s.explanations();
            sweep += s.sweepMs();
            enrich += s.enrichMs();
            explain += s.explainMs();
        }
        int n = samples.size();
        double perPly = plies == 0 ? 0 : (double) sweep / plies;
        return Optional.of(new Rates(
                Math.max(0, perPly - measuredAt.sweepMs()),
                candidates == 0 ? 0 : (double) enrich / candidates,
                explanations == 0 ? 0 : (double) explain / explanations,
                (double) plies / n,
                (double) candidates / n,
                n,
                explanations > 0,
                measuredAt));
    }

    /** Estimated wall time for one typical game under {@code proposed}. */
    public static long perGameMs(Rates r, Params proposed) {
        Params at = r.measuredAt();

        double sweep = r.avgPlies() * (proposed.sweepMs() + r.overheadPerPlyMs());

        double lineRatio = at.lines() == 0 ? 1 : (double) proposed.lines() / at.lines();
        double depthRatio = Math.pow(DEPTH_FACTOR, proposed.depth() - at.depth());
        double enrich = r.avgCandidates() * r.perCandidateMs() * lineRatio * depthRatio;

        double written = proposed.maxExplanations() == null
                ? r.avgCandidates()
                : Math.min(proposed.maxExplanations(), r.avgCandidates());
        double explain = written * r.perExplanationMs();

        return Math.round(sweep + Math.max(enrich, explain));
    }
}
