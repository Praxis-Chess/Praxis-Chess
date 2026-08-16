package com.praxis.prax.evidence;

import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fact layer is what stops Prax inventing chess, so its failures are silent
 * by nature — a wrong statement still reads like a right one. Every case here
 * is one that actually shipped wrong at some point.
 *
 * Positions are real, taken from the player's own games and verified by hand.
 */
@DisplayName("PositionEvidenceBuilder Tests")
class PositionEvidenceBuilderTest {

    /**
     * Black to move, up a rook and knight. 28...Rc7 unguards e8 and loses to
     * 29.Re8+ Rxe8 30.dxe8=Q#. The engine's choice is 28...Rb8.
     */
    private static final String BLACK_TO_MOVE_MATED = "2rr2k1/p2PRppp/8/5P2/3n4/8/PPP5/1K6 b - - 0 28";

    /** White to move, a passed a-pawn and a rook on the seventh. Best is Rxd8. */
    private static final String WHITE_TO_MOVE = "3r4/PR6/2k3p1/8/1P6/2P4P/r7/3R3K w - - 12 54";

    /** A mate score from StockfishService is ±100 pawns, White's perspective. */
    private static final double MATE_FOR_WHITE = 100.0;
    private static final double MATE_FOR_BLACK = -100.0;

    private PositionEvidenceBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new PositionEvidenceBuilder();
    }

    private static String statementOf(List<ChessFact> facts, ChessFact.Kind kind) {
        return facts.stream()
                .filter(f -> f.kind() == kind)
                .map(ChessFact::statement)
                .findFirst()
                .orElse(null);
    }

    private static boolean hasKind(List<ChessFact> facts, ChessFact.Kind kind) {
        return facts.stream().anyMatch(f -> f.kind() == kind);
    }

    @Nested
    @DisplayName("Move Resolution Tests")
    class MoveResolutionTests {

        @Test
        @DisplayName("Should resolve a move given in UCI")
        void shouldResolveMoveGivenInUci() {
            Move m = builder.resolveMove(BLACK_TO_MOVE_MATED, "c8c7");

            assertThat(m).isNotNull();
            assertThat(m.toString()).isEqualTo("c8c7");
        }

        @Test
        @DisplayName("Should resolve a move given in SAN")
        void shouldResolveMoveGivenInSan() {
            // The tool schema asks for UCI and the model sends SAN, because SAN
            // is the notation it reads. Rejecting it silently dropped every
            // comparison fact and sent the model back to inventing reasons.
            Move m = builder.resolveMove(BLACK_TO_MOVE_MATED, "Rc7");

            assertThat(m).isNotNull();
            assertThat(m.toString()).isEqualTo("c8c7");
        }

        @Test
        @DisplayName("Should resolve SAN carrying check and annotation marks")
        void shouldResolveSanWithDecoration() {
            assertThat(builder.resolveMove(WHITE_TO_MOVE, "Qxd8+")).isNull(); // no queen
            assertThat(builder.resolveMove(WHITE_TO_MOVE, "Rxd8+")).isNotNull();
            assertThat(builder.resolveMove(BLACK_TO_MOVE_MATED, "Rc7!?")).isNotNull();
        }

        @Test
        @DisplayName("Should resolve castling in either notation")
        void shouldResolveCastlingInEitherNotation() {
            String castlingAvailable = "rnbqk2r/ppp2ppp/3p1n2/8/1b1PP3/2N2N2/PPP2PPP/R1BQKB1R b KQkq - 0 11";

            assertThat(builder.resolveMove(castlingAvailable, "O-O")).isNotNull();
            assertThat(builder.resolveMove(castlingAvailable, "e8g8")).isNotNull();
        }

        @Test
        @DisplayName("Should return null for a move that is not legal")
        void shouldReturnNullWhenMoveIsNotLegal() {
            // Resolving against the legal move list, not parsing the string, is
            // what makes this safe — an illegal move must not become a fact.
            assertThat(builder.resolveMove(BLACK_TO_MOVE_MATED, "Nc3")).isNull();
            assertThat(builder.resolveMove(BLACK_TO_MOVE_MATED, "nonsense")).isNull();
        }

        @Test
        @DisplayName("Should return null for null or blank input")
        void shouldReturnNullForNullOrBlankInput() {
            assertThat(builder.resolveMove(BLACK_TO_MOVE_MATED, null)).isNull();
            assertThat(builder.resolveMove(BLACK_TO_MOVE_MATED, "   ")).isNull();
        }
    }

    @Nested
    @DisplayName("Mate Sign Tests")
    class MateSignTests {

        @Test
        @DisplayName("Should report walking into mate when the opponent gets the mate")
        void shouldReportWalkingIntoMate() {
            // Math.abs() on the score made "I have mate" and "I get mated" the
            // same value, so this move — mate in 2 against the player — was
            // reported as costing nothing.
            Move played = builder.resolveMove(BLACK_TO_MOVE_MATED, "Rc7");

            List<ChessFact> facts =
                    builder.comparePlayed(BLACK_TO_MOVE_MATED, -6.92, played, MATE_FOR_WHITE, 0);

            assertThat(hasKind(facts, ChessFact.Kind.WALKS_INTO_MATE)).isTrue();
            assertThat(hasKind(facts, ChessFact.Kind.NO_LOSS)).isFalse();

            // Both endpoints in one sentence. Stated as two separate facts, the
            // model read them as simultaneous, could not reconcile them, and
            // announced "a severe flaw in the position assessment".
            assertThat(statementOf(facts, ChessFact.Kind.WALKS_INTO_MATE))
                    .contains("Before Rc7")
                    .contains("Black is ahead by 6.92 pawns")
                    .contains("After it, White has a forced mate");
        }

        @Test
        @DisplayName("Should report walking into mate for the other colour too")
        void shouldReportWalkingIntoMateWhenColoursReversed() {
            Move played = builder.resolveMove(WHITE_TO_MOVE, "Rb8");

            List<ChessFact> facts =
                    builder.comparePlayed(WHITE_TO_MOVE, 4.64, played, MATE_FOR_BLACK, 0);

            assertThat(statementOf(facts, ChessFact.Kind.WALKS_INTO_MATE))
                    .contains("Before Rb8")
                    .contains("White is ahead by 4.64 pawns")
                    .contains("After it, Black has a forced mate");
        }

        @Test
        @DisplayName("Should report no loss when the mover keeps its own mate")
        void shouldReportNoLossWhenMoverKeepsItsMate() {
            Move played = builder.resolveMove(BLACK_TO_MOVE_MATED, "Rc7");

            List<ChessFact> facts = builder.comparePlayed(
                    BLACK_TO_MOVE_MATED, MATE_FOR_BLACK, played, MATE_FOR_BLACK, 0);

            assertThat(hasKind(facts, ChessFact.Kind.NO_LOSS)).isTrue();
            assertThat(hasKind(facts, ChessFact.Kind.WALKS_INTO_MATE)).isFalse();
        }

        @Test
        @DisplayName("Should report a missed mate when the mate is given up")
        void shouldReportMissedMateWhenMateIsGivenUp() {
            Move played = builder.resolveMove(BLACK_TO_MOVE_MATED, "Rc7");

            List<ChessFact> facts =
                    builder.comparePlayed(BLACK_TO_MOVE_MATED, MATE_FOR_BLACK, played, -3.0, 0);

            assertThat(hasKind(facts, ChessFact.Kind.MISSED_MATE)).isTrue();
        }
    }

    @Nested
    @DisplayName("Evaluation Loss Tests")
    class EvaluationLossTests {

        @Test
        @DisplayName("Should measure loss from the mover's point of view when Black moves")
        void shouldMeasureLossFromBlacksPointOfView() {
            // Black is better at -6.92; after the move only -2.10. That is a
            // 4.82 pawn loss FOR BLACK, even though the raw score rose.
            Move played = builder.resolveMove(BLACK_TO_MOVE_MATED, "Rc7");

            List<ChessFact> facts =
                    builder.comparePlayed(BLACK_TO_MOVE_MATED, -6.92, played, -2.10, 0);

            assertThat(statementOf(facts, ChessFact.Kind.EVAL_LOSS)).contains("4.82 pawns");
        }

        @Test
        @DisplayName("Should measure loss from the mover's point of view when White moves")
        void shouldMeasureLossFromWhitesPointOfView() {
            Move played = builder.resolveMove(WHITE_TO_MOVE, "Rb8");

            List<ChessFact> facts = builder.comparePlayed(WHITE_TO_MOVE, 4.64, played, 1.14, 0);

            assertThat(statementOf(facts, ChessFact.Kind.EVAL_LOSS)).contains("3.5 pawns");
        }

        @Test
        @DisplayName("Should report no loss when the move matches the engine")
        void shouldReportNoLossWhenMoveMatchesEngine() {
            // Every branch must produce a cost fact, including "none" — silence
            // is what the model filled in with "loses the material advantage".
            Move played = builder.resolveMove(BLACK_TO_MOVE_MATED, "Rc7");

            List<ChessFact> facts =
                    builder.comparePlayed(BLACK_TO_MOVE_MATED, -6.92, played, -6.90, 0);

            assertThat(hasKind(facts, ChessFact.Kind.NO_LOSS)).isTrue();
            assertThat(hasKind(facts, ChessFact.Kind.EVAL_LOSS)).isFalse();
        }

        @Test
        @DisplayName("Should continue fact ids from the given start index")
        void shouldContinueFactIdsFromStartIndex() {
            Move played = builder.resolveMove(BLACK_TO_MOVE_MATED, "Rc7");

            List<ChessFact> facts =
                    builder.comparePlayed(BLACK_TO_MOVE_MATED, -6.92, played, -2.10, 4);

            assertThat(facts).isNotEmpty();
            assertThat(facts.get(0).id()).isEqualTo("f5");
        }
    }

    @Nested
    @DisplayName("Evaluation Wording Tests")
    class EvaluationWordingTests {

        @Test
        @DisplayName("Should name the side that is ahead rather than use a signed perspective")
        void shouldNameTheSideThatIsAhead() {
            // "-6.77 from White's perspective" was read back by the model as
            // "Black was deeply behind" for a position Black was winning.
            // Naming the side leaves nothing to invert.
            var ev = builder.build(BLACK_TO_MOVE_MATED, -6.92, "c8b8", List.of("c8b8"));

            String swing = statementOf(ev.facts(), ChessFact.Kind.EVAL_SWING);
            assertThat(swing).contains("Black is ahead by 6.92 pawns");
            assertThat(swing).doesNotContain("perspective");
        }

        @Test
        @DisplayName("Should name White when White is ahead")
        void shouldNameWhiteWhenWhiteIsAhead() {
            var ev = builder.build(WHITE_TO_MOVE, 4.64, "d1d8", List.of("d1d8"));

            assertThat(statementOf(ev.facts(), ChessFact.Kind.EVAL_SWING))
                    .contains("White is ahead by 4.64 pawns");
        }

        @Test
        @DisplayName("Should call a near-equal position level")
        void shouldCallNearEqualPositionLevel() {
            var ev = builder.build(WHITE_TO_MOVE, 0.04, "d1d8", List.of("d1d8"));

            assertThat(statementOf(ev.facts(), ChessFact.Kind.EVAL_SWING))
                    .contains("the position is level");
        }
    }

    @Nested
    @DisplayName("Board Fact Tests")
    class BoardFactTests {

        @Test
        @DisplayName("Should name the defending piece rather than say a piece defends itself")
        void shouldNameTheDefendingPiece() {
            // "the rook on b8 is defended" came back out of the model as
            // "Rb8 defends the rook on b8", which a rook cannot do.
            var ev = builder.build(BLACK_TO_MOVE_MATED, -6.92, "c8b8", List.of("c8b8"));

            assertThat(statementOf(ev.facts(), ChessFact.Kind.DEFENDED))
                    .isEqualTo("The rook on b8 is defended by the rook on d8 after Rb8.");
        }

        @Test
        @DisplayName("Should report what the moved piece attacks")
        void shouldReportWhatTheMovedPieceAttacks() {
            var ev = builder.build(BLACK_TO_MOVE_MATED, -6.92, "c8b8", List.of("c8b8"));

            assertThat(statementOf(ev.facts(), ChessFact.Kind.ATTACKS))
                    .isEqualTo("After Rb8, the rook attacks the pawn on b2.");
        }

        @Test
        @DisplayName("Should exclude the king from the attack list")
        void shouldExcludeKingFromAttackList() {
            // Rb6+ attacks the black king on c6 along the sixth rank. The CHECK
            // assertion is what keeps this test honest — without it, an empty
            // attack list would pass for the wrong reason.
            var ev = builder.build(WHITE_TO_MOVE, 4.64, "b7b6", List.of("b7b6"));

            assertThat(hasKind(ev.facts(), ChessFact.Kind.CHECK)).isTrue();

            // The king is covered by CHECK; listing it again produced
            // "attacks the king on c6", a clumsier restatement of the same fact.
            String attacks = statementOf(ev.facts(), ChessFact.Kind.ATTACKS);
            if (attacks != null) assertThat(attacks).doesNotContain("king");
        }

        @Test
        @DisplayName("Should report a capture, naming the move")
        void shouldReportCaptureNamingTheMove() {
            // Named rather than "It captures…" — these render as separate lines
            // to the player, where a pronoun has lost its antecedent.
            var ev = builder.build(WHITE_TO_MOVE, 4.64, "d1d8", List.of("d1d8"));

            assertThat(statementOf(ev.facts(), ChessFact.Kind.CAPTURE))
                    .isEqualTo("Rxd8 captures the rook on d8.");
        }

        @Test
        @DisplayName("Should report that the moving piece was already attacked")
        void shouldReportThatMovingPieceWasAlreadyAttacked() {
            // The d7 pawn attacks c8, so the rook was under attack before moving.
            var ev = builder.build(BLACK_TO_MOVE_MATED, -6.92, "c8b8", List.of("c8b8"));

            assertThat(statementOf(ev.facts(), ChessFact.Kind.ESCAPES_ATTACK))
                    .isEqualTo("The rook on c8 was attacked before this move.");
        }

        @Test
        @DisplayName("Should render the best move in SAN")
        void shouldRenderBestMoveInSan() {
            var ev = builder.build(WHITE_TO_MOVE, 4.64, "d1d8", List.of("d1d8"));

            assertThat(ev.bestMoveSan()).isEqualTo("Rxd8");
            assertThat(ev.bestMoveUci()).isEqualTo("d1d8");
        }
    }

    @Nested
    @DisplayName("Principal Variation Tests")
    class PrincipalVariationTests {

        @Test
        @DisplayName("Should number the main line from the FEN rather than restart at move one")
        void shouldNumberMainLineFromTheFen() {
            // chesslib's toSanWithMoveNumbers() always begins at "1." and gives
            // no hint that Black is to move, so a line at move 28 rendered as
            // "1. Rb8 Re8" — the same ply-versus-move confusion fixed elsewhere.
            var ev = builder.build(BLACK_TO_MOVE_MATED, -6.92, "c8b8", List.of("c8b8 e7e8"));

            assertThat(statementOf(ev.facts(), ChessFact.Kind.PRINCIPAL_VARIATION))
                    .contains("28...Rb8")
                    .doesNotContain("1.Rb8");
        }

        @Test
        @DisplayName("Should number a White line with a plain move number")
        void shouldNumberWhiteLineWithPlainMoveNumber() {
            var ev = builder.build(WHITE_TO_MOVE, 4.64, "d1d8", List.of("d1d8 c6b7"));

            assertThat(statementOf(ev.facts(), ChessFact.Kind.PRINCIPAL_VARIATION))
                    .contains("54.Rxd8");
        }

        @Test
        @DisplayName("Should survive a malformed principal variation")
        void shouldSurviveMalformedPrincipalVariation() {
            var ev = builder.build(BLACK_TO_MOVE_MATED, -6.92, "c8b8", List.of("garbage"));

            // No PV fact rather than an exception — a bad engine line must never
            // take the whole answer down.
            assertThat(ev.facts()).isNotEmpty();
            assertThat(hasKind(ev.facts(), ChessFact.Kind.PRINCIPAL_VARIATION)).isFalse();
        }
    }

    @Nested
    @DisplayName("Fact Priority Tests")
    class FactPriorityTests {

        @Test
        @DisplayName("Should rank the cost of the move above board geometry")
        void shouldRankCostAboveGeometry() {
            // Facts are built in board order, which buries the decisive one at
            // the bottom. A small model leads with what it reads first.
            int mate = new ChessFact("f1", ChessFact.Kind.WALKS_INTO_MATE, "x").priority();
            int loss = new ChessFact("f2", ChessFact.Kind.EVAL_LOSS, "x").priority();
            int best = new ChessFact("f3", ChessFact.Kind.BEST_MOVE, "x").priority();
            int attacks = new ChessFact("f4", ChessFact.Kind.ATTACKS, "x").priority();
            int alt = new ChessFact("f5", ChessFact.Kind.ALTERNATIVE, "x").priority();

            assertThat(mate).isLessThan(loss);
            assertThat(loss).isLessThan(best);
            assertThat(best).isLessThan(attacks);
            assertThat(attacks).isLessThan(alt);
        }
    }
}
