package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replaying the engine's line and counting what it costs.
 *
 * The number that matters most here is the consequence ply. "You lose a knight"
 * and "you lose a knight four moves from now" call for different advice, and the
 * pipeline has never been able to say which one it is.
 */
@DisplayName("Consequence walk")
class ConsequenceWalkTest {

    private static Board at(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    @Nested
    @DisplayName("mate")
    class Mate {

        /** Scholar's mate: the line is one move long and ends the game. */
        @Test
        void aMatingReplyIsRecordedAsMate() {
            Board afterNf6 = at("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4");

            var consequence = ConsequenceWalk.of(afterNf6, List.of("h5f7"), Side.BLACK);

            assertThat(consequence.mate()).isTrue();
            assertThat(consequence.mateIn()).isEqualTo(1);
            assertThat(consequence.replySan()).startsWith("Qxf7");
            assertThat(consequence.replyGivesCheck()).isTrue();
            assertThat(consequence.consequencePly()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("material")
    class Material {

        /**
         * A knight taken two plies in. The ply is the fact that makes the
         * explanation actionable, and it is not the same as the length of the
         * line.
         */
        @Test
        void recordsWhatFallsAndWhen() {
            // Black to move has just played a losing move; White wins the knight
            // on c3 with the queen, and Black recaptures nothing.
            Board after = at("4k3/8/8/q7/8/2N5/8/4K3 b - - 0 1");

            var consequence = ConsequenceWalk.of(after, List.of("a5c3"), Side.WHITE);

            assertThat(consequence.losses()).hasSize(1);
            assertThat(consequence.losses().get(0).square()).isEqualTo(Square.C3);
            assertThat(consequence.losses().get(0).value()).isEqualTo(3);
            assertThat(consequence.materialSwing()).isEqualTo(3);
            assertThat(consequence.consequencePly()).isEqualTo(1);
        }

        /**
         * A line where nothing is taken has no consequence ply. Reporting a
         * default of 1 would put a number on a fact that is not there.
         */
        @Test
        void aQuietLineHasNoConsequencePly() {
            Board after = at("4k3/8/8/8/8/8/4R3/4K3 b - - 0 1");

            var consequence = ConsequenceWalk.of(after, List.of("e8d8", "e2e3"), Side.WHITE);

            assertThat(consequence.losses()).isEmpty();
            assertThat(consequence.consequencePly()).isZero();
            assertThat(consequence.materialSwing()).isZero();
        }

        /**
         * Knight takes knight, pawn takes back. The blunderer lost a knight and
         * won one back, so the move cost nothing. The first version reported only
         * the loss — on a real game that turned a one-pawn line into "White loses
         * 13 points", and the model repeated it because it was in the evidence.
         */
        @Test
        void aTradeIsNetOfWhatWasWonBack() {
            Board after = at("4k3/8/3p4/4n3/8/5N2/8/4K3 w - - 0 1");

            var consequence = ConsequenceWalk.of(after, List.of("f3e5", "d6e5"), Side.BLACK);

            assertThat(consequence.losses()).hasSize(1);
            assertThat(consequence.gains()).hasSize(1);
            assertThat(consequence.materialSwing()).isZero();
            assertThat(consequence.costsSomething()).isFalse();
        }

        /**
         * The played move's own capture is material won. Black's bishop takes the
         * knight on f3 and the other knight takes back: an even trade. The walk
         * starts after the move, and without this the trade read "Black loses 3
         * points" on a real game.
         */
        @Test
        void aCapturingMoveIsCreditedWithWhatItTook() {
            Board before = at("4k3/8/8/8/6b1/5N2/3N4/4K3 b - - 0 1");
            Board afterBxf3 = at("4k3/8/8/8/8/5b2/3N4/4K3 w - - 0 2");

            var consequence = ConsequenceWalk.of(afterBxf3, List.of("d2f3"), Side.BLACK,
                    before.getPiece(Square.F3), Square.F3, "Bxf3");

            assertThat(consequence.losses()).hasSize(1);
            assertThat(consequence.gains()).extracting(ConsequenceWalk.Loss::ply).containsExactly(0);
            assertThat(consequence.materialSwing()).isZero();
            assertThat(consequence.costsSomething()).isFalse();
        }

        /**
         * A PV runs as deep as the search did. Material that changes hands past
         * the horizon is not a consequence of the move being explained.
         */
        @Test
        void capturesBeyondTheHorizonAreNotCounted() {
            // Eight quiet king moves, then White's rook takes a pawn on ply nine.
            Board after = at("7k/p7/8/8/8/8/8/R3K3 w - - 0 1");
            var line = List.of("e1f1", "h8g8", "f1g1", "g8h8", "g1h1", "h8g8",
                    "h1g1", "g8h8", "a1a7");

            var consequence = ConsequenceWalk.of(after, line, Side.BLACK);

            assertThat(consequence.lineSan()).hasSize(ConsequenceWalk.HORIZON);
            assertThat(consequence.losses()).isEmpty();
        }

        /** An illegal or unparseable line stops rather than throwing. */
        @Test
        void aLineThatDoesNotPlayStopsWhereItBreaks() {
            Board after = at("4k3/8/8/8/8/8/4R3/4K3 b - - 0 1");

            var consequence = ConsequenceWalk.of(after, List.of("e8d8", "h1h8"), Side.WHITE);

            assertThat(consequence.lineSan()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("the rendered block")
    class Block {

        /**
         * The field names are the adapter's interface. It was trained to continue
         * from exactly these, so a rename is not a cosmetic change — it is a
         * quietly worse prompt with nothing reporting a problem.
         */
        @Test
        void carriesTheFieldsTheAdapterWasTrainedOn() {
            String fen = "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3";
            Board afterNf6 = at("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4");
            var consequence = ConsequenceWalk.of(afterNf6, List.of("h5f7"), Side.BLACK);

            String block = EvidenceBlock.render(fen, "Nf6", Side.BLACK, "OPENING",
                    consequence, List.of());

            assertThat(block)
                    .contains("FEN: " + fen)
                    .contains("Player: Black")
                    .contains("Played: Nf6")
                    .contains("Reply: Qxf7")
                    .contains("Outcome: mate in 1")
                    .contains("Phase: opening")
                    .endsWith(EvidenceBlock.INSTRUCTION);
        }

        /**
         * When the walk shows no material lost, the block says so. Leaving the
         * field out invites the model to supply an outcome of its own.
         */
        @Test
        void aTradeIsReportedAsNoMaterialLost() {
            // Black plays ...d6; White takes on e5 and the pawn takes back.
            String fen = "4k3/3p4/8/4n3/8/5N2/8/4K3 b - - 0 1";
            Board after = at("4k3/8/3p4/4n3/8/5N2/8/4K3 w - - 0 2");
            var consequence = ConsequenceWalk.of(after, List.of("f3e5", "d6e5"), Side.BLACK);

            String block = EvidenceBlock.render(fen, "d6", Side.BLACK, "ENDGAME",
                    consequence, List.of());

            assertThat(block)
                    .contains("Outcome: no material lost within " + ConsequenceWalk.HORIZON + " plies")
                    .doesNotContain("Lost in the line")
                    .doesNotContain("Consequence ply");
        }

        /** Verified geometry is stated; there is no label to pass through. */
        @Test
        void namesOnlyTacticsThatWereComputed() {
            String fen = "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3";
            Board afterNf6 = at("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4");
            var consequence = ConsequenceWalk.of(afterNf6, List.of("h5f7"), Side.BLACK);

            String block = EvidenceBlock.render(fen, "Nf6", Side.BLACK, "OPENING",
                    consequence, List.of());

            assertThat(block).doesNotContain("Tagged:");
        }
    }
}
