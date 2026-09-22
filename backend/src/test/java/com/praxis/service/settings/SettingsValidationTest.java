package com.praxis.service.settings;

import com.praxis.domain.AnalysisSettings;
import com.praxis.dto.SettingsDto.EngineConfig;
import com.praxis.dto.SettingsDto.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounds are enforced, never clamped: a clamped depth would be a different
 * ruler from the one the user asked for, recorded on every game analysed with it.
 */
@DisplayName("Settings validation")
class SettingsValidationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);
    private static final EngineConfig OK = new EngineConfig(100, 18, 3, 3);

    private static Map<String, String> validate(Update u) {
        return SettingsService.validate(u, TODAY);
    }

    private static Update engines(EngineConfig lib, EngineConfig pra) {
        return new Update(null, null, null, null, lib, pra);
    }

    @Test
    @DisplayName("the built-in profiles are valid")
    void defaultsAreValid() {
        assertThat(validate(engines(OK, new EngineConfig(200, 20, 4, 10)))).isEmpty();
    }

    @Test
    @DisplayName("every out-of-range engine value is reported at once")
    void reportsAllEngineErrorsTogether() {
        Map<String, String> e = validate(engines(new EngineConfig(10, 40, 11, 99), null));
        assertThat(e).containsOnlyKeys(
                "library_sweep_move_time_ms", "library_multi_pv_depth",
                "library_multi_pv_lines", "library_max_explanations");
    }

    @Test
    @DisplayName("the bounds themselves are allowed")
    void boundsAreInclusive() {
        assertThat(validate(engines(new EngineConfig(50, 12, 1, 0), new EngineConfig(1_000, 30, 10, 50)))).isEmpty();
    }

    @Test
    @DisplayName("'all' explanations (null) is valid")
    void allExplanationsIsValid() {
        assertThat(validate(engines(new EngineConfig(100, 18, 3, null), null))).isEmpty();
    }

    @Test
    @DisplayName("a missing engine field is required, not defaulted")
    void missingFieldIsAnError() {
        assertThat(validate(engines(new EngineConfig(100, null, 3, 3), null)))
                .containsKey("library_multi_pv_depth");
    }

    @Test
    @DisplayName("an omitted kind is left unchanged")
    void omittedKindIsNotValidated() {
        assertThat(validate(engines(null, null))).isEmpty();
    }

    @Test
    @DisplayName("a sync range needs both ends or neither")
    void syncRangeIsAllOrNothing() {
        assertThat(validate(new Update(LocalDate.of(2026, 6, 1), null, null, null, null, null)))
                .containsKey("sync_range");
        assertThat(validate(new Update(null, null, null, null, null, null))).isEmpty();
    }

    @Test
    @DisplayName("a backwards, pre-2007 or future-starting sync range is rejected")
    void syncRangeBounds() {
        assertThat(validate(new Update(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 7, 1), null, null, null, null)))
                .containsKey("sync_range");
        assertThat(validate(new Update(LocalDate.of(2005, 1, 1), LocalDate.of(2026, 7, 1), null, null, null, null)))
                .containsKey("sync_range");
        assertThat(validate(new Update(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), null, null, null, null)))
                .containsKey("sync_range");
    }

    @Test
    @DisplayName("an analysis range may be open on either side, but not backwards")
    void analysisRange() {
        assertThat(validate(new Update(null, null, LocalDate.of(2026, 6, 1), null, null, null))).isEmpty();
        assertThat(validate(new Update(null, null, null, LocalDate.of(2026, 6, 1), null, null))).isEmpty();
        assertThat(validate(new Update(null, null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 6, 1), null, null)))
                .containsKey("analysis_range");
    }

    @Test
    @DisplayName("an identical configuration is not a new version")
    void sameEngineIsDetected() {
        AnalysisSettings a = SettingsService.v0(AnalysisSettings.LIBRARY);
        AnalysisSettings b = SettingsService.v0(AnalysisSettings.LIBRARY);
        assertThat(a.sameEngineAs(b)).isTrue();
        b.setMultiPvDepth(22);
        assertThat(a.sameEngineAs(b)).isFalse();
    }

    @Test
    @DisplayName("v0 carries the built-in values every existing game was analysed with")
    void v0MatchesTheBuiltInProfiles() {
        AnalysisSettings lib = SettingsService.v0(AnalysisSettings.LIBRARY);
        assertThat(lib.getLabel()).isEqualTo("library-v0");
        assertThat(lib.getSweepMoveTimeMs()).isEqualTo(100);
        assertThat(lib.getMultiPvDepth()).isEqualTo(18);
        assertThat(lib.getMultiPvLines()).isEqualTo(3);
        assertThat(lib.getMaxExplanations()).isEqualTo(3);

        AnalysisSettings pra = SettingsService.v0(AnalysisSettings.PRACTICE);
        assertThat(pra.getLabel()).isEqualTo("practice-v0");
        assertThat(pra.getMultiPvDepth()).isEqualTo(20);
        assertThat(pra.getMaxExplanations()).isEqualTo(10);
    }
}
