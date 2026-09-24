package com.praxis.evidence.diagnosis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "Why?" panel's content: the verified chain as steps, each on the board
 * that shows it.
 */
@DisplayName("Why view")
class WhyViewTest {

    @Nested
    @DisplayName("Scholar's mate")
    class ScholarsMate {

        private final WhyView.View v = WhyView.of(TestGraphs.scholarsMate());

        @Test
        void carriesTheVerifiedDiagnosisFromTheRules() {
            assertThat(v.verified()).isTrue();
            assertThat(v.diagnosedBy()).isEqualTo("RULES");
            assertThat(v.mechanism()).isEqualTo("IGNORED_THREAT");
            assertThat(v.explanation()).contains("already threatened Qxf7#");
        }

        @Test
        void oneStepPerClaimInChainOrder() {
            assertThat(v.steps()).extracting(WhyView.Step::type)
                    .containsExactly("THREAT_EXISTS", "DOES_NOT_ADDRESS", "CRITICAL_REPLY",
                            "MATE_IN", "COUNTERFACTUAL", "VISIBILITY");
        }

        /** The threat is shown where it existed: before the move, as the queen's arrow to f7. */
        @Test
        void theThreatIsDrawnBeforeTheMove() {
            var threat = v.steps().get(0);
            assertThat(threat.board()).isEqualTo("P0");
            assertThat(threat.arrows()).containsExactly(new WhyView.Arrow("h5", "f7", "THREAT"));
        }

        @Test
        void theReplyIsDrawnAfterTheMove() {
            var reply = v.steps().get(2);
            assertThat(reply.board()).isEqualTo("PP");
            assertThat(reply.arrows()).containsExactly(new WhyView.Arrow("h5", "f7", "REPLY"));
        }

        /** After g6 the queen cannot reach f7 at all, so no arrow is drawn for a move that is not possible. */
        @Test
        void anImpossibleReplyIsNotDrawn() {
            var cf = v.steps().get(4);
            assertThat(cf.board()).isEqualTo("PB");
            assertThat(cf.arrows()).isEmpty();
        }

        @Test
        void theOutcomeIsShownWhereItLands() {
            assertThat(v.steps().get(3).board()).isEqualTo("PC");
            // Mate: Black's king on e8, White's queen on f7.
            assertThat(v.boards().get("PC").fen()).startsWith("r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/");
            assertThat(v.boards().get("PC").title()).contains("Qxf7#");
        }

        @Test
        void everyStepPointsAtABoardThatExists() {
            assertThat(v.boards()).containsKeys("P0", "PP", "PB", "PC");
            for (var s : v.steps()) assertThat(v.boards()).containsKey(s.board());
        }

        @Test
        void factsLeadWithTheExplanation() {
            var facts = WhyView.facts(TestGraphs.scholarsMate(), v);
            assertThat(facts.get(0)).isEqualTo(v.explanation());
            assertThat(facts).contains("The damage is immediate: it lands with the opponent's reply, Qxf7#.");
            assertThat(facts).anyMatch(f -> f.startsWith("The engine sees the problem at depth 1"));
            assertThat(facts).contains("The engine's move: g6 attacks the queen on h5.");
        }
    }

    /** The threat is carried out five half-moves in: the outcome board is the one after cxd5. */
    @Test
    void aThreatCarriedOutLaterIsShownOnItsOwnSquaresAndLandsLater() {
        var g = TestGraphs.threatCarriedOutLater();
        var v = WhyView.of(g);

        assertThat(v.steps().get(0).arrows()).containsExactly(new WhyView.Arrow("c4", "d5", "THREAT"));
        assertThat(v.boards().get("PC").title()).contains("cxd5");
        assertThat(WhyView.facts(g, v)).contains("The damage lands 5 half-moves into the line, with cxd5.");
    }

    /** Positional: nothing to step through but visibility, and no consequence board. */
    @Test
    void aPositionalMistakeHasNoOutcomeBoard() {
        var v = WhyView.of(TestGraphs.positional());

        assertThat(v.consequence()).isEqualTo("NOT_CONCRETE");
        assertThat(v.steps()).extracting(WhyView.Step::type).containsExactly("VISIBILITY");
        assertThat(v.boards()).doesNotContainKey("PC");
    }

    /**
     * From a real game: Bxc6+ bxc6 is a fair trade, and the pawn is lost later
     * with hxg4. The damage lands at hxg4, not at the recapture, which is where
     * the graph's own consequence ply (first loss of anything) would put it.
     */
    @Test
    void theDamageLandsWhereTheNetLossIsReachedNotAtAnEvenRecapture() {
        var g = TestGraphs.hangingKnight();
        var tradeThenPawn = new com.praxis.evidence.graph.EvidenceGraph.Reply(
                "b7c6", "bxc6", "b7", "c6", false, java.util.List.of("bxc6", "Nf3", "hxg4"),
                java.util.List.of(new com.praxis.evidence.graph.EvidenceGraph.Taken("bishop", "c6", "bxc6", 1, 3),
                        new com.praxis.evidence.graph.EvidenceGraph.Taken("pawn", "g4", "hxg4", 3, 1)),
                java.util.List.of(new com.praxis.evidence.graph.EvidenceGraph.Taken("knight", "c6", "Bxc6+", 0, 3)),
                1, false, null, 1);
        var traded = new com.praxis.evidence.graph.EvidenceGraph(1, g.header(), g.p0(), g.best(), g.played(),
                tradeThenPawn, g.playedLine(), g.bestLine(), g.cf1(), g.t1(), g.delta(), g.geometry(),
                g.bestEffects(), g.movedEffects(), g.d1(), false);

        assertThat(traded.reply().consequencePly()).isEqualTo(1);
        assertThat(WhyView.landsAt(traded)).isEqualTo(3);
    }

    /** Found live: "Nxe7 captures the bishop on e7 · exchange 0" reached the player. */
    @Test
    void engineNotationNeverReachesAFact() {
        var g = TestGraphs.missedWin();
        var withCapture = new com.praxis.evidence.graph.EvidenceGraph(1, g.header(), g.p0(), g.best(), g.played(),
                g.reply(), g.playedLine(), g.bestLine(), g.cf1(), g.t1(), g.delta(), g.geometry(),
                java.util.List.of(new com.praxis.evidence.graph.EvidenceGraph.EffectItem("CAPTURES",
                        java.util.List.of("e5"), "Nxe5 captures the knight on e5 · exchange +3")),
                g.movedEffects(), g.d1(), false);

        var facts = WhyView.facts(withCapture, WhyView.of(withCapture));

        assertThat(facts).contains("The engine's move: Nxe5 captures the knight on e5.");
        assertThat(facts).noneMatch(f -> f.contains("exchange") || f.contains("SEE") || f.contains("·"));
    }

    @Test
    void mateLandsOnTheMatingMove() {
        assertThat(WhyView.landsAt(TestGraphs.scholarsMate())).isEqualTo(1);
    }

    @Test
    void nothingLostLandsNowhere() {
        assertThat(WhyView.landsAt(TestGraphs.missedWin())).isZero();
    }

    @Test
    void aMoveThatCannotBePlayedGivesNoBoard() {
        assertThat(WhyView.after(TestGraphs.SCHOLAR_FEN, java.util.List.of("e1e8"), 1)).isNull();
    }
}
