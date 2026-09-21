package com.praxis.prax.artifact;

import com.github.bhlangonijr.chesslib.Board;
import com.praxis.prax.artifact.ChessPositionArtifact.Arrow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A board is self-evidently wrong to a player who can read one — which is
 * exactly why it must be tested rather than eyeballed. A diagram showing the
 * wrong position is worse than no diagram, because there is nothing in the UI
 * that says so.
 */
@DisplayName("Chess Position Artifact Tests")
class ChessPositionArtifactTest {

    private static final String START =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    /** Black to move, from a real analysed game shape. */
    private static final String BLACK_TO_MOVE =
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2";

    private ChessPositionArtifactBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ChessPositionArtifactBuilder();
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should draw both the played move and the engine's choice")
        void shouldDrawBothMoves() {
            var a = (ChessPositionArtifact) builder.build(
                    "p1", START, "e2e4", "a2a3", null, "Played a3; engine prefers e4.");

            assertThat(a).isNotNull();
            assertThat(a.arrows()).hasSize(2);
            assertThat(a.arrows()).extracting(Arrow::role)
                    .containsExactlyInAnyOrder(Arrow.Role.PLAYED, Arrow.Role.BEST);
            assertThat(a.arrows()).extracting(Arrow::from).contains("e2", "a2");
        }

        @Test
        @DisplayName("Should highlight the squares both moves touch, without duplicates")
        void shouldHighlightWithoutDuplicates() {
            // Both moves end on the same square — a doubled highlight renders twice.
            var a = (ChessPositionArtifact) builder.build(
                    "p1", START, "b1c3", "d2d3", null, null);

            assertThat(a).isNotNull();
            assertThat(a.highlights()).doesNotHaveDuplicates();
            assertThat(a.highlights()).contains("b1", "c3", "d2", "d3");
        }

        @Test
        @DisplayName("Should orient the board toward whoever is to move")
        void shouldOrientTowardSideToMove() {
            // The player who is about to blunder is the one who needs to read it.
            assertThat(ChessPositionArtifactBuilder.orientationOf(START)).isEqualTo("white");
            assertThat(ChessPositionArtifactBuilder.orientationOf(BLACK_TO_MOVE)).isEqualTo("black");
        }

        @Test
        @DisplayName("Should build a board with no played move")
        void shouldBuildWithoutPlayedMove() {
            var a = (ChessPositionArtifact) builder.build("p1", START, "e2e4", null, null, null);

            assertThat(a).isNotNull();
            assertThat(a.arrows()).hasSize(1);
            assertThat(a.arrows().get(0).role()).isEqualTo(Arrow.Role.BEST);
        }

        @Test
        @DisplayName("Should include attacked squares when supplied")
        void shouldIncludeAttackedSquares() {
            var a = (ChessPositionArtifact) builder.build(
                    "p1", START, "e2e4", null, List.of("c2", "f7"), null);

            assertThat(a).isNotNull();
            assertThat(a.highlights()).contains("c2", "f7");
        }

        @Test
        @DisplayName("Should return null rather than a board with no position")
        void shouldReturnNullWithoutFen() {
            assertThat(builder.build("p1", null, "e2e4", null, null, null)).isNull();
            assertThat(builder.build("p1", "  ", "e2e4", null, null, null)).isNull();
        }

        @Test
        @DisplayName("Should ignore malformed move strings instead of drawing nonsense")
        void shouldIgnoreMalformedMoves() {
            var a = (ChessPositionArtifact) builder.build(
                    "p1", START, "zz99", "not-a-move", null, null);

            assertThat(a).isNotNull();
            assertThat(a.arrows()).as("no arrow may be drawn from an unparseable move").isEmpty();
        }
    }

    @Nested
    @DisplayName("Replay Equivalence Tests")
    class ReplayEquivalenceTests {

        @Test
        @DisplayName("Should carry a FEN that reaches the same position on independent replay")
        void fenMatchesIndependentReplay() {
            // THE test for this feature. An off-by-one in ply indexing is the most
            // likely real bug, and it produces a board that is confidently,
            // silently wrong. Replaying the moves separately and comparing is the
            // only check that catches it.
            Board replayed = new Board();
            replayed.loadFromFen(START);
            replayed.doMove(new com.github.bhlangonijr.chesslib.move.Move(
                    com.github.bhlangonijr.chesslib.Square.E2,
                    com.github.bhlangonijr.chesslib.Square.E4));
            replayed.doMove(new com.github.bhlangonijr.chesslib.move.Move(
                    com.github.bhlangonijr.chesslib.Square.E7,
                    com.github.bhlangonijr.chesslib.Square.E5));

            var a = (ChessPositionArtifact) builder.build(
                    "p1", replayed.getFen(), "g1f3", null, null, null);

            assertThat(a).isNotNull();
            Board fromArtifact = new Board();
            fromArtifact.loadFromFen(a.fen());
            assertThat(fromArtifact.getFen()).isEqualTo(replayed.getFen());
            assertThat(a.orientation()).isEqualTo("white");
        }
    }

    @Nested
    @DisplayName("Validator Tests")
    class ValidatorTests {

        private static ChessPositionArtifact artifact(String fen, String orientation,
                                                      List<String> highlights, List<Arrow> arrows) {
            return new ChessPositionArtifact("p1", "t", fen, orientation, highlights, arrows, null);
        }

        @Test
        @DisplayName("Should accept a well-formed position")
        void shouldAcceptValid() {
            var a = artifact(START, "white", List.of("e2"),
                    List.of(new Arrow("e2", "e4", Arrow.Role.BEST)));

            assertThat(ArtifactValidator.validate(a)).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "not a fen",
                "rnbqkbnr/pppppppp/8/8/8",
                "xxxxxxxx/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        })
        @DisplayName("Should drop a position whose FEN does not parse")
        void shouldDropBadFen(String fen) {
            // Not "looks like a FEN" — actually loads, in the same library that
            // will be asked to render it.
            assertThat(ArtifactValidator.validate(artifact(fen, "white", null, null))).isNull();
        }

        @Test
        @DisplayName("Should drop an unknown orientation")
        void shouldDropBadOrientation() {
            assertThat(ArtifactValidator.validate(artifact(START, "sideways", null, null))).isNull();
            assertThat(ArtifactValidator.validate(artifact(START, null, null, null))).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"j9", "e", "e9", "", "a0", "e2e4"})
        @DisplayName("Should drop an off-board highlight")
        void shouldDropOffBoardHighlight(String square) {
            assertThat(ArtifactValidator.validate(
                    artifact(START, "white", List.of(square), null))).isNull();
        }

        @Test
        @DisplayName("Should drop an arrow with an off-board endpoint")
        void shouldDropOffBoardArrow() {
            assertThat(ArtifactValidator.validate(artifact(START, "white", null,
                    List.of(new Arrow("e2", "z9", Arrow.Role.BEST))))).isNull();
            assertThat(ArtifactValidator.validate(artifact(START, "white", null,
                    List.of(new Arrow("q1", "e4", Arrow.Role.BEST))))).isNull();
        }

        @Test
        @DisplayName("Should drop an arrow that goes nowhere")
        void shouldDropZeroLengthArrow() {
            assertThat(ArtifactValidator.validate(artifact(START, "white", null,
                    List.of(new Arrow("e2", "e2", Arrow.Role.BEST))))).isNull();
        }

        @Test
        @DisplayName("Should recognise every real square and nothing else")
        void shouldRecogniseSquares() {
            assertThat(ArtifactValidator.isSquare("a1")).isTrue();
            assertThat(ArtifactValidator.isSquare("h8")).isTrue();
            assertThat(ArtifactValidator.isSquare("E4")).isTrue();
            assertThat(ArtifactValidator.isSquare("i1")).isFalse();
            assertThat(ArtifactValidator.isSquare(null)).isFalse();
        }
    }
}
