package com.praxis.prax.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Ollama's /api/chat endpoint with tool calling.
 *
 * Deliberately separate from OllamaAnalysisClient, which posts to /api/generate
 * with format:json. Different endpoint, different message shape, different
 * model — the analysis model and the agent model must not compete for VRAM
 * (Reasoning Plan §2).
 */
@Service
public class OllamaChatClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatClient.class);

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public OllamaChatClient(AppProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.baseUrl = props.ollama().baseUrl();
        this.model = props.reasoningModel();
    }

    public String model() {
        return model;
    }

    /** One assistant turn: either tool calls, or a final message. */
    public record Turn(String content, List<ToolCall> toolCalls) {
        public boolean wantsTools() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    public record ToolCall(String id, String name, Map<String, Object> arguments) {}

    /**
     * @param messages full conversation so far, in Ollama's message shape
     * @param tools    JSON schemas; pass an empty list to force a text answer
     */
    public Turn chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        // An empty tool list is the agent forcing a final answer (see PraxAgent).
        boolean finalAnswer = tools.isEmpty();
        if (!finalAnswer) body.put("tools", tools);
        // Thinking variants (e.g. Qwen3-4B-Thinking) spend the whole budget inside
        // <think> and return an empty `content`. Ask them not to; harmless on
        // models that ignore it.
        body.put("think", false);
        // The final message is a contract, not prose. Without this the model
        // sometimes answers in plain text, which parses to zero citations — so
        // uncited chess claims sail past evidence validation and reach the
        // player looking exactly as authoritative as grounded ones.
        if (finalAnswer) body.put("format", "json");

        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("temperature", 0.3);
        opts.put("num_ctx", 4096);
        // Give a 4B model 1600 tokens of rope and it will use them: one run
        // repeated "Final answer: 43. Qg5 was a blunder" forty times until the
        // budget ran out. Penalise repeats, and keep the answer near the three
        // or four sentences the prompt asks for.
        opts.put("repeat_penalty", 1.18);
        // Tool turns still need headroom to reach the call after reasoning.
        opts.put("num_predict", finalAnswer ? 700 : 1600);
        body.put("options", opts);

        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("[prax] ollama chat {}: {}", res.statusCode(), res.body());
                return new Turn(null, List.of());
            }

            JsonNode root = mapper.readTree(res.body());
            JsonNode msg = root.path("message");
            String content = msg.path("content").asText(null);

            // Ollama puts reasoning in a separate `thinking` field. If the model
            // burned its budget there and emitted no content, the answer may
            // still be recoverable from the tail of the reasoning.
            String thinking = msg.path("thinking").asText(null);
            if ((content == null || content.isBlank()) && thinking != null && !thinking.isBlank()) {
                log.warn("[prax] model returned only reasoning ({} chars) — recovering from it. "
                        + "Consider an instruct model rather than a thinking one.", thinking.length());
                content = thinking;
            }
            // Some builds inline the block instead. Strip it either way.
            if (content != null && content.contains("<think>")) {
                content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
            }

            List<ToolCall> calls = new ArrayList<>();
            JsonNode tc = msg.path("tool_calls");
            if (tc.isArray()) {
                int i = 0;
                for (JsonNode node : tc) {
                    JsonNode fn = node.path("function");
                    Map<String, Object> args = new LinkedHashMap<>();
                    JsonNode a = fn.path("arguments");
                    // Ollama may send arguments as an object or as a JSON string.
                    if (a.isTextual()) {
                        try {
                            a = mapper.readTree(a.asText());
                        } catch (Exception ignored) {
                            a = mapper.createObjectNode();
                        }
                    }
                    a.fields().forEachRemaining(e ->
                            args.put(e.getKey(), e.getValue().isNumber()
                                    ? e.getValue().numberValue()
                                    : e.getValue().asText()));
                    // Index within THIS response only. The agent reassigns a
                    // run-scoped id — see PraxAgent.
                    calls.add(new ToolCall("r" + (++i), fn.path("name").asText(), args));
                }
            }
            return new Turn(content, calls);

        } catch (Exception e) {
            log.warn("[prax] ollama chat failed: {}", e.getMessage());
            return new Turn(null, List.of());
        }
    }

    public boolean isHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
