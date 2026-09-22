package com.praxis.service.settings;

import com.praxis.domain.AnalysisSettings;
import com.praxis.repository.AnalysisSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as "already analysed with the current settings".
 *
 * Found on the live database, not by these tests: raising the depth and putting
 * it back leaves a third version whose engine numbers are identical to v0's.
 * Comparing row ids declared all 105 analysed games outdated and offered to
 * re-analyse them — 74 minutes of work, their drill cards deleted, and byte-for-byte
 * the same output. The ruler is the engine configuration, not the row id.
 */
@DisplayName("Engine ruler equivalence")
class EngineRulerTest {

    private static AnalysisSettings version(long id, String label, int depth) {
        return AnalysisSettings.builder()
                .id(id).kind(AnalysisSettings.LIBRARY).label(label)
                .sweepMoveTimeMs(100).multiPvDepth(depth).multiPvLines(3).maxExplanations(3)
                .active(true)
                .build();
    }

    /** A service with only the version repository wired; nothing else is touched. */
    private static SettingsService serviceOver(List<AnalysisSettings> rows) {
        AnalysisSettingsRepository repo = Mockito.mock(AnalysisSettingsRepository.class);
        // Newest active row wins, which is how the real query is ordered.
        Mockito.when(repo.findFirstByKindAndActiveTrueOrderByIdDesc(AnalysisSettings.LIBRARY))
                .thenReturn(rows.stream().filter(AnalysisSettings::isActive)
                        .reduce((a, b) -> a.getId() > b.getId() ? a : b));
        Mockito.when(repo.findByKindOrderByIdDesc(AnalysisSettings.LIBRARY)).thenReturn(rows);
        return new SettingsService(repo, null, null, null, null, null);
    }

    @Test
    @DisplayName("a version reverted to earlier numbers is the same ruler as the original")
    void revertedVersionIsNotOutdated() {
        AnalysisSettings v0 = version(1, "library-v0", 18);
        AnalysisSettings v1 = version(5, "library-v1", 24);
        AnalysisSettings v2 = version(6, "library-v2", 18);   // active, back to v0's numbers

        Set<Long> current = serviceOver(List.of(v2, v1, v0))
                .engineEquivalentIds(AnalysisSettings.LIBRARY);

        // v0's games were measured exactly as v2 measures, so they are current.
        assertThat(current).containsExactlyInAnyOrder(1L, 6L);
        assertThat(current).doesNotContain(5L);
    }

    @Test
    @DisplayName("a genuinely different depth is a different ruler")
    void changedDepthIsOutdated() {
        AnalysisSettings v0 = version(1, "library-v0", 18);
        AnalysisSettings v1 = version(5, "library-v1", 24);   // active

        Set<Long> current = serviceOver(List.of(v1, v0)).engineEquivalentIds(AnalysisSettings.LIBRARY);

        assertThat(current).containsExactly(5L);
    }

    @Test
    @DisplayName("a label change alone does not make games outdated")
    void labelIsNotTheRuler() {
        AnalysisSettings v0 = version(1, "library-v0", 18);
        AnalysisSettings renamed = version(2, "deep-scan", 18);

        assertThat(serviceOver(List.of(renamed, v0)).engineEquivalentIds(AnalysisSettings.LIBRARY))
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("every engine field counts, not just depth")
    void everyFieldCounts() {
        AnalysisSettings base = version(1, "library-v0", 18);

        AnalysisSettings moreLines = AnalysisSettings.builder()
                .id(2L).kind(AnalysisSettings.LIBRARY).label("library-v1")
                .sweepMoveTimeMs(100).multiPvDepth(18).multiPvLines(5).maxExplanations(3)
                .active(true).build();
        assertThat(serviceOver(List.of(moreLines, base)).engineEquivalentIds(AnalysisSettings.LIBRARY))
                .containsExactly(2L);

        AnalysisSettings slowerSweep = AnalysisSettings.builder()
                .id(2L).kind(AnalysisSettings.LIBRARY).label("library-v1")
                .sweepMoveTimeMs(250).multiPvDepth(18).multiPvLines(3).maxExplanations(3)
                .active(true).build();
        assertThat(serviceOver(List.of(slowerSweep, base)).engineEquivalentIds(AnalysisSettings.LIBRARY))
                .containsExactly(2L);

        // "Every flagged move" is stored as null, and null is not 3.
        AnalysisSettings everyMove = AnalysisSettings.builder()
                .id(2L).kind(AnalysisSettings.LIBRARY).label("library-v1")
                .sweepMoveTimeMs(100).multiPvDepth(18).multiPvLines(3).maxExplanations(null)
                .active(true).build();
        assertThat(serviceOver(List.of(everyMove, base)).engineEquivalentIds(AnalysisSettings.LIBRARY))
                .containsExactly(2L);
    }

    @Test
    @DisplayName("a game with no recorded settings is never current")
    void unattributedGameIsOutdated() {
        AnalysisSettings v0 = version(1, "library-v0", 18);
        assertThat(serviceOver(List.of(v0)).engineEquivalentIds(AnalysisSettings.LIBRARY))
                .doesNotContain((Long) null);
    }
}
