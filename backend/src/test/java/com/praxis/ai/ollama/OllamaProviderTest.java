package com.praxis.ai.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.ai.ChatMessage;
import com.praxis.ai.ChatResult;
import com.praxis.ai.CompletionRequest;
import com.praxis.ai.OutputBudget;
import com.praxis.ai.ToolCall;
import com.praxis.ai.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Ollama's wire format, unchanged by the move behind LlmProvider. */
@DisplayName("Ollama provider")
class OllamaProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("tool calls keep arguments as objects and carry no ids, as before")
    void toolTurnShape() {
        List<ChatMessage> turn = List.of(
                ChatMessage.user("my worst blunder?"),
                ChatMessage.toolCalls(List.of(new ToolCall("tc_1", "find_mistakes", Map.of("limit", 1)))),
                ChatMessage.toolResult("tc_1", "{\"callId\":\"tc_1\"}"));
        JsonNode json = MAPPER.valueToTree(OllamaProvider.toWire(turn));
        JsonNode call = json.get(1).get("tool_calls").get(0);
        assertThat(json.get(1).get("content").asText()).isEmpty();
        assertThat(call.has("id")).isFalse();
        assertThat(call.get("function").get("arguments").get("limit").asInt()).isEqualTo(1);
        assertThat(json.get(2).get("role").asText()).isEqualTo("tool");
        assertThat(json.get(2).has("tool_call_id")).isFalse();
    }

    @Test
    @DisplayName("an answer left in the thinking field is recovered; inline <think> is stripped")
    void thinking() throws Exception {
        ChatResult fromThinking = OllamaProvider.fromWire(
                MAPPER.readTree("{\"content\":\"\",\"thinking\":\"{\\\"answer\\\":\\\"x\\\"}\"}"), MAPPER);
        assertThat(fromThinking.content()).isEqualTo("{\"answer\":\"x\"}");

        ChatResult inline = OllamaProvider.fromWire(
                MAPPER.readTree("{\"content\":\"<think>hmm</think> {\\\"a\\\":1}\"}"), MAPPER);
        assertThat(inline.content()).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("tool-call arguments sent as a JSON string are parsed; numbers stay numbers")
    void stringArguments() throws Exception {
        ChatResult r = OllamaProvider.fromWire(MAPPER.readTree(
                "{\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"find_games\","
                        + "\"arguments\":\"{\\\"limit\\\":5,\\\"color\\\":\\\"white\\\"}\"}}]}"), MAPPER);
        assertThat(r.toolCalls().get(0).arguments()).containsEntry("limit", 5).containsEntry("color", "white");
    }

    @Test
    @DisplayName("generation limits go inside options, where Ollama reads them; no ignored top-level fields")
    void generateBody() {
        var req = CompletionRequest.standard("qwen2.5:7b", "Explain.", OutputBudget.EXPLANATION);
        JsonNode body = MAPPER.valueToTree(OllamaProvider.generateBody(req, 200));
        assertThat(body.get("options").get("num_predict").asInt()).isEqualTo(200);
        assertThat(body.get("options").get("temperature").asDouble()).isEqualTo(0.3);
        assertThat(body.has("num_predict")).isFalse();
        assertThat(body.has("num_ctx")).isFalse();
        assertThat(body.get("options").has("num_ctx")).isFalse();
        assertThat(body.get("format").asText()).isEqualTo("json");
        assertThat(body.get("keep_alive").asText()).isEqualTo("2h");
    }

    @Test
    @DisplayName("local limits sit well above measured answer lengths")
    void localLimits() {
        var p = new OllamaProvider(new com.praxis.config.AppProperties(null, null, null, null, null, null), MAPPER);
        assertThat(p.maxTokens(OutputBudget.EXPLANATION)).isEqualTo(200);   // answers peak near 70
        assertThat(p.maxTokens(OutputBudget.REPORT)).isEqualTo(768);        // training plan JSON reaches ~430
        assertThat(p.maxTokens(OutputBudget.CHAT_ANSWER)).isEqualTo(700);
    }

    @Test
    @DisplayName("a tool spec renders as a function with its JSON Schema")
    void toolSpec() {
        var spec = new ToolSpec("get_progress", "Drill counts.", Map.of("type", "object", "properties", Map.of()));
        JsonNode o = MAPPER.valueToTree(OllamaProvider.tools(List.of(spec))).get(0);
        assertThat(o.get("type").asText()).isEqualTo("function");
        assertThat(o.get("function").get("name").asText()).isEqualTo("get_progress");
        assertThat(o.get("function").get("parameters").get("type").asText()).isEqualTo("object");
    }
}
