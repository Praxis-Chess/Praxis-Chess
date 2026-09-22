package com.praxis.prax.intelligence;

import com.praxis.domain.Game;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.service.EcoTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telling an unmatched FILTER apart from an empty HISTORY.
 *
 * From a real run — "which is one of my best plays?" against a library of 104
 * analysed games, every one carrying a genuine accuracy:
 *
 * > "no opening has been played enough times (minimum 3 games) to show a clear
 * > performance trend"
 *
 * The player had 27 games of the Philidor. What actually happened is that the
 * model called get_opening_performance with {@code opening: "e4"} — a MOVE, not
 * an opening name or an ECO code — the filter matched nothing, and the empty
 * list was narrated as a thin history. Both situations arrived as {@code []},
 * so the model had no way to tell them apart.
 */
@DisplayName("Opening filter Tests")
class OpeningFilterTest {

    private static final String USER = "testplayer";

    private GameRepository games;
    private ChessIntelligence intel;

    /** The real shape of the library: an ECO on every game, a name on almost none. */
    private static Game game(String eco, String name, String result) {
        return Game.builder()
                .username(USER)
                .playerColor("white")
                .result(result)
                .rawPgn("1. e4")
                .openingEco(eco)
                .openingName(name)
                .accuracy(70.0)
                .analysisStatus(AnalysisStatus.ANALYZED)
                .build();
    }

    @BeforeEach
    void setUp() {
        games = Mockito.mock(GameRepository.class);
        MoveErrorRepository errors = Mockito.mock(MoveErrorRepository.class);

        // A stand-in for eco.properties, holding only what these cases need.
        EcoTable eco = Mockito.mock(EcoTable.class);
        Mockito.when(eco.lookup("C41")).thenReturn("Philidor Defense");
        Mockito.when(eco.lookup("B21")).thenReturn("Sicilian Defense, Grand Prix Attack");
        Mockito.when(eco.lookup("C21")).thenReturn("Center Game");

        List<Game> library = new ArrayList<>();
        for (int i = 0; i < 27; i++) library.add(game("C41", null, i < 14 ? "WIN" : "LOSS"));
        for (int i = 0; i < 5; i++)  library.add(game("B21", null, "LOSS"));
        library.add(game("C21", null, "WIN"));   // only 1 — below any sane minimum

        Mockito.when(games.findByUsernameOrderByPlayedAtDesc(USER)).thenReturn(library);

        intel = new ChessIntelligence(games, errors, eco);
    }

    @Nested
    @DisplayName("A blank opening_name does not hide a game")
    class BlankNames {

        @Test
        @DisplayName("openingLabel resolves the name from the ECO table")
        void shouldGroupByResolvedName() {
            // Every one of these games has openingName == null. If the label came
            // from the column alone, the whole library would vanish here — which
            // is the failure openingLabel() was written to prevent.
            var stats = intel.openingPerformance(USER, null, null, 3);

            assertThat(stats).extracting(ChessIntelligence.OpeningStat::name)
                    .contains("Philidor Defense", "Sicilian Defense, Grand Prix Attack");
        }

        @Test
        @DisplayName("a name filter matches through the ECO table")
        void shouldMatchOnResolvedName() {
            // "Sicilian" appears in no opening_name column in this library. It
            // still has to match, via the resolved label.
            assertThat(intel.gamesMatchingOpening(USER, "Sicilian", null)).isEqualTo(5);
            assertThat(intel.gamesMatchingOpening(USER, "Philidor", null)).isEqualTo(27);
        }

        @Test
        @DisplayName("an ECO code matches directly")
        void shouldMatchOnEco() {
            assertThat(intel.gamesMatchingOpening(USER, "C41", null)).isEqualTo(27);
            assertThat(intel.gamesMatchingOpening(USER, "c41", null)).isEqualTo(27);
        }
    }

    @Nested
    @DisplayName("An unmatched filter is distinguishable from a thin history")
    class UnmatchedFilter {

        @Test
        @DisplayName("a move is not an opening, and matches nothing")
        void shouldMatchNothingForAMove() {
            // The exact call from the failing run.
            assertThat(intel.gamesMatchingOpening(USER, "e4", null)).isZero();
            assertThat(intel.openingPerformance(USER, "e4", null, 3)).isEmpty();
        }

        @Test
        @DisplayName("a real opening below the minimum matches, but reports nothing")
        void shouldMatchYetFallShortOfTheMinimum() {
            // The other way to get an empty list — and the ONLY one that
            // justifies saying "not played enough times". One game of the Center
            // Game is a real count; three is the bar it does not clear.
            assertThat(intel.gamesMatchingOpening(USER, "Center Game", null)).isEqualTo(1);
            assertThat(intel.openingPerformance(USER, "Center Game", null, 3)).isEmpty();
        }

        @Test
        @DisplayName("the two empty results are told apart by the match count")
        void shouldSeparateTheTwoEmptyCases() {
            // This is the whole point: openingPerformance returns [] for both,
            // and gamesMatchingOpening is what separates them.
            assertThat(intel.openingPerformance(USER, "e4", null, 3)).isEmpty();
            assertThat(intel.openingPerformance(USER, "Center Game", null, 3)).isEmpty();

            assertThat(intel.gamesMatchingOpening(USER, "e4", null))
                    .as("a filter that matches nothing")
                    .isZero();
            assertThat(intel.gamesMatchingOpening(USER, "Center Game", null))
                    .as("a filter that matches, but too little to report")
                    .isPositive();
        }

        @Test
        @DisplayName("the player's real openings can be named back to them")
        void shouldListAvailableOpenings() {
            // So a dead end becomes an answer: "no match for 'e4' — you have
            // Philidor Defense (27), ...".
            var available = intel.availableOpenings(USER, 8);

            assertThat(available).isNotEmpty();
            assertThat(available.get(0))
                    .as("commonest first")
                    .isEqualTo("Philidor Defense (27)");
            assertThat(available).anyMatch(s -> s.startsWith("Sicilian Defense"));
        }

        @Test
        @DisplayName("no filter covers everything")
        void shouldCountEverythingWithoutAFilter() {
            assertThat(intel.gamesMatchingOpening(USER, null, null)).isEqualTo(33);
        }
    }

    @Nested
    @DisplayName("The minimum still applies")
    class Minimum {

        @Test
        @DisplayName("an opening at the minimum is reported")
        void shouldIncludeAtTheBoundary() {
            assertThat(intel.openingPerformance(USER, null, null, 5))
                    .extracting(ChessIntelligence.OpeningStat::name)
                    .contains("Sicilian Defense, Grand Prix Attack");
        }

        @Test
        @DisplayName("an opening below the minimum is not")
        void shouldExcludeBelowTheBoundary() {
            assertThat(intel.openingPerformance(USER, null, null, 6))
                    .extracting(ChessIntelligence.OpeningStat::name)
                    .doesNotContain("Sicilian Defense, Grand Prix Attack");
        }
    }
}
