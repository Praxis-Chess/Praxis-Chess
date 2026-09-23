package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEE against exchanges whose answer is known independently.
 *
 * The first two positions are the Chess Programming Wiki's standard SEE cases,
 * used because their published values were not derived from this implementation.
 * The rest are hand-built so each one isolates a single behaviour, and each says
 * what it would mean if it broke.
 */
@DisplayName("Static exchange evaluation")
class SeeTest {

    private static Board at(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    @Nested
    @DisplayName("published positions")
    class Published {

        /** CPW SEE test 1: Rxe5 wins a pawn outright — nothing defends e5. */
        @Test
        void rookTakesUndefendedPawn() {
            Board b = at("1k1r4/1pp4p/p7/4p3/8/P5P1/1PP4P/2K1R3 w - - 0 1");
            assertThat(See.capture(b, Square.E1, Square.E5)).isEqualTo(1);
        }

        /**
         * CPW SEE test 2: Nxe5 loses two pawns' worth. The knight wins a pawn and
         * is recaptured, and the pile-up behind it does not rescue the sequence.
         */
        @Test
        void knightTakesDefendedPawn() {
            Board b = at("1k1r3q/1ppn3p/p4b2/4p3/8/P2N2P1/1PP1R1BP/2K1Q3 w - - 0 1");
            assertThat(See.capture(b, Square.D3, Square.E5)).isEqualTo(-2);
        }
    }

    @Nested
    @DisplayName("counting attackers is not enough")
    class BeyondCounting {

        /**
         * One attacker, one defender, equal pieces: safe. A rule that counted
         * attackers would call this hanging and every explanation built on it
         * would be wrong in the most ordinary position on the board.
         */
        @Test
        void defendedByAnEqualPieceIsSafe() {
            Board b = at("4k3/8/3n4/8/4N3/8/4R3/4K3 w - - 0 1");
            assertThat(See.onSquare(b, Square.E4)).isZero();
        }

        /** Remove the defender and the same knight is simply lost. */
        @Test
        void undefendedPieceIsLost() {
            Board b = at("4k3/8/3n4/8/4N3/8/8/4K3 w - - 0 1");
            assertThat(See.onSquare(b, Square.E4)).isEqualTo(-3);
        }

        /**
         * Defended, but only by something dearer than the attacker: a rook
         * guarded by a queen against a pawn is still lost material.
         */
        @Test
        void aCheaperAttackerWinsThroughADearerDefender() {
            // Pawn d5 attacks the rook on e4; the queen on e1 guards it down the
            // file. dxe4 Qxe4 loses a rook for a pawn.
            Board b = at("4k3/8/8/3p4/4R3/8/8/4QK2 w - - 0 1");
            assertThat(See.onSquare(b, Square.E4)).isEqualTo(-4);
        }
    }

    @Nested
    @DisplayName("x-rays")
    class Xrays {

        /**
         * Doubled rooks behind each other, against a queen and rook on the same
         * file. Every piece joins as the one in front of it is removed, and the
         * whole sequence comes out level — so the pawn is not actually winnable.
         *
         * This is the case that only works if attackers are recomputed against a
         * shrinking occupancy. With a fixed attacker set the back rook never
         * appears and the answer comes out a pawn too high.
         */
        @Test
        void piecesBehindPiecesJoinTheExchange() {
            Board b = at("4rk2/4q3/8/4p3/8/8/4R3/4R1K1 w - - 0 1");
            assertThat(See.capture(b, Square.E2, Square.E5)).isZero();
        }
    }

    @Nested
    @DisplayName("the king")
    class King {

        /**
         * A king cannot be the piece that recaptures while the square is still
         * attacked — that capture is illegal. Counting it would make defended
         * pieces look safe when only the king "defends" them.
         */
        @Test
        void kingCannotRecaptureIntoAnAttack() {
            // Black pawn d5 is guarded only by the black king; White attacks d5
            // with a pawn and a rook, so Kxd5 would be into check from the rook.
            Board b = at("8/8/8/2kp4/2P5/8/3R4/4K3 w - - 0 1");
            assertThat(See.onSquare(b, Square.D5)).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("the sign convention the graph depends on")
    class Signs {

        /** onSquare is from the owner's point of view and is never positive. */
        @Test
        void onSquareIsTheOwnersLoss() {
            Board b = at("4k3/8/3n4/8/4N3/8/8/4K3 w - - 0 1");
            assertThat(See.onSquare(b, Square.E4)).isNegative();
            assertThat(See.exchange(b, Square.E4, Side.BLACK)).isPositive();
        }

        /** Empty squares and unattacked pieces are both plain zero, not null. */
        @Test
        void quietSquaresAreZero() {
            Board b = at("4k3/8/8/8/4N3/8/8/4K3 w - - 0 1");
            assertThat(See.onSquare(b, Square.E4)).isZero();
            assertThat(See.onSquare(b, Square.A1)).isZero();
        }
    }
}
