package com.praxis.prax.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reply contract. A model that answers in prose instead of JSON produces
 * zero citations, and uncited chess claims then sail past evidence validation
 * looking exactly as authoritative as grounded ones — so recognising the
 * difference matters as much as parsing the happy path.
 */
@DisplayName("PraxResponse Tests")
class PraxResponseTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("Contract Parsing Tests")
    class ContractParsingTests {

        @Test
        @DisplayName("Should parse answer, evidence and selected facts")
        void shouldParseAnswerEvidenceAndFacts() {
            String raw = """
                {
                  "answer": "Your worst blunder was Rc7.",
                  "facts": ["f8", "f2"],
                  "evidence": [{"label": "Win drop", "value": "92.99%", "callId": "tc_1"}],
                  "followUp": null
                }
                """;

            PraxResponse r = PraxResponse.parse(raw, mapper);

            assertThat(r.answer()).isEqualTo("Your worst blunder was Rc7.");
            assertThat(r.factIds()).containsExactly("f8", "f2");
            assertThat(r.evidence()).hasSize(1);
            assertThat(r.evidence().get(0).callId()).isEqualTo("tc_1");
            assertThat(r.followUp()).isNull();
        }

        @Test
        @DisplayName("Should tolerate a fenced or prefixed JSON block")
        void shouldTolerateFencedJsonBlock() {
            String raw = """
                Here is my answer:
                ```json
                {"answer": "Checked.", "facts": [], "evidence": []}
                ```
                """;

            PraxResponse r = PraxResponse.parse(raw, mapper);

            assertThat(r.answer()).isEqualTo("Checked.");
        }

        @Test
        @DisplayName("Should return empty fact ids when the model selects none")
        void shouldReturnEmptyFactIdsWhenNoneSelected() {
            String raw = "{\"answer\": \"Hello.\", \"evidence\": []}";

            PraxResponse r = PraxResponse.parse(raw, mapper);

            assertThat(r.factIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Prose Fallback Tests")
    class ProseFallbackTests {

        @Test
        @DisplayName("Should keep plain prose rather than fail the turn")
        void shouldKeepPlainProse() {
            PraxResponse r = PraxResponse.parse("I checked your games and found nothing.", mapper);

            assertThat(r.answer()).isEqualTo("I checked your games and found nothing.");
            assertThat(r.evidence()).isEmpty();
            assertThat(r.factIds()).isEmpty();
        }

        @Test
        @DisplayName("Should cap runaway prose at a sentence boundary")
        void shouldCapRunawayProse() {
            // A 4B model given 1600 tokens of rope used them: one run repeated
            // "Final answer: Qg5 was a blunder" forty times.
            String loop = "Final answer: Qg5 was a blunder. ".repeat(80);

            PraxResponse r = PraxResponse.parse(loop, mapper);

            assertThat(r.answer()).hasSizeLessThan(1000);
            assertThat(r.answer()).endsWith("…");
        }

        @Test
        @DisplayName("Should return a null answer for null or blank input")
        void shouldReturnNullAnswerForNullOrBlankInput() {
            assertThat(PraxResponse.parse(null, mapper).answer()).isNull();
            assertThat(PraxResponse.parse("   ", mapper).answer()).isNull();
        }

        @Test
        @DisplayName("Should fall back to prose when the JSON is malformed")
        void shouldFallBackToProseWhenJsonIsMalformed() {
            PraxResponse r = PraxResponse.parse("{\"answer\": \"unterminated", mapper);

            assertThat(r.answer()).isNotNull();
            assertThat(r.evidence()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Contract Detection Tests")
    class ContractDetectionTests {

        @Test
        @DisplayName("Should recognise a compliant reply")
        void shouldRecogniseCompliantReply() {
            String raw = "{\"answer\": \"Checked.\", \"evidence\": []}";

            assertThat(PraxResponse.isContract(raw, mapper)).isTrue();
        }

        @Test
        @DisplayName("Should reject prose so the agent can ask again")
        void shouldRejectProse() {
            // The usual exit is the model answering while tools are still
            // offered, where format:json does not apply. Detecting that is what
            // buys the one corrective round trip.
            assertThat(PraxResponse.isContract("Your worst blunder was Rc7.", mapper)).isFalse();
        }

        @Test
        @DisplayName("Should reject JSON that is missing the evidence array")
        void shouldRejectJsonMissingEvidenceArray() {
            assertThat(PraxResponse.isContract("{\"answer\": \"Checked.\"}", mapper)).isFalse();
        }

        @Test
        @DisplayName("Should reject JSON whose answer is empty")
        void shouldRejectJsonWithEmptyAnswer() {
            assertThat(PraxResponse.isContract("{\"answer\": \"\", \"evidence\": []}", mapper))
                    .isFalse();
        }

        @Test
        @DisplayName("Should reject null and blank input")
        void shouldRejectNullAndBlankInput() {
            assertThat(PraxResponse.isContract(null, mapper)).isFalse();
            assertThat(PraxResponse.isContract("  ", mapper)).isFalse();
        }
    }
}
