package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.praxis.config.AppProperties;
import com.praxis.service.analysis.StockfishService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worked example from the plan (§6.7), end to end over the primitives.
 *
 * After 1.e4 e5 2.Bc4 Nc6 3.Qh5, Black plays 3...Nf6?? and is mated. It is the
 * cleanest possible test of the central claim: the danger was there *before* the
 * move, so the honest explanation is "you did not deal with Qxf7", not "your
 * move allowed it". Every current explanation of this position gets that
 * backwards, because nothing in the pipeline could ask the question.
 *
 * The geometry half runs anywhere. The engine half is skipped when Stockfish is
 * not configured, rather than silently passing.
 */
@DisplayName("Scholar's mate — the §6.7 graph")
class ScholarsMateEvidenceTest {

    /** Black to move, before 3...Nf6. */
    private static final String P0 =
            "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3";

    private static Board board() {
        Board b = new Board();
        b.loadFromFen(P0);
        return b;
    }

    @Nested
    @DisplayName("what the board alone shows")
    class Geometry {

        /**
         * f7 is attacked twice — by the queen on h5 and the bishop on c4 — and
         * defended only by the king. That is the whole mechanism, and it is true
         * before Black touches anything.
         */
        @Test
        void f7IsAlreadyOverloadedBeforeTheMove() {
            Board b = board();

            var attackers = Attacks.attackers(b, Square.F7, com.github.bhlangonijr.chesslib.Side.WHITE);
            assertThat(attackers).containsExactlyInAnyOrder(Square.H5, Square.C4);

            var defenders = Attacks.defenders(b, Square.F7);
            assertThat(defenders).containsExactly(Square.E8);
        }

        /**
         * 3...Nf6 changes nothing about f7. The diff is the evidence that the
         * move did not cause the problem — which is the opposite of what every
         * explanation of this position says.
         */
        @Test
        void theBlunderDoesNotTouchTheMatingSquare() {
            var delta = PositionDiff.of(board(), new Move(Square.G8, Square.F6));

            assertThat(delta.pieces())
                    .filteredOn(p -> p.square() == Square.F7)
                    .isEmpty();
            assertThat(delta.nowLoose()).isEmpty();
        }

        /**
         * 3...g6 does deal with it: the pawn attacks the queen and blocks the
         * h5–f7 diagonal, so f7 is no longer attacked twice.
         */
        @Test
        void theSavingMoveBlocksTheDiagonal() {
            Board after = Attacks.at(P0);
            assertThat(after.doMove(new Move(Square.G7, Square.G6))).isTrue();

            var attackers = Attacks.attackers(after, Square.F7, com.github.bhlangonijr.chesslib.Side.WHITE);
            assertThat(attackers).containsExactly(Square.C4);
            assertThat(Attacks.from(after, Square.G6)).contains(Square.H5);
        }
    }

    @Nested
    @EnabledIf("com.praxis.evidence.ScholarsMateEvidenceTest#stockfishAvailable")
    @DisplayName("what the engine adds")
    class Engine {

        private static StockfishService engine;
        private static AppProperties props;

        @BeforeAll
        static void start() {
            props = new AppProperties(
                    null, null, new AppProperties.Stockfish(stockfishPath().orElseThrow()),
                    null, null, null);
            engine = new StockfishService(props);
            engine.init();
            engine.setDeterministic(true);
        }

        /**
         * The whole §6.7 claim, through the real evidence service: the mate was
         * on the board before 3...Nf6, the refutation IS that threat, so the move
         * ignored it rather than caused it. And asked twice, the service says
         * exactly the same thing — on the shared multi-threaded engine it did not.
         */
        @Test
        void theServiceCallsItAnIgnoredThreatAndSaysSoReproducibly() {
            var service = new EvidenceExplainService(engine, new ThreatProbe(engine), props);
            Move nf6 = new Move(Square.G8, Square.F6);

            var first = service.gather(P0, nf6, "Nf6",
                    com.github.bhlangonijr.chesslib.Side.BLACK, "OPENING").orElseThrow();
            var second = service.gather(P0, nf6, "Nf6",
                    com.github.bhlangonijr.chesslib.Side.BLACK, "OPENING").orElseThrow();

            assertThat(first.consequence().mate()).isTrue();
            assertThat(first.replyUci()).isEqualTo("h5f7");
            assertThat(first.threatened()).isTrue();
            assertThat(first.threatIsReply()).isTrue();
            assertThat(second.block()).isEqualTo(first.block());
        }

        @AfterAll
        static void stop() {
            if (engine != null) engine.destroy();
        }

        /**
         * The probe: give White a free move in the position before the blunder,
         * and Qxf7 mates. So the threat pre-dates 3...Nf6 — `IGNORED_THREAT`,
         * not a tactic the move created.
         */
        @Test
        void theThreatExistsBeforeTheMove() {
            var threat = new ThreatProbe(engine).probe(P0, 12);

            assertThat(threat.found()).isTrue();
            assertThat(threat.moveUci()).isEqualTo("h5f7");
        }

        /** The probe refuses rather than guesses when the player is in check. */
        @Test
        void aPlayerInCheckCannotPass() {
            String inCheck = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3";
            var threat = new ThreatProbe(engine).probe(inCheck, 8);

            assertThat(threat.probed()).isFalse();
            assertThat(threat.skipReason()).contains("check");
        }

        /**
         * The depth curve after 3...Nf6 shows mate immediately — depth 1. That is
         * what makes this a SHALLOW miss: nothing deep was required to see it.
         */
        @Test
        void theMateIsVisibleAtTheFirstDepth() {
            Board after = Attacks.at(P0);
            after.doMove(new Move(Square.G8, Square.F6));

            var search = engine.search(after.getFen(), 10);

            assertThat(search.depthCurve()).isNotEmpty();
            var visibility = DepthCurve.visibilityDepth(search.depthCurve(), 0.0, false, 5.0);
            assertThat(visibility).isPresent();
            assertThat(DepthCurve.classify(visibility)).isEqualTo(DepthCurve.Visibility.SHALLOW);
        }

        /**
         * The counterfactual: Qxf7 is not even legal after 3...g6, so the reply
         * that refutes the blunder is unavailable against the right move. This is
         * what turns "your move allowed it" from a guess into a checkable claim —
         * here it correctly shows the move did NOT allow it, the position did.
         */
        @Test
        void theRefutationIsUnavailableAfterTheBestMove() {
            Board afterG6 = Attacks.at(P0);
            afterG6.doMove(new Move(Square.G7, Square.G6));

            boolean legal = afterG6.legalMoves().stream()
                    .anyMatch(m -> m.getFrom() == Square.H5 && m.getTo() == Square.F7);

            assertThat(legal).isFalse();
        }

        /** Deterministic mode means the same question twice gets the same answer. */
        @Test
        void aRepeatedSearchGivesTheSameAnswer() {
            engine.clearHash();
            var first = engine.search(P0, 12);
            engine.clearHash();
            var second = engine.search(P0, 12);

            assertThat(second.bestMoveUci()).isEqualTo(first.bestMoveUci());
            assertThat(second.depthCurve()).isEqualTo(first.depthCurve());
        }

        /** searchmoves restricts the search to one reply, which is how CF1 is priced. */
        @Test
        void searchMovesPricesASingleReply() {
            var restricted = engine.search(P0, 10, List.of("g8f6"));

            assertThat(restricted.bestMoveUci()).isEqualTo("g8f6");
        }
    }

    static Optional<String> stockfishPath() {
        String fromEnv = System.getenv("STOCKFISH_PATH");
        if (fromEnv != null && Files.isExecutable(Path.of(fromEnv))) return Optional.of(fromEnv);
        // The path this project is configured with locally. application.yml is
        // gitignored, so the test cannot read it and must not depend on it.
        String local = "D:/Tanm/stockfish-windows-x86-64-avx2/stockfish/stockfish-windows-x86-64-avx2.exe";
        return Files.isExecutable(Path.of(local)) ? Optional.of(local) : Optional.empty();
    }

    static boolean stockfishAvailable() {
        return stockfishPath().isPresent();
    }
}
