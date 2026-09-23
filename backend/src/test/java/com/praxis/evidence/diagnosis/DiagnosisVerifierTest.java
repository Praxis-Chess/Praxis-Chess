package com.praxis.evidence.diagnosis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each §8.4 rule, caught failing on the error it exists to catch.
 *
 * The starting point is always a diagnosis that verifies — the rules' own — and
 * each test breaks exactly one thing, so a failure names the rule that let the
 * error through. Several of these errors are ones the baseline or the Phase 1
 * model actually made on real games.
 */
@DisplayName("Diagnosis verifier")
class DiagnosisVerifierTest {

    private static final DiagnosisVerifier.Mode CLAIM = DiagnosisVerifier.Mode.CLAIM;
    private static final DiagnosisVerifier.Mode CITATION = DiagnosisVerifier.Mode.CITATION;

    private static Diagnosis scholar() {
        return DiagnosisRules.diagnose(TestGraphs.scholarsMate());
    }

    private static List<Integer> rulesBroken(Diagnosis d, DiagnosisVerifier.Mode mode) {
        return DiagnosisVerifier.verify(TestGraphs.scholarsMate(), d, mode).violations().stream()
                .map(DiagnosisVerifier.Violation::rule).distinct().sorted().toList();
    }

    private static Diagnosis withChain(Diagnosis d, List<Diagnosis.Claim> chain) {
        return new Diagnosis(chain, d.consequence(), d.mechanism(), d.motif(),
                d.criticalResponse(), d.visibility(), d.explanation());
    }

    private static Diagnosis withExplanation(Diagnosis d, String text) {
        return new Diagnosis(d.reasoningChain(), d.consequence(), d.mechanism(), d.motif(),
                d.criticalResponse(), d.visibility(), text);
    }

    private static Diagnosis.Claim claim(String type, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        return new Diagnosis.Claim(type, args, List.of("R1"), "");
    }

    @Test
    @DisplayName("the starting point verifies")
    void baselinePasses() {
        assertThat(rulesBroken(scholar(), CLAIM)).isEmpty();
        assertThat(rulesBroken(scholar(), CITATION)).isEmpty();
    }

    @Nested
    @DisplayName("rule 1 — schema")
    class Schema {

        @Test
        void anUnknownClaimTypeIsRejected() {
            var chain = new ArrayList<>(scholar().reasoningChain());
            chain.add(claim("FEELS_BAD", "move", "Nf6"));
            assertThat(rulesBroken(withChain(scholar(), chain), CLAIM)).contains(1);
        }

        @Test
        void aChainLongerThanSevenIsRejected() {
            var chain = new ArrayList<>(scholar().reasoningChain());
            chain.add(claim("VISIBILITY", "band", "SHALLOW"));
            chain.add(claim("VISIBILITY", "band", "SHALLOW"));
            assertThat(chain).hasSizeGreaterThan(7);
            assertThat(rulesBroken(withChain(scholar(), chain), CLAIM)).contains(1);
        }

        @Test
        void aMissingRequiredArgumentIsRejected() {
            var chain = new ArrayList<>(scholar().reasoningChain());
            chain.set(2, claim("CRITICAL_REPLY", "result", "MATE"));   // no "move"
            assertThat(rulesBroken(withChain(scholar(), chain), CLAIM)).contains(1);
        }
    }

    @Nested
    @DisplayName("rule 2 — every claim is true of the graph")
    class Truth {

        /** The wrong punishment — the error the baseline made most. */
        @Test
        void aWrongCriticalReplyIsFalse() {
            var chain = new ArrayList<>(scholar().reasoningChain());
            chain.set(2, claim("CRITICAL_REPLY", "move", "Qxe5+", "result", "MATE"));
            assertThat(rulesBroken(withChain(scholar(), chain), CLAIM)).contains(2);
        }

        /** The Phase 2 lab's real case: "Ne5 wins the queen" when nothing was won. */
        @Test
        void aMaterialClaimThatDisagreesWithTheLineIsFalse() {
            var chain = new ArrayList<>(scholar().reasoningChain());
            chain.set(3, claim("MATERIAL_CHANGE", "amount", 9, "line", "PLAYED"));
            assertThat(rulesBroken(withChain(scholar(), chain), CLAIM)).contains(2);
        }

        /** The baseline's own worst line: `Bg5` called "a fork on f6". */
        @Test
        void aGeometryThatIsNotOnTheBoardIsFalse() {
            var chain = new ArrayList<>(scholar().reasoningChain());
            chain.add(0, claim("TACTIC_GEOMETRY", "kind", "FORK", "by", "f6", "targets", List.of("h5", "e4")));
            chain.remove(chain.size() - 1);
            assertThat(rulesBroken(withChain(scholar(), chain), CLAIM)).contains(2);
        }

        /** ATTACK_DEFENSE is checked on the board itself, not the graph. */
        @Test
        void attackAndDefenceAreReadOffTheBoard() {
            var right = claim("ATTACK_DEFENSE", "square", "f7",
                    "attackers", List.of("Qh5", "Bc4"), "defenders", List.of("Ke8"));
            var wrong = claim("ATTACK_DEFENSE", "square", "f7",
                    "attackers", List.of("Qh5"), "defenders", List.of("Ke8", "Qd8"));

            assertThat(DiagnosisVerifier.falsity(TestGraphs.scholarsMate(), right)).isNull();
            assertThat(DiagnosisVerifier.falsity(TestGraphs.scholarsMate(), wrong)).isNotNull();
        }
    }

    @Nested
    @DisplayName("rule 3 — labels")
    class Labels {

        /** A single-cause mistake has one right mechanism. */
        @Test
        void theWrongMechanismIsRejected() {
            var d = scholar();
            var wrong = new Diagnosis(d.reasoningChain(), d.consequence(), "REMOVED_DEFENDER",
                    d.motif(), d.criticalResponse(), d.visibility(), d.explanation());
            assertThat(rulesBroken(wrong, CLAIM)).contains(3);
        }

        /**
         * In the composite subset a model may pick one of the causes whose
         * preconditions hold — but not one whose do not.
         */
        @Test
        void compositeMechanismsMustHaveTheirPreconditions() {
            var g = TestGraphs.twoCauses();
            var d = DiagnosisRules.diagnose(g);
            var allowed = new Diagnosis(d.reasoningChain(), d.consequence(), "MOVED_INTO_ATTACK",
                    d.motif(), d.criticalResponse(), d.visibility(), d.explanation());
            var notAllowed = new Diagnosis(d.reasoningChain(), d.consequence(), "IGNORED_THREAT",
                    d.motif(), d.criticalResponse(), d.visibility(), d.explanation());

            assertThat(DiagnosisVerifier.verify(g, allowed, CLAIM).passed()).isTrue();
            assertThat(DiagnosisVerifier.verify(g, notAllowed, CLAIM).violations())
                    .extracting(DiagnosisVerifier.Violation::rule).contains(3);
        }
    }

    @Nested
    @DisplayName("rule 4 — causal language needs evidence")
    class Causation {

        /**
         * "Nf6 allowed Qxf7#" is a causal claim, and without the counterfactual
         * it is also the wrong one: the mate was already on.
         */
        @Test
        void causalWordsWithoutACounterfactualAreRejected() {
            var d = scholar();
            var chain = d.reasoningChain().stream()
                    .filter(c -> !"COUNTERFACTUAL".equals(c.type())).toList();
            var noCf = withExplanation(withChain(d, chain), "Nf6 allowed Qxf7#, which is mate.");
            assertThat(rulesBroken(noCf, CLAIM)).contains(4);
        }

        @Test
        void alreadyNeedsAThreat() {
            var d = scholar();
            var chain = d.reasoningChain().stream()
                    .filter(c -> !"THREAT_EXISTS".equals(c.type())).toList();
            var noThreat = withExplanation(withChain(d, chain), "Qxf7# was already coming.");
            assertThat(rulesBroken(noThreat, CLAIM)).contains(4);
        }
    }

    @Nested
    @DisplayName("rule 5 — prose adds nothing the chain did not earn")
    class Prose {

        /** "g5 wins the bishop" style additions: a square no claim mentions. */
        @Test
        void aSquareInTheProseButNotTheChainIsRejected() {
            var d = withExplanation(scholar(), scholar().explanation() + " The bishop on e5 is also weak.");
            assertThat(rulesBroken(d, CLAIM)).contains(5);
        }

        @Test
        void moveNumbersAndCheckMarksDoNotCount() {
            var d = withExplanation(scholar(), "3...Nf6 does not deal with it: 4.Qxf7 is mate.");
            // Nf6 and Qxf7 both appear in verified claims, whatever the decoration.
            assertThat(rulesBroken(d, CLAIM)).doesNotContain(5);
        }
    }

    @Nested
    @DisplayName("rules 6 to 8")
    class Remaining {

        @Test
        void theCriticalResponseMustBeR1() {
            var d = scholar();
            var wrong = new Diagnosis(d.reasoningChain(), d.consequence(), d.mechanism(), d.motif(),
                    "4.Qxe5+", d.visibility(), d.explanation());
            assertThat(rulesBroken(wrong, CLAIM)).contains(6);
        }

        /** Citation mode only: claim mode ignores citations, so R0–R2 are not penalised. */
        @Test
        void aFabricatedCitationFailsOnlyInCitationMode() {
            var d = scholar();
            var chain = new ArrayList<>(d.reasoningChain());
            var c = chain.get(2);
            chain.set(2, new Diagnosis.Claim(c.type(), c.args(), List.of("Δ9"), c.text()));
            var faked = withChain(d, chain);

            assertThat(rulesBroken(faked, CLAIM)).doesNotContain(7);
            assertThat(rulesBroken(faked, CITATION)).contains(7);
        }

        /** Inventing a cause for a positional mistake — rule 8's whole reason. */
        @Test
        void aPositionalMistakeGetsNoInventedCause() {
            var g = TestGraphs.positional();
            var d = DiagnosisRules.diagnose(g);
            var invented = new Diagnosis(d.reasoningChain(), d.consequence(), "CREATED_TACTIC",
                    d.motif(), d.criticalResponse(), d.visibility(), d.explanation());

            assertThat(DiagnosisVerifier.verify(g, invented, CLAIM).violations())
                    .extracting(DiagnosisVerifier.Violation::rule).contains(8);
        }
    }
}
