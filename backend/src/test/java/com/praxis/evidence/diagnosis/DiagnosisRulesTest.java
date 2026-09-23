package com.praxis.evidence.diagnosis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The §7 taxonomy applied to graphs.
 *
 * These pin down that each definition is applied as written. Whether the
 * definitions are the RIGHT ones is a different question, and only hand labels
 * answer it — that is Phase 3's exit criterion, not these tests'.
 */
@DisplayName("Diagnosis rules")
class DiagnosisRulesTest {

    @Nested
    @DisplayName("single-cause mistakes")
    class SingleCause {

        /**
         * §6.7. The mate was on before 3...Nf6, and 3...g6 stops it: IGNORED_THREAT,
         * not something the move created. Every explanation of this position the
         * baseline produced got that backwards.
         */
        @Test
        void scholarsMateIsAnIgnoredThreat() {
            var l = DiagnosisRules.label(TestGraphs.scholarsMate());

            assertThat(l.consequence()).isEqualTo("MATED");
            assertThat(l.fired()).containsExactly("IGNORED_THREAT");
            assertThat(l.singleCause()).isTrue();
            assertThat(l.mechanism()).isEqualTo("IGNORED_THREAT");
            // Qxf7 is neither a fork, pin nor skewer, and not a back-rank mate.
            assertThat(l.motif()).isEqualTo("OTHER");
            assertThat(l.visibility()).isEqualTo("SHALLOW");
        }

        @Test
        void aKnightPutWhereItCanBeWonMovedIntoAttack() {
            var l = DiagnosisRules.label(TestGraphs.hangingKnight());

            assertThat(l.consequence()).isEqualTo("LOST_MATERIAL");
            assertThat(l.fired()).containsExactly("MOVED_INTO_ATTACK");
            assertThat(l.motif()).isEqualTo("HANGING_PIECE");
        }

        /**
         * §6.5's test, not §7.1's shorthand: the question is whether the REPLY
         * was already threatened, not whether any threat existed. Here a different
         * threat did, and the move still created the fork that punishes it.
         */
        @Test
        void anotherThreatExistingDoesNotStopTheMoveCreatingThisOne() {
            var l = DiagnosisRules.label(TestGraphs.differentThreat());

            assertThat(l.fired()).containsExactly("CREATED_TACTIC");
            assertThat(l.motif()).isEqualTo("FORK");
        }

        /**
         * The threat does not have to be the first reply. From the hand labels:
         * the knight was already attacked, and the engine takes it five plies later.
         */
        @Test
        void aThreatCarriedOutLaterIsStillAnIgnoredThreat() {
            var l = DiagnosisRules.label(TestGraphs.threatCarriedOutLater());

            assertThat(l.consequence()).isEqualTo("LOST_MATERIAL");
            assertThat(l.fired()).containsExactly("IGNORED_THREAT");
        }

        /** If the engine's line loses the same piece to the same move, the move played is not what ignored it. */
        @Test
        void notWhenTheEnginesLineAlsoAllowsIt() {
            assertThat(DiagnosisRules.threatCarriedOut(TestGraphs.threatTakenInBothLines())).isFalse();
        }

        /**
         * When R1 just takes the piece that moved, and it could simply be won, the
         * geometry that comes with the capture is a side effect: MOVED_INTO_ATTACK
         * alone, not composite with CREATED_TACTIC.
         */
        @Test
        void takingTheMovedPieceIsNotAlsoACreatedTactic() {
            var l = DiagnosisRules.label(TestGraphs.moverTakenWithGeometry(-3));

            assertThat(l.fired()).containsExactly("MOVED_INTO_ATTACK");
            assertThat(l.singleCause()).isTrue();
        }

        /** But when the piece was protected and only the tactic wins it, the tactic is the cause. */
        @Test
        void aProtectedPieceWonByATacticIsACreatedTactic() {
            var l = DiagnosisRules.label(TestGraphs.moverTakenWithGeometry(0));

            assertThat(l.fired()).containsExactly("CREATED_TACTIC");
        }

        @Test
        void aWinNotTakenIsAMissedOpportunity() {
            var l = DiagnosisRules.label(TestGraphs.missedWin());

            assertThat(l.consequence()).isEqualTo("MISSED_MATERIAL");
            assertThat(l.fired()).containsExactly("MISSED_OPPORTUNITY");
        }
    }

    @Nested
    @DisplayName("the composite subset and abstention")
    class CompositeAndAbstention {

        /**
         * Two rules hold. Both are recorded (§7.2) and neither is named: picking
         * one would be the rules guessing, which is exactly what the model is
         * being trained not to do.
         */
        @Test
        void twoCausesAreRecordedAndNeitherIsChosen() {
            var l = DiagnosisRules.label(TestGraphs.twoCauses());

            assertThat(l.fired()).containsExactly("REMOVED_DEFENDER", "MOVED_INTO_ATTACK");
            assertThat(l.composite()).isTrue();
            assertThat(l.mechanism()).isEqualTo("UNCLEAR");
        }

        /** Positional mistakes are abstained on: no cause, POSITIONAL motif. */
        @Test
        void noConcreteConsequenceAbstains() {
            var l = DiagnosisRules.label(TestGraphs.positional());

            assertThat(l.consequence()).isEqualTo("NOT_CONCRETE");
            assertThat(l.fired()).isEmpty();
            assertThat(l.composite()).isFalse();
            assertThat(l.mechanism()).isEqualTo("NONE");
            assertThat(l.motif()).isEqualTo("POSITIONAL");
        }

        /**
         * With the player in check the probe cannot run. "No threat" is then
         * unknown, not false — so REMOVED_DEFENDER, which needs it, stays silent
         * and only the rule that does not depend on it fires.
         */
        @Test
        void aMissingProbeIsNotReadAsNoThreat() {
            var l = DiagnosisRules.label(TestGraphs.inCheck());

            assertThat(l.fired()).containsExactly("MOVED_INTO_ATTACK");
        }
    }

    @Nested
    @DisplayName("the rules' own diagnosis")
    class OwnDiagnosis {

        /**
         * The rules are the fallback when a model fails verification, so their
         * output must pass verification itself — in both modes, on every shape of
         * mistake. If this fails, the fallback is not a fallback.
         */
        @Test
        void everyRulesDiagnosisPassesTheVerifier() {
            var graphs = java.util.List.of(TestGraphs.scholarsMate(), TestGraphs.hangingKnight(),
                    TestGraphs.missedWin(), TestGraphs.twoCauses(), TestGraphs.positional(),
                    TestGraphs.inCheck(), TestGraphs.differentThreat(), TestGraphs.threatCarriedOutLater(),
                    TestGraphs.moverTakenWithGeometry(-3), TestGraphs.moverTakenWithGeometry(0));
            for (var g : graphs) {
                var d = DiagnosisRules.diagnose(g);
                for (var mode : DiagnosisVerifier.Mode.values()) {
                    var report = DiagnosisVerifier.verify(g, d, mode);
                    assertThat(report.violations())
                            .as("%s in %s mode: %s", g.played().san(), mode, d.explanation())
                            .isEmpty();
                }
            }
        }

        @Test
        void scholarsMateReadsAsTheLessonItTeaches() {
            var d = DiagnosisRules.diagnose(TestGraphs.scholarsMate());

            assertThat(d.explanation())
                    .contains("already threatened Qxf7#")
                    .contains("Nf6 does not deal with it")
                    .contains("After g6, Qxf7# would not be possible");
            assertThat(d.reasoningChain()).extracting(Diagnosis.Claim::type)
                    .containsExactly("THREAT_EXISTS", "DOES_NOT_ADDRESS", "CRITICAL_REPLY",
                            "MATE_IN", "COUNTERFACTUAL", "VISIBILITY");
            assertThat(d.criticalResponse()).isEqualTo("Qxf7#");
        }

        /**
         * The threat is named, not R1. There is no counterfactual, because CF1
         * tests R1 after the engine's move, not the threat.
         */
        @Test
        void aThreatCarriedOutLaterNamesTheThreat() {
            var d = DiagnosisRules.diagnose(TestGraphs.threatCarriedOutLater());

            assertThat(d.explanation())
                    .contains("White already threatened cxd5")
                    .contains("Qc3 does not deal with it: after Ke2, cxd5 still comes");
            assertThat(d.reasoningChain()).extracting(Diagnosis.Claim::type)
                    .containsExactly("THREAT_EXISTS", "DOES_NOT_ADDRESS", "CRITICAL_REPLY",
                            "MATERIAL_CHANGE", "VISIBILITY");
        }

        /** The composite subset states what happened and names no cause. */
        @Test
        void compositeDiagnosesStateFactsOnly() {
            var d = DiagnosisRules.diagnose(TestGraphs.twoCauses());

            assertThat(d.mechanism()).isEqualTo("UNCLEAR");
            assertThat(d.reasoningChain()).extracting(Diagnosis.Claim::type)
                    .doesNotContain("COUNTERFACTUAL", "THREAT_EXISTS", "MOVED_INTO_ATTACK", "DEFENDER_REMOVED");
        }
    }
}
