package com.praxis.prax.grounding;

import com.praxis.prax.routing.QuestionRouter.Lane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last thing standing between the model's memory and the player.
 *
 * A misjudgement here is not cosmetic: the case that motivated this class
 * shipped an invented person, "Pal Benyamin Larsen", as chess history.
 */
@DisplayName("GroundingPolicy Tests")
class GroundingPolicyTest {

    /** Plenty of budget, so time never confounds a case that is not about time. */
    private static final long AMPLE = 120_000;

    private static Verdict assess(Lane lane, int trustedCalls, String q, boolean web) {
        return GroundingPolicy.assess(lane, trustedCalls, q, web, false, AMPLE);
    }

    @Nested
    @DisplayName("Grounded Answer Tests")
    class GroundedAnswerTests {

        @Test
        @DisplayName("Should ship when any trusted tool produced data")
        void shouldShipWhenGrounded() {
            assertThat(assess(Lane.PLAYER, 1, "What is my weakness?", true).isShip()).isTrue();
            assertThat(assess(Lane.GENERAL, 1, "What is the Najdorf?", true).isShip()).isTrue();
            assertThat(assess(Lane.HYBRID, 3, "How do I score in the Najdorf?", true).isShip()).isTrue();
        }

        @Test
        @DisplayName("Should ship a grounded answer even with web unavailable")
        void shouldShipGroundedWithoutWeb() {
            // Player data needs no network. Web being off must not suppress it.
            assertThat(assess(Lane.PLAYER, 2, "What is my weakness?", false).isShip()).isTrue();
        }
    }

    @Nested
    @DisplayName("Ungrounded Factual Tests")
    class UngroundedFactualTests {

        @Test
        @DisplayName("Should escalate the fabricated-name case rather than ship it")
        void shouldEscalateTheFabricationCase() {
            // REGRESSION. This exact question shipped an invented person because
            // the only guard on the PLAYER lane tested for DIGITS, and the answer
            // happened to contain none.
            Verdict v = assess(Lane.PLAYER, 0,
                    "Did Magnus and Nimzovich-Larsen ever had match between them?", true);

            assertThat(v.isShip()).as("an ungrounded factual answer must never ship").isFalse();
            assertThat(v.isEscalate()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Did Magnus and Nimzovich-Larsen ever had match between them?",
                "Has Bent Larsen ever beaten Fischer?",
                "Which opening did Nimzowitsch invent?",
                "Was Nimzowitsch a contemporary of Capablanca?",
                "Magnus Carlsen is Norwegian, right?",
        })
        @DisplayName("Should refuse to ship any ungrounded factual answer")
        void shouldNeverShipUngroundedFactual(String question) {
            // Every phrasing here is one the router does not match — which is
            // precisely the point. The guard makes that harmless.
            assertThat(assess(Lane.PLAYER, 0, question, true).isShip()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "suggest me best move I played",
                "what was my worst move?",
                "show me my blunders",
                "why was that game so bad?",
        })
        @DisplayName("Should never web-search a question about the player's own games")
        void shouldNotEscalateOwnGameQuestions(String question) {
            // REGRESSION. Asked "suggest me best move I played", the model called
            // nothing, the answer escalated, and Prax searched the WEB for a
            // question about this player's own chess — returning links to online
            // move calculators.
            //
            // Escalation rescues MISROUTES. Here the router was right and the
            // model simply failed to act, which a search cannot fix.
            Verdict v = assess(Lane.PLAYER, 0, question, true);

            assertThat(v.isEscalate())
                    .as("the web cannot know anything about this player's games")
                    .isFalse();
            assertThat(v.isRefuse()).isTrue();
            assertThat(v.playerMessage()).containsIgnoringCase("your own games");
        }

        @Test
        @DisplayName("Should still escalate a PLAYER verdict reached only by default")
        void shouldStillEscalateDefaultedPlayerVerdict() {
            // No first person, no game reference — the router landed here because
            // nothing matched, which is exactly the case escalation exists for.
            Verdict v = assess(Lane.PLAYER, 0,
                    "Did Magnus and Nimzovich-Larsen ever had match between them?", true);

            assertThat(v.isEscalate()).isTrue();
        }

        @Test
        @DisplayName("Should refuse outright when web research is switched off")
        void shouldRefuseWhenWebUnavailable() {
            Verdict v = assess(Lane.PLAYER, 0, "Who was Bent Larsen?", false);

            assertThat(v.isRefuse()).isTrue();
            assertThat(v.playerMessage()).isNotBlank();
            // The refusal must not hedge its way into stating the claim anyway.
            assertThat(v.playerMessage().toLowerCase())
                    .doesNotContain("i believe")
                    .doesNotContain("probably")
                    .doesNotContain("as far as i know");
        }

        @Test
        @DisplayName("Should not escalate without time to finish a search")
        void shouldRefuseWhenOutOfBudget() {
            Verdict v = GroundingPolicy.assess(Lane.PLAYER, 0, "Who was Bent Larsen?",
                    true, false, 3_000);

            assertThat(v.isRefuse()).isTrue();
        }
    }

    @Nested
    @DisplayName("Escalation Loop Tests")
    class EscalationLoopTests {

        @Test
        @DisplayName("Should refuse rather than escalate a second time")
        void shouldNotEscalateTwice() {
            Verdict v = GroundingPolicy.assess(Lane.PLAYER, 0, "Who was Bent Larsen?",
                    true, true, AMPLE);

            assertThat(v.isEscalate()).as("escalation must not loop").isFalse();
            assertThat(v.isRefuse()).isTrue();
        }

        @Test
        @DisplayName("Should not re-search a GENERAL question that already searched")
        void shouldNotReSearchGeneralLane() {
            // GENERAL pre-searches before the first model call, so an empty
            // result there means the search already failed.
            Verdict v = assess(Lane.GENERAL, 0, "What is the Nimzovich-Larsen Attack?", true);

            assertThat(v.isEscalate()).isFalse();
            assertThat(v.isRefuse()).isTrue();
        }
    }

    @Nested
    @DisplayName("Conversational Tests")
    class ConversationalTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "hi", "Hi Prax!", "hello", "hey there", "yo",
                "thanks", "Thanks!", "thank you", "cheers", "nice", "cool", "ok", "got it",
                "bye", "Good morning", "good evening prax",
                "who are you", "What can you do?", "help",
        })
        @DisplayName("Should ship conversational exchanges without demanding evidence")
        void shouldShipConversational(String question) {
            // A greeting must not trigger SearXNG. Nothing here is a claim about
            // the world, so there is nothing to ground.
            assertThat(assess(Lane.PLAYER, 0, question, true).isShip()).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Should treat empty input as conversational")
        void shouldTreatEmptyAsConversational(String question) {
            assertThat(assess(Lane.PLAYER, 0, question, true).isShip()).isTrue();
        }
    }

    @Nested
    @DisplayName("Substantive Classification Tests")
    class SubstantiveClassificationTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "Did Magnus and Nimzovich-Larsen ever had match between them?",
                "Who was Bent Larsen?",
                "What is my weakness?",
                "Where do I blunder most?",
                "Explain the theory behind 5.Bg5",
                "Magnus Carlsen is Norwegian",
        })
        @DisplayName("Should classify real questions as substantive")
        void shouldClassifySubstantive(String question) {
            assertThat(GroundingPolicy.isSubstantive(question)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"hi", "hi prax", "thanks", "ok", "cool", "bye", "who are you"})
        @DisplayName("Should classify chatter as not substantive")
        void shouldClassifyChatter(String question) {
            assertThat(GroundingPolicy.isSubstantive(question)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "How you doing?", "how are you", "How are you doing?", "how's it going",
                "How is it going?", "what's up", "How have you been?", "you alright?",
        })
        @DisplayName("Should classify 'how are you' greetings as not substantive")
        void shouldClassifyGreetingQuestions(String question) {
            // REGRESSION. These carry a question mark, so the short-input rule
            // does not exempt them, and they were classified substantive. A
            // conversational reply with zero tool calls would then have
            // ESCALATED — sending a greeting to SearXNG.
            assertThat(GroundingPolicy.isSubstantive(question)).isFalse();
        }

        @Test
        @DisplayName("Should not escalate a greeting to a web search")
        void shouldNotEscalateGreeting() {
            Verdict v = assess(Lane.PLAYER, 0, "How you doing?", true);

            assertThat(v.isEscalate()).as("a greeting must never reach the search engine").isFalse();
            assertThat(v.isShip()).isTrue();
        }

        @Test
        @DisplayName("Should still treat a real question about progress as substantive")
        void shouldNotSwallowRealProgressQuestions() {
            // The line is narrow on purpose: "how am I doing" is a genuine
            // request for measured data and must keep its grounding.
            assertThat(GroundingPolicy.isSubstantive("How am I doing?")).isTrue();
            assertThat(GroundingPolicy.isSubstantive("How are my openings doing?")).isTrue();
        }

        @Test
        @DisplayName("Should treat a short question mark as substantive")
        void shortQuestionsStillCount() {
            // "Who is Ding Liren?" is three words. Length alone must not excuse
            // an answer from being grounded — the question mark decides.
            assertThat(GroundingPolicy.isSubstantive("Who is Ding Liren?")).isTrue();
        }

        @Test
        @DisplayName("Should lean toward substantive when uncertain")
        void shouldLeanTowardSubstantive() {
            // The bias is the OPPOSITE of QuestionRouter's, deliberately. A false
            // positive costs one wasted search; a false negative ships a
            // fabrication. Anything unrecognised is treated as a claim.
            assertThat(GroundingPolicy.isSubstantive("tell me about Bent Larsen")).isTrue();
            assertThat(GroundingPolicy.isSubstantive("larsen vs fischer 1971")).isTrue();
        }
    }
}
