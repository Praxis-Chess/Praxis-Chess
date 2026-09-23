package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Motifs read off the board, on positions small enough to check by eye.
 *
 * Every position is built piece by piece rather than lifted from a game, so a
 * failure points at one rule instead of a tangle of them. The negative cases
 * carry as much weight as the positive ones: the failure this class exists to
 * prevent is a motif being claimed where none exists.
 */
@DisplayName("Tactic geometry")
class TacticGeometryTest {

    private static Board at(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    private static List<TacticGeometry.Tactic> of(Board board, Move move, TacticGeometry.Kind kind) {
        return TacticGeometry.after(board, move).stream().filter(t -> t.kind() == kind).toList();
    }

    @Nested
    @DisplayName("forks")
    class Forks {

        /** Knight to c6 hits the king on d8 and the rook on e7 at once. */
        @Test
        void knightHittingKingAndRookIsAFork() {
            Board b = at("3k4/4r3/8/4N3/8/8/8/6K1 w - - 0 1");
            var forks = of(b, new Move(Square.E5, Square.C6), TacticGeometry.Kind.FORK);

            assertThat(forks).hasSize(1);
            assertThat(forks.get(0).targets()).containsExactlyInAnyOrder(Square.D8, Square.E7);
        }

        /**
         * A queen attacking two pawns, each guarded by a rook behind it, forks
         * nothing: neither pawn can be taken. A rule that counted attacked pieces
         * would call this a fork, and every sentence built on it would be wrong.
         */
        @Test
        void attackingTwoGuardedPawnsIsNotAFork() {
            Board b = at("1r1r3k/1p1p4/8/8/8/8/8/3QK3 w - - 0 1");
            assertThat(of(b, new Move(Square.D1, Square.D5), TacticGeometry.Kind.FORK)).isEmpty();
        }

        /** Nothing attacked at all is, obviously, nothing. */
        @Test
        void aQuietMoveForksNothing() {
            Board b = at("r3k3/8/8/8/8/8/8/4K1N1 w - - 0 1");
            assertThat(of(b, new Move(Square.G1, Square.F3), TacticGeometry.Kind.FORK)).isEmpty();
        }
    }

    @Nested
    @DisplayName("pins and skewers are one geometry, two orderings")
    class PinsAndSkewers {

        /** Knight in front, king behind: a pin, and an absolute one. */
        @Test
        void bishopPinsKnightToKing() {
            Board b = at("8/8/8/4k3/8/2n5/1B6/4K3 w - - 0 1");
            var pins = TacticGeometry.pinsAndSkewers(b, Side.WHITE).stream()
                    .filter(t -> t.kind() == TacticGeometry.Kind.PIN).toList();

            assertThat(pins).hasSize(1);
            assertThat(pins.get(0).targets()).containsExactly(Square.C3, Square.E5);
        }

        /**
         * The same bishop, the same diagonal — but with the king in front and the
         * knight behind it. That is a skewer, and the only thing that changed is
         * which piece is worth more.
         */
        @Test
        void kingInFrontOfTheKnightIsASkewer() {
            Board b = at("8/8/8/8/3n4/2k5/1B6/4K3 b - - 0 1");
            var skewers = TacticGeometry.pinsAndSkewers(b, Side.WHITE).stream()
                    .filter(t -> t.kind() == TacticGeometry.Kind.SKEWER).toList();

            assertThat(skewers).hasSize(1);
            assertThat(skewers.get(0).targets()).containsExactly(Square.C3, Square.D4);
        }

        /**
         * A pin that was already standing is not something the move did. On the
         * first real game this ran against, a knight reply was tagged "pin"
         * because of a line that had existed for several moves.
         */
        @Test
        void aPinThatAlreadyExistedIsNotCreditedToTheMove() {
            Board b = at("8/8/8/4k3/8/2n5/1B6/6K1 w - - 0 1");

            assertThat(TacticGeometry.pinsAndSkewers(b, Side.WHITE)).hasSize(1);
            assertThat(of(b, new Move(Square.G1, Square.H1), TacticGeometry.Kind.PIN)).isEmpty();
        }

        /**
         * Rook, knight, bishop on one rank. The two black pieces are worth the
         * same, so neither is pinned nor skewered — losing either for the other
         * costs nothing, and naming a motif here would be inventing one.
         */
        @Test
        void equalValuesOnALineAreNeither() {
            Board b = at("4k3/8/8/8/8/8/8/R2n1b1K w - - 0 1");
            assertThat(TacticGeometry.pinsAndSkewers(b, Side.WHITE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("discovered attacks")
    class Discovered {

        /**
         * The knight steps aside and the rook's file opens onto the queen. The
         * rook never moved, so nothing about the move itself names this — only
         * the difference between the two positions does.
         */
        @Test
        void movingAPieceUncoversTheLineBehindIt() {
            Board b = at("3q3k/8/8/8/3N4/8/8/3R2K1 w - - 0 1");
            var found = of(b, new Move(Square.D4, Square.F5), TacticGeometry.Kind.DISCOVERED_ATTACK);

            assertThat(found).hasSize(1);
            assertThat(found.get(0).by()).isEqualTo(Square.D1);
            assertThat(found.get(0).targets()).containsExactly(Square.D8);
        }

        /** A move that opens nothing reports nothing. */
        @Test
        void anOrdinaryMoveDiscoversNothing() {
            Board b = at("3q3k/8/8/8/8/8/8/3R2K1 w - - 0 1");
            assertThat(of(b, new Move(Square.G1, Square.G2),
                    TacticGeometry.Kind.DISCOVERED_ATTACK)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the claim the baseline could not check")
    class TheBaselineCase {

        /**
         * `Bg5` against a knight on f6 backed by the queen. The shipped system
         * called this "a fork on f6" — it is not a fork by any reading. The
         * geometry says what it actually is, which is a pin, and that difference
         * is the whole point of computing motifs instead of guessing them.
         */
        @Test
        void bishopHittingOneKnightIsAPinAndNotAFork() {
            Board b = at("r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 0 1");
            Move bg5 = new Move(Square.C1, Square.G5);

            assertThat(of(b, bg5, TacticGeometry.Kind.FORK)).isEmpty();

            var pins = of(b, bg5, TacticGeometry.Kind.PIN);
            assertThat(pins).hasSize(1);
            assertThat(pins.get(0).targets()).containsExactly(Square.F6, Square.D8);
        }
    }
}
