package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a move changed — the sentence a club player actually needs.
 *
 * "Your move was bad" is a verdict. "Your bishop was the only thing defending
 * that knight" is a cause, and it exists only as a difference between two
 * positions. These tests pin down that the difference is computed, not guessed.
 */
@DisplayName("Position diff")
class PositionDiffTest {

    private static Board at(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    @Nested
    @DisplayName("defenders")
    class Defenders {

        /**
         * The bishop on d2 is the knight on c3's only defender. Moving it away
         * leaves the knight to be taken — and the diff has to say so about a
         * piece that did not move and is nowhere near the move's squares.
         */
        @Test
        void movingTheOnlyDefenderLeavesThePieceLoose() {
            Board b = at("4k3/8/8/q7/8/2N5/3B4/4K3 w - - 0 1");
            var delta = PositionDiff.of(b, new Move(Square.D2, Square.H6));

            var knight = delta.pieces().stream()
                    .filter(p -> p.square() == Square.C3).findFirst().orElseThrow();
            assertThat(knight.defendersBefore()).containsExactly(Square.D2);
            assertThat(knight.defendersAfter()).isEmpty();
            assertThat(knight.defendersLost()).containsExactly(Square.D2);
            assertThat(knight.nowLoose()).isTrue();
            assertThat(delta.nowLoose()).extracting(PositionDiff.PieceChange::square)
                    .containsExactly(Square.C3);
        }

        /**
         * The same knight, but a rook also guards it. Losing one of two defenders
         * changes the count and changes nothing about its safety — reporting it
         * as newly hanging would be the single most misleading thing this could
         * get wrong.
         */
        @Test
        void losingOneOfTwoDefendersIsNotHanging() {
            Board b = at("4k3/8/8/q7/8/2N5/3B4/2R1K3 w - - 0 1");
            var delta = PositionDiff.of(b, new Move(Square.D2, Square.H6));

            var knight = delta.pieces().stream()
                    .filter(p -> p.square() == Square.C3).findFirst().orElseThrow();
            assertThat(knight.defendersLost()).containsExactly(Square.D2);
            assertThat(knight.nowLoose()).isFalse();
            assertThat(delta.nowLoose()).isEmpty();
        }
    }

    @Nested
    @DisplayName("lines")
    class Lines {

        /** Stepping out of the rook's way opens the file onto the queen. */
        @Test
        void vacatingASquareOpensTheLineBehindIt() {
            Board b = at("3q3k/8/8/8/3N4/8/8/3R2K1 w - - 0 1");
            var delta = PositionDiff.of(b, new Move(Square.D4, Square.F5));

            assertThat(delta.lines())
                    .anySatisfy(l -> {
                        assertThat(l.opened()).isTrue();
                        assertThat(l.slider()).isEqualTo(Square.D1);
                        assertThat(l.target()).isEqualTo(Square.D8);
                    });
        }

        /** Stepping into it closes the same line. */
        @Test
        void landingOnASquareBlocksTheLineThroughIt() {
            Board b = at("3q3k/8/8/8/8/8/4N3/3R2K1 w - - 0 1");
            var delta = PositionDiff.of(b, new Move(Square.E2, Square.D4));

            assertThat(delta.lines())
                    .anySatisfy(l -> {
                        assertThat(l.opened()).isFalse();
                        assertThat(l.slider()).isEqualTo(Square.D1);
                        assertThat(l.target()).isEqualTo(Square.D8);
                    });
        }
    }

    @Nested
    @DisplayName("the moved piece")
    class MovedPiece {

        /**
         * The mover is tracked as one piece across the move, not as a piece
         * disappearing from one square and a stranger appearing on another.
         */
        @Test
        void theMoverIsFollowedToItsNewSquare() {
            Board b = at("4k3/8/3n4/8/8/8/4N3/4K3 w - - 0 1");
            var delta = PositionDiff.of(b, new Move(Square.E2, Square.E4));

            var mover = delta.pieces().get(0);
            assertThat(mover.square()).isEqualTo(Square.E4);
            assertThat(delta.from()).isEqualTo(Square.E2);
            assertThat(delta.to()).isEqualTo(Square.E4);
            // e4 is attacked by the knight on d6 and defended by nothing.
            assertThat(mover.seeBefore()).isZero();
            assertThat(mover.seeAfter()).isEqualTo(-3);
            assertThat(mover.nowLoose()).isTrue();
        }

        @Test
        void newAttacksAreOnlyTheOnesTheMoveCreated() {
            Board b = at("3k4/4r3/8/4N3/8/8/8/6K1 w - - 0 1");
            var delta = PositionDiff.of(b, new Move(Square.E5, Square.C6));

            assertThat(delta.newAttacks()).containsExactly(Square.E7);
            assertThat(delta.givesCheck()).isTrue();
        }

        @Test
        void capturesAreRecorded() {
            Board b = at("4k3/8/8/4p3/8/8/4R3/4K3 w - - 0 1");
            var delta = PositionDiff.of(b, new Move(Square.E2, Square.E5));

            assertThat(delta.captured()).isNotNull();
            assertThat(delta.moved()).isNotNull();
        }
    }
}
