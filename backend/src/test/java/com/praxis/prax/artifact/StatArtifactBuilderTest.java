package com.praxis.prax.artifact;

import com.praxis.prax.artifact.TableArtifact.Align;
import com.praxis.prax.intelligence.ChessIntelligence.MotifStat;
import com.praxis.prax.intelligence.ChessIntelligence.OpeningStat;
import com.praxis.prax.intelligence.ChessIntelligence.PhaseStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Charts and tables are transcriptions of counts the database already made. The
 * risk is not that a number is wrong — it is that a row is dropped, a column
 * shifts, or an unmeasured value renders as zero and reads as a bad result.
 */
@DisplayName("Stat Artifact Builder Tests")
class StatArtifactBuilderTest {

    private StatArtifactBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new StatArtifactBuilder();
    }

    @Nested
    @DisplayName("Chart Tests")
    class ChartTests {

        @Test
        @DisplayName("Should turn motif counts into bars, largest first as given")
        void shouldBuildMotifChart() {
            var chart = (ChartArtifact) builder.mistakesByMotif(List.of(
                    new MotifStat("POSITIONAL", 154, 90, List.of()),
                    new MotifStat("HANGING_PIECE", 63, 40, List.of())));

            assertThat(chart).isNotNull();
            assertThat(chart.chartId()).isEqualTo(ChartArtifact.ChartId.MISTAKES_BY_MOTIF);
            assertThat(chart.bars()).hasSize(2);
            assertThat(chart.bars().get(0).value()).isEqualTo(154);
        }

        @Test
        @DisplayName("Should render enum names as chess, not as database columns")
        void shouldHumaniseLabels() {
            // "HANGING_PIECE" in a player-facing chart is the same leak as the
            // model quoting 'WALKS_INTO_MATE' into its prose.
            assertThat(StatArtifactBuilder.humanise("HANGING_PIECE")).isEqualTo("Hanging piece");
            assertThat(StatArtifactBuilder.humanise("MIDDLEGAME")).isEqualTo("Middlegame");
            assertThat(StatArtifactBuilder.humanise(null)).isEqualTo("Unknown");
        }

        @Test
        @DisplayName("Should cap the number of bars")
        void shouldCapBars() {
            var many = new java.util.ArrayList<MotifStat>();
            for (int i = 0; i < 20; i++) many.add(new MotifStat("M" + i, 20 - i, 0, List.of()));

            var chart = (ChartArtifact) builder.mistakesByMotif(many);

            assertThat(chart.bars()).hasSizeLessThanOrEqualTo(StatArtifactBuilder.MAX_BARS);
        }

        @Test
        @DisplayName("Should produce nothing rather than an empty chart")
        void shouldReturnNullWhenNothingToShow() {
            // An axis with no bars is a frame around emptiness — worse than no
            // chart, because it implies the data was checked and was zero.
            assertThat(builder.mistakesByMotif(List.of())).isNull();
            assertThat(builder.mistakesByMotif(null)).isNull();
            assertThat(builder.mistakesByMotif(List.of(new MotifStat("X", 0, 0, List.of())))).isNull();
        }

        @Test
        @DisplayName("Should skip phases with no errors")
        void shouldSkipCleanPhases() {
            var chart = (ChartArtifact) builder.mistakesByPhase(List.of(
                    new PhaseStat("OPENING", 0, 0, 80.0, 10),
                    new PhaseStat("MIDDLEGAME", 97, 60, 64.0, 10)));

            assertThat(chart).isNotNull();
            assertThat(chart.bars()).hasSize(1);
            assertThat(chart.bars().get(0).label()).isEqualTo("Middlegame");
        }
    }

    @Nested
    @DisplayName("Table Tests")
    class TableTests {

        @Test
        @DisplayName("Should build one row per opening with matching column count")
        void shouldBuildOpeningTable() {
            var table = (TableArtifact) builder.openingTable(List.of(
                    new OpeningStat("A01", "Nimzovich-Larsen Attack", 12, 4, 33.3, 49.2, "white"),
                    new OpeningStat("B20", "Sicilian Defense", 21, 11, 52.4, 68.8, "black")));

            assertThat(table).isNotNull();
            assertThat(table.rows()).hasSize(2);
            // A short row shifts every value left of the gap into the wrong
            // column, and reads as data rather than as an error.
            for (var row : table.rows()) {
                assertThat(row).hasSameSizeAs(table.columns());
            }
            assertThat(table.align()).hasSameSizeAs(table.columns());
            assertThat(table.align().get(0)).isEqualTo(Align.LEFT);
        }

        @Test
        @DisplayName("Should show unmeasured accuracy as a dash, never as zero")
        void shouldNotRenderMissingAccuracyAsZero() {
            // The sentinel confusion that made 61 games report 0% accuracy. A
            // null here means "not measured"; 0 would mean "played terribly".
            var table = (TableArtifact) builder.openingTable(List.of(
                    new OpeningStat("C50", "Italian Game", 5, 2, 40.0, null, "white")));

            assertThat(table.rows().get(0).get(3)).isEqualTo("—");
        }

        @Test
        @DisplayName("Should fall back to the ECO code when an opening has no name")
        void shouldFallBackToEco() {
            var table = (TableArtifact) builder.openingTable(List.of(
                    new OpeningStat("D02", null, 7, 3, 42.9, 61.0, "white")));

            assertThat(table.rows().get(0).get(0)).isEqualTo("D02");
        }

        @Test
        @DisplayName("Should produce nothing for an empty result")
        void shouldReturnNullWhenNoOpenings() {
            assertThat(builder.openingTable(List.of())).isNull();
            assertThat(builder.openingTable(null)).isNull();
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should drop a chart with a non-finite bar")
        void shouldDropNonFiniteBar() {
            // A NaN renders as a bar of undefined length: visibly odd, but the
            // number beside it still reads as a measurement.
            var bad = new ChartArtifact("c", "t", ChartArtifact.ChartId.MISTAKES_BY_MOTIF,
                    "mistakes", List.of(new ChartArtifact.Bar("X", Double.NaN)), null);

            assertThat(ArtifactValidator.validate(bad)).isNull();
        }

        @Test
        @DisplayName("Should drop a chart with a negative bar")
        void shouldDropNegativeBar() {
            var bad = new ChartArtifact("c", "t", ChartArtifact.ChartId.MISTAKES_BY_MOTIF,
                    "mistakes", List.of(new ChartArtifact.Bar("X", -3)), null);

            assertThat(ArtifactValidator.validate(bad)).isNull();
        }

        @Test
        @DisplayName("Should drop a table whose row does not match its columns")
        void shouldDropRaggedTable() {
            var bad = new TableArtifact("t", "t", List.of("A", "B", "C"),
                    List.of(Align.LEFT, Align.RIGHT, Align.RIGHT),
                    List.of(List.of("1", "2")), null);

            assertThat(ArtifactValidator.validate(bad)).isNull();
        }

        @Test
        @DisplayName("Should drop a table whose align does not match its columns")
        void shouldDropMismatchedAlign() {
            var bad = new TableArtifact("t", "t", List.of("A", "B"),
                    List.of(Align.LEFT), List.of(List.of("1", "2")), null);

            assertThat(ArtifactValidator.validate(bad)).isNull();
        }
    }
}
