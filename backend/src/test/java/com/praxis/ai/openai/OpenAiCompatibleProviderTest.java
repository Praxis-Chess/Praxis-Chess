package com.praxis.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.ai.*;
import com.praxis.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI-compatible adapter: its wire format, and its HTTP behaviour against a
 * stand-in provider on localhost (no network, no key).
 */
@DisplayName("OpenAI-compatible provider")
class OpenAiCompatibleProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static AiProperties props(String baseUrl, String key) {
        return props(baseUrl, key, null, null);
    }

    private static AiProperties props(String baseUrl, String key, Map<String, Integer> maxTokens, Boolean reasoning) {
        return new AiProperties(new AiProperties.Cloud(baseUrl, key, "test-model", null, null, null, null,
                maxTokens, reasoning), List.of("prax", "reports"));
    }

    private static final String OK = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}";

    // ── wire format ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("tool calls carry the agent's ids, and results answer them by id")
    void pairsById() throws Exception {
        List<ChatMessage> turn = List.of(
                ChatMessage.system("You are Prax."),
                ChatMessage.toolCalls(List.of(
                        new ToolCall("tc_1", "find_mistakes", Map.of("limit", 1)),
                        new ToolCall("tc_2", "explain_mistake", Map.of("ply", 47)))),
                ChatMessage.toolResult("tc_1", "a"),
                ChatMessage.toolResult("tc_2", "b"));
        JsonNode json = MAPPER.valueToTree(OpenAiCompatibleProvider.toWire(turn, MAPPER));
        JsonNode calls = json.get(1).get("tool_calls");
        assertThat(calls.get(0).get("id").asText()).isEqualTo("tc_1");
        assertThat(calls.get(0).get("type").asText()).isEqualTo("function");
        assertThat(calls.get(1).get("function").get("arguments").asText()).isEqualTo("{\"ply\":47}");
        assertThat(json.get(1).get("content").isNull()).isTrue();
        assertThat(json.get(2).get("tool_call_id").asText()).isEqualTo("tc_1");
        assertThat(json.get(3).get("tool_call_id").asText()).isEqualTo("tc_2");
    }

    @Test
    @DisplayName("a reply's tool calls come back typed, arguments parsed")
    void readsToolCalls() throws Exception {
        JsonNode msg = MAPPER.readTree("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_9\","
                + "\"type\":\"function\",\"function\":{\"name\":\"find_games\","
                + "\"arguments\":\"{\\\"color\\\":\\\"white\\\",\\\"limit\\\":5}\"}}]}");
        ChatResult r = OpenAiCompatibleProvider.fromWire(msg, MAPPER);
        assertThat(r.content()).isNull();
        assertThat(r.toolCalls()).containsExactly(
                new ToolCall("call_9", "find_games", Map.of("color", "white", "limit", 5)));
    }

    // ── HTTP, against a stand-in on localhost ────────────────────────────────

    @Test
    @DisplayName("sends the key as a bearer token, asks for JSON, and returns the content")
    void completion() throws Exception {
        List<String> auth = new ArrayList<>();
        List<JsonNode> bodies = new ArrayList<>();
        String base = serve("/v1/chat/completions", (a, body) -> {
            auth.add(a);
            bodies.add(body);
            return new Reply(200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}");
        });
        var provider = new OpenAiCompatibleProvider(props(base, "sk-secret", Map.of("report", 300), null), MAPPER);

        Completion c = provider.complete(CompletionRequest.structured("report-model", "Write JSON.", OutputBudget.REPORT));

        assertThat(c.text()).isEqualTo("{\"ok\":true}");
        assertThat(c.truncated()).isFalse();
        assertThat(auth).containsExactly("Bearer sk-secret");
        assertThat(bodies.get(0).get("model").asText()).isEqualTo("report-model");
        assertThat(bodies.get(0).get("response_format").get("type").asText()).isEqualTo("json_object");
        assertThat(bodies.get(0).get("max_tokens").asInt()).isEqualTo(300);
        assertThat(bodies.get(0).get("top_p").asDouble()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("a provider that rejects JSON mode is asked again without it")
    void retriesWithoutJsonMode() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        String base = serve("/v1/chat/completions", (a, body) -> {
            bodies.add(body);
            return body.has("response_format")
                    ? new Reply(400, "{\"error\":\"response_format not supported\"}")
                    : new Reply(200, "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}");
        });
        var provider = new OpenAiCompatibleProvider(props(base, null), MAPPER);

        assertThat(provider.complete(CompletionRequest.standard("m", "Write JSON.", OutputBudget.EXPLANATION)).text())
                .isEqualTo("{}");
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(1).has("response_format")).isFalse();
    }

    @Test
    @DisplayName("a failed chat turn is an empty result, never an exception into the agent")
    void chatFailureIsEmpty() throws Exception {
        String base = serve("/v1/chat/completions", (a, b) -> new Reply(500, "{\"error\":\"boom\"}"));
        var provider = new OpenAiCompatibleProvider(props(base, "k"), MAPPER);
        ChatResult r = provider.chat(new ChatRequest("m", List.of(ChatMessage.user("hi")), List.of(),
                true, OutputBudget.CHAT_ANSWER, 1, 0.3, 1.18, 4096));
        assertThat(r.content()).isNull();
        assertThat(r.wantsTools()).isFalse();
    }

    @Test
    @DisplayName("limits: generous defaults, config overrides, four times more room for a reasoning model")
    void limits() {
        var plain = new OpenAiCompatibleProvider(props("http://x/v1", null), MAPPER);
        assertThat(plain.maxTokens(OutputBudget.EXPLANATION)).isEqualTo(1024);
        assertThat(plain.maxTokens(OutputBudget.REPORT)).isEqualTo(2048);
        var overridden = new OpenAiCompatibleProvider(props("http://x/v1", null, Map.of("explanation", 300), null), MAPPER);
        assertThat(overridden.maxTokens(OutputBudget.EXPLANATION)).isEqualTo(300);
        assertThat(overridden.maxTokens(OutputBudget.REPORT)).isEqualTo(2048);
        var reasoning = new OpenAiCompatibleProvider(props("http://x/v1", null, null, true), MAPPER);
        assertThat(reasoning.maxTokens(OutputBudget.EXPLANATION)).isEqualTo(4096);
    }

    @Test
    @DisplayName("a reasoning model is sent max_completion_tokens and no sampling parameters")
    void reasoningBody() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        String base = serve("/v1/chat/completions", (a, body) -> {
            bodies.add(body);
            return new Reply(200, OK);
        });
        new OpenAiCompatibleProvider(props(base, "k", null, true), MAPPER)
                .complete(CompletionRequest.structured("o-model", "Write JSON.", OutputBudget.INSIGHT));
        JsonNode b = bodies.get(0);
        assertThat(b.get("max_completion_tokens").asInt()).isEqualTo(4096);
        assertThat(b.has("max_tokens")).isFalse();
        assertThat(b.has("temperature")).isFalse();
        assertThat(b.has("top_p")).isFalse();
    }

    @Test
    @DisplayName("finish_reason length is reported as a cut-off, even with no text at all")
    void detectsCutOff() throws Exception {
        String base = serve("/v1/chat/completions", (a, b) -> new Reply(200,
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}]}"));
        var provider = new OpenAiCompatibleProvider(props(base, "k"), MAPPER);
        Completion c = provider.complete(CompletionRequest.standard("m", "p", OutputBudget.EXPLANATION));
        assertThat(c.truncated()).isTrue();
        ChatResult r = provider.chat(new ChatRequest("m", List.of(ChatMessage.user("hi")), List.of(),
                true, OutputBudget.CHAT_ANSWER, 1, 0.3, 1.18, 4096));
        assertThat(r.truncated()).isTrue();
    }

    @Test
    @DisplayName("health asks /models and names a rejected key")
    void health() throws Exception {
        String base = serve("/v1/models", (a, b) -> new Reply(401, "{}"));
        var provider = new OpenAiCompatibleProvider(props(base, "bad"), MAPPER);
        Health h = provider.health();
        assertThat(h.ok()).isFalse();
        assertThat(h.detail()).contains("401").contains("API key");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Reply(int status, String body) {}

    private interface Handler {
        Reply handle(String authHeader, JsonNode body) throws Exception;
    }

    /** A stand-in provider on a free localhost port; returns its base URL ("…/v1"). */
    private String serve(String path, Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, ex -> {
            try {
                byte[] in = ex.getRequestBody().readAllBytes();
                JsonNode body = in.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(in);
                Reply r = handler.handle(ex.getRequestHeaders().getFirst("Authorization"), body);
                byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(r.status(), bytes.length);
                ex.getResponseBody().write(bytes);
            } catch (Exception e) {
                ex.sendResponseHeaders(500, -1);
            } finally {
                ex.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
}
