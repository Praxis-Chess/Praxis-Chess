package com.praxis.service.settings;

import com.praxis.service.settings.AnalysisCostModel.Params;
import com.praxis.service.settings.AnalysisCostModel.Rates;
import com.praxis.service.settings.AnalysisCostModel.Sample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("AnalysisCostModel")
class AnalysisCostModelTest {

    /** The built-in library profile: 100 ms scan, depth 18, 3 lines, 3 explanations. */
    private static final Params LIB_V0 = new Params(100, 18, 3, 3);

    /**
     * Three identical games: 80 positions swept in 9,600 ms (so 120 ms per
     * position: 100 of search + 20 of overhead), 6 flagged moves deep-checked in
     * 12,000 ms (2,000 each), 3 explanations taking 36,000 ms (12,000 each).
     */
    private static List<Sample> typical() {
        Sample s = new Sample(80, 6, 3, 9_600, 12_000, 36_000);
        return List.of(s, s, s);
    }

    private static Rates rates() {
        return AnalysisCostModel.rates(typical(), LIB_V0).orElseThrow();
    }

    @Test
    @DisplayName("no estimate from fewer than three measured games")
    void refusesToGuessFromTooFewSamples() {
        Sample s = typical().get(0);
        assertThat(AnalysisCostModel.rates(List.of(s, s), LIB_V0)).isEmpty();
        assertThat(AnalysisCostModel.rates(List.of(), LIB_V0)).isEmpty();
    }

    @Test
    @DisplayName("per-unit rates are recovered from the samples")
    void derivesRates() {
        Rates r = rates();
        assertThat(r.overheadPerPlyMs()).isCloseTo(20, within(1e-9));
        assertThat(r.perCandidateMs()).isCloseTo(2_000, within(1e-9));
        assertThat(r.perExplanationMs()).isCloseTo(12_000, within(1e-9));
        assertThat(r.avgPlies()).isEqualTo(80);
        assertThat(r.avgCandidates()).isEqualTo(6);
        assertThat(r.explanationsMeasured()).isTrue();
    }

    @Test
    @DisplayName("unchanged settings reproduce the measured time")
    void identityEstimate() {
        // sweep 80 × 120 = 9,600; deep check 12,000; explanations 36,000.
        // The last two overlap, so the game costs 9,600 + max(12,000, 36,000).
        assertThat(AnalysisCostModel.perGameMs(rates(), LIB_V0)).isEqualTo(45_600);
    }

    @Test
    @DisplayName("overlap: when explanations dominate, a deeper search can be free")
    void deeperSearchHiddenBehindExplanations() {
        // depth +1: deep check 12,000 × 1.7 = 20,400 — still under the 36,000 of
        // explanations it runs alongside, so the wall time doesn't move.
        assertThat(AnalysisCostModel.perGameMs(rates(), new Params(100, 19, 3, 3))).isEqualTo(45_600);
    }

    @Test
    @DisplayName("depth and candidate lines scale the deep check")
    void depthAndLinesScaleTheDeepCheck() {
        // No explanations, so the deep check is exposed: 12,000 × (10/3) × 1.7² .
        long est = AnalysisCostModel.perGameMs(rates(), new Params(100, 20, 10, 0));
        double expected = 9_600 + 12_000 * (10.0 / 3) * Math.pow(1.7, 2);
        assertThat((double) est).isCloseTo(expected, within(1.0));
    }

    @Test
    @DisplayName("scan time scales the sweep, keeping the measured overhead")
    void scanTimeScalesTheSweep() {
        // 80 × (300 + 20) = 25,600, plus the unchanged 36,000 explanations.
        assertThat(AnalysisCostModel.perGameMs(rates(), new Params(300, 18, 3, 3))).isEqualTo(61_600);
    }

    @Test
    @DisplayName("'all' explanations means every flagged move")
    void allExplanations() {
        // 6 candidates × 12,000 = 72,000, dominating the deep check.
        assertThat(AnalysisCostModel.perGameMs(rates(), new Params(100, 18, 3, null))).isEqualTo(9_600 + 72_000);
    }

    @Test
    @DisplayName("explanations capped below the flagged-move count cost only what's written")
    void explanationCap() {
        assertThat(AnalysisCostModel.perGameMs(rates(), new Params(100, 18, 3, 1)))
                .as("1 × 12,000 is under the 12,000 deep check, so the deep check sets the pace")
                .isEqualTo(9_600 + 12_000);
    }
}
