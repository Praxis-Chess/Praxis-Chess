package com.praxis.prax.chat;

import com.praxis.prax.evidence.Evidence;
import com.praxis.prax.tools.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §7 — the mechanism, not the instruction.
 *
 * A prompt asking a model not to invent statistics is a request; this class is
 * what makes it enforceable. Every case below is a way a fabricated or
 * mis-attributed figure could otherwise have reached the player.
 */
@DisplayName("EvidenceValidator Tests")
class EvidenceValidatorTest {

    private static Map<String, ToolResult> results(Object... pairs) {
        Map<String, ToolResult> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (ToolResult) pairs[i + 1]);
        }
        return m;
    }

    private static PraxResponse.Claim claim(String label, String value, String callId) {
        return new PraxResponse.Claim(label, value, callId);
    }

    @Nested
    @DisplayName("Citation Resolution Tests")
    class CitationResolutionTests {

        @Test
        @DisplayName("Should keep a claim whose call id resolves")
        void shouldKeepClaimWhoseCallIdResolves() {
            var src = results("tc_1", ToolResult.playerData("get_player_profile", Map.of(), 100));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Games analysed", "100", "tc_1")), src);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).value()).isEqualTo("100");
            assertThat(out.get(0).source()).isEqualTo(Evidence.Provenance.PLAYER_DATA);
        }

        @Test
        @DisplayName("Should drop a claim whose call id does not resolve")
        void shouldDropClaimWhoseCallIdDoesNotResolve() {
            var src = results("tc_1", ToolResult.playerData("get_player_profile", Map.of(), 100));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Games analysed", "4200", "tc_9")), src);

            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("Should drop a claim with no call id at all")
        void shouldDropClaimWithNoCallId() {
            var src = results("tc_1", ToolResult.playerData("get_player_profile", Map.of(), 100));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Games analysed", "100", null)), src);

            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("Should resolve a claim that cites the tool by name when unambiguous")
        void shouldResolveClaimCitingToolByName() {
            // A small model often writes the tool name instead of the call id.
            // That is still a real reference, so it is honoured.
            var src = results("tc_1", ToolResult.playerData("get_mistake_patterns", Map.of(), 127));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Blunders", "9", "get_mistake_patterns")), src);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).source()).isEqualTo(Evidence.Provenance.PLAYER_DATA);
        }

        @Test
        @DisplayName("Should refuse a tool-name citation when two calls used that tool")
        void shouldRefuseAmbiguousToolNameCitation() {
            // Two calls, one name — the provenance attached would be a guess.
            var src = results(
                    "tc_1", ToolResult.playerData("find_games", Map.of(), 20),
                    "tc_2", ToolResult.playerData("find_games", Map.of(), 5));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Games", "20", "find_games")), src);

            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("Should drop a claim citing a tool that failed")
        void shouldDropClaimCitingFailedTool() {
            var src = results("tc_1", ToolResult.error("analyze_position", "Chess engine is not running."));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Evaluation", "-2.5", "tc_1")), src);

            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("Should drop a claim missing its label or value")
        void shouldDropClaimMissingLabelOrValue() {
            var src = results("tc_1", ToolResult.playerData("get_player_profile", Map.of(), 100));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim(null, "100", "tc_1"), claim("Games", null, "tc_1")), src);

            assertThat(out).isEmpty();
        }
    }

    @Nested
    @DisplayName("Provenance Tests")
    class ProvenanceTests {

        @Test
        @DisplayName("Should carry engine provenance through from the tool result")
        void shouldCarryEngineProvenance() {
            // The three classes must never blur: an engine number must not be
            // presentable as something measured about the player.
            var src = results("tc_2", ToolResult.engine("analyze_position", Map.of()));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Engine evaluation", "-6.92 pawns", "tc_2")), src);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).source()).isEqualTo(Evidence.Provenance.ENGINE);
        }

        @Test
        @DisplayName("Should keep a qualitative engine verdict")
        void shouldKeepQualitativeEngineVerdict() {
            // "forced mate" is what the engine returned; no number expresses it.
            var src = results("tc_2", ToolResult.engine("analyze_position", Map.of()));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Engine verdict", "forced mate", "tc_2")), src);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).value()).isEqualTo("forced mate");
        }

        @Test
        @DisplayName("Should drop a player-data claim whose value carries no figure")
        void shouldDropNonQuantitativePlayerDataClaim() {
            var src = results("tc_1", ToolResult.playerData("get_opening_performance", Map.of(), 27));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Most played", "the Philidor Defense", "tc_1")), src);

            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("Documents a gap: an ECO code passes the digit check")
        void documentsGapWhereEcoCodePassesDigitCheck() {
            // The prompt's own "wrong" example is {"label": "Philidor Defense",
            // "value": "C41"} — a subject wearing evidence's clothes. The
            // validator tests for a digit, and "C41" has one, so it survives.
            // Asserted rather than hidden: this is current behaviour, not
            // intended behaviour, and closing it needs a rule that recognises a
            // measurement rather than a character class.
            var src = results("tc_1", ToolResult.playerData("get_opening_performance", Map.of(), 27));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Philidor Defense", "C41", "tc_1")), src);

            assertThat(out).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Sample Size Tests")
    class SampleSizeTests {

        @Test
        @DisplayName("Should mark a player-data claim resting on too few games")
        void shouldMarkUnderpoweredClaim() {
            // Kept, but the thinness is made visible rather than silently hidden.
            var src = results("tc_1", ToolResult.playerData("get_opening_performance", Map.of(), 3));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Win rate", "67%", "tc_1")), src);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).value()).isEqualTo("67% (only 3 games)");
        }

        @Test
        @DisplayName("Should not mark a claim at the sample threshold")
        void shouldNotMarkClaimAtThreshold() {
            var src = results("tc_1",
                    ToolResult.playerData("get_opening_performance", Map.of(), Evidence.MIN_SAMPLE));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Win rate", "40%", "tc_1")), src);

            assertThat(out.get(0).value()).isEqualTo("40%");
        }

        @Test
        @DisplayName("Should not mark a single-object lookup as underpowered")
        void shouldNotMarkSingleObjectLookup() {
            // A move number from one game is a fact, not a statistic — tagging
            // it "(only 1 games)" was nonsense.
            var src = results("tc_1", ToolResult.singleObject("get_game", Map.of()));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Blunder at move", "28", "tc_1")), src);

            assertThat(out.get(0).value()).isEqualTo("28");
            assertThat(out.get(0).isUnderpowered()).isFalse();
        }

        @Test
        @DisplayName("Should never mark an engine claim as underpowered")
        void shouldNeverMarkEngineClaimAsUnderpowered() {
            var src = results("tc_2", ToolResult.engine("analyze_position", Map.of()));

            List<Evidence> out = EvidenceValidator.validate(
                    List.of(claim("Evaluation", "-6.92", "tc_2")), src);

            assertThat(out.get(0).isUnderpowered()).isFalse();
        }
    }
}
