package com.praxis.play.patterns;

import com.praxis.domain.Game;
import com.praxis.play.domain.PracticeGame;
import com.praxis.play.patterns.detectors.CastlingDelayDetector;
import com.praxis.play.patterns.detectors.DevelopmentDelayDetector;
import com.praxis.service.analysis.ParsedGame;
import com.praxis.service.analysis.PgnParserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PGN-derived detectors, against real movetext and no engine.
 *
 * These fixtures are legal games — they are replayed by chesslib inside
 * PgnParserService, so an illegal move would fail to parse rather than produce a
 * wrong answer, and the tests would go green for the wrong reason.
 */
class PgnDetectorTest {

    private static final String PLAYER = "latt1ce";
    private final PgnParserService parser = new PgnParserService();

    /** Castles on move 12 — comfortably past the move-10 mark. */
    private static final String CASTLES_LATE = """
            1. e4 e5 2. Nf3 Nf6 3. d3 d6 4. Be2 Be7 5. h3 h6 6. c3 c6
            7. b3 b6 8. g3 g6 9. Bd2 Bd7 10. Na3 Na6 11. Qc2 Qc7 12. O-O O-O""";

    /** Same line, stopped before either side castles. Exactly 20 ply. */
    private static final String NEVER_CASTLES = """
            1. e4 e5 2. Nf3 Nf6 3. d3 d6 4. Be2 Be7 5. h3 h6 6. c3 c6
            7. b3 b6 8. g3 g6 9. Bd2 Bd7 10. Na3 Na6""";

    /** Castles on move 4. */
    private static final String CASTLES_EARLY = """
            1. e4 e5 2. Nf3 Nf6 3. Be2 Be7 4. O-O O-O 5. d3 d6 6. h3 h6
            7. c3 c6 8. b3 b6 9. g3 g6 10. Kh1 Kh8""";

    /** White pushes pawns for ten moves; all four minors are still at home. */
    private static final String NO_DEVELOPMENT = """
            1. e4 e5 2. d3 Nf6 3. c3 Nc6 4. b3 Bc5 5. a3 d6 6. h3 Be6
            7. g3 Qd7 8. f3 O-O 9. a4 Rae8 10. b4 Bb6""";

    /** Too short to have reached the question at all. */
    private static final String VERY_SHORT = "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6";

    // --- fixture plumbing ---

    private String pgn(String movetext, boolean playerIsWhite) {
        return "[Event \"Praxis Practice\"]\n"
             + "[White \"" + (playerIsWhite ? PLAYER : "Praxis Engine") + "\"]\n"
             + "[Black \"" + (playerIsWhite ? "Praxis Engine" : PLAYER) + "\"]\n"
             + "[Result \"*\"]\n\n"
             + movetext.replace("\n", " ") + " *\n";
    }

    private AnalysedGame game(String movetext, boolean playerIsWhite) {
        UUID id = UUID.randomUUID();
        String raw = pgn(movetext, playerIsWhite);
        ParsedGame parsed = parser.parse(id.toString(), raw, PLAYER);

        Game archived = Game.builder()
                .id(id)
                .username(PLAYER)
                .playerColor(playerIsWhite ? "white" : "black")
                .rawPgn(raw)
                .playedAt(OffsetDateTime.now())
                .build();

        return new AnalysedGame(PracticeGame.builder().username(PLAYER).build(),
                archived, parsed, List.of());
    }

    /** N copies of one fixture — the detectors need a window, not a single game. */
    private List<AnalysedGame> window(String movetext, boolean playerIsWhite, int n) {
        return IntStream.range(0, n).mapToObj(i -> game(movetext, playerIsWhite)).toList();
    }

    @Nested
    @DisplayName("Castling delay")
    class Castling {

        private final CastlingDelayDetector detector = new CastlingDelayDetector();

        @Test
        @DisplayName("fires when the player castles after move 10")
        void late() {
            Optional<PracticePattern> pattern = detector.detect(window(CASTLES_LATE, true, 6));

            assertThat(pattern).isPresent();
            assertThat(pattern.get().gamesAffected()).isEqualTo(6);
            assertThat(pattern.get().finding()).contains("6 of 6 games");
        }

        @Test
        @DisplayName("counts a game with no castling at all, and says so")
        void never() {
            Optional<PracticePattern> pattern = detector.detect(window(NEVER_CASTLES, true, 5));

            assertThat(pattern).isPresent();
            assertThat(pattern.get().finding()).contains("never castled in 5");
            // No ply to cite — the finding is about the game, not a move.
            assertThat(pattern.get().evidence()).allSatisfy(o -> assertThat(o.ply()).isNull());
        }

        @Test
        @DisplayName("stays silent when the player castles early")
        void early() {
            assertThat(detector.detect(window(CASTLES_EARLY, true, 6))).isEmpty();
        }

        /**
         * A four-move game did not fail to castle; it never got there. Counting
         * it would make every quick loss look like a castling problem.
         */
        @Test
        @DisplayName("drops games too short to judge rather than counting them")
        void shortGamesLeaveTheDenominator() {
            List<AnalysedGame> mixed = new java.util.ArrayList<>(window(CASTLES_LATE, true, 5));
            mixed.addAll(window(VERY_SHORT, true, 3));

            Optional<PracticePattern> pattern = detector.detect(mixed);

            assertThat(pattern).isPresent();
            assertThat(pattern.get().gamesConsidered())
                    .as("the three short games should not be in the denominator")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("says nothing below the sample floor")
        void belowFloor() {
            assertThat(detector.detect(window(CASTLES_LATE, true, 4))).isEmpty();
        }

        @Test
        @DisplayName("hedges near the floor and states it plainly with more games")
        void strengthBands() {
            assertThat(detector.detect(window(CASTLES_LATE, true, 6)).orElseThrow().strength())
                    .isEqualTo(Strength.EMERGING);
            assertThat(detector.detect(window(CASTLES_LATE, true, 12)).orElseThrow().strength())
                    .isEqualTo(Strength.ESTABLISHED);
        }

        @Test
        @DisplayName("works from Black's side too")
        void asBlack() {
            assertThat(detector.detect(window(CASTLES_LATE, false, 6))).isPresent();
            assertThat(detector.detect(window(CASTLES_EARLY, false, 6))).isEmpty();
        }

        @Test
        @DisplayName("carries no drill link — no position's best move is 'castle'")
        void noDrill() {
            assertThat(detector.detect(window(CASTLES_LATE, true, 6)).orElseThrow().drillMotif())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Development delay")
    class Development {

        private final DevelopmentDelayDetector detector = new DevelopmentDelayDetector();

        @Test
        @DisplayName("fires when minors are still at home on move 10")
        void undeveloped() {
            Optional<PracticePattern> pattern = detector.detect(window(NO_DEVELOPMENT, true, 6));

            assertThat(pattern).isPresent();
            assertThat(pattern.get().finding()).contains("move 10");
            assertThat(pattern.get().gamesAffected()).isEqualTo(6);
        }

        @Test
        @DisplayName("stays silent when the pieces came out")
        void developed() {
            assertThat(detector.detect(window(CASTLES_LATE, true, 6))).isEmpty();
        }

        /** Counts squares, not moves: a knight that went out and came back is home. */
        @Test
        @DisplayName("reads the board, so a piece that returned home still counts")
        void readsBoardNotMoveCount() {
            // Nf3 then back to g1: the knight has moved twice and is undeveloped.
            String outAndBack = """
                    1. e4 e5 2. Nf3 Nf6 3. Ng1 Ng8 4. d3 d6 5. h3 h6
                    6. c3 c6 7. b3 b6 8. g3 g6 9. a3 a6 10. Qc2 Qc7""";

            assertThat(DevelopmentDelayDetector.undevelopedMinors(
                    parser.parse("x", pgn(outAndBack, true), PLAYER)
                          .moves().get(19).fenAfter(), true))
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("every detector carries its rationale")
        void rationale() {
            assertThat(detector.why()).isNotBlank();
            assertThat(detector.whatToDo()).isNotBlank();
        }
    }
}
