package com.praxis.ai.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.ai.*;
import com.praxis.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Ollama on this machine: the default provider for every feature.
 *
 * <p>Chat goes to {@code /api/chat} with tool calling; completions to
 * {@code /api/generate} in JSON mode. The model and the agent model must not
 * compete for VRAM, which is why features route to different models
 * (Reasoning Plan §2); this class serves whichever model it is asked for.
 */
@Component
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    /**
     * Local limits. They stop a small model rambling on a 4 GB GPU, with room to
     * spare over measured answers (2026-10-03, this library): explanations peak at
     * ~70 tokens (cap 200); pattern reports at ~170 (cap 768, shared with the
     * training plan, whose JSON reaches ~430); Prax's turns are as tuned in the
     * agent. A cut-off is detected and retried once with twice the room.
     */
    static final Map<OutputBudget, Integer> LIMITS = Map.of(
            OutputBudget.EXPLANATION, 200,
            OutputBudget.INSIGHT, 256,
            OutputBudget.REPORT, 768,
            OutputBudget.TOOL_TURN, 1600,
            OutputBudget.CHAT_ANSWER, 700);

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final RestClient rest;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OllamaProvider(AppProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.baseUrl = props.ollama() == null || props.ollama().baseUrl() == null
                ? "http://localhost:11434" : props.ollama().baseUrl();
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String id() {
        return "ollama";
    }

    @Override
    public int maxTokens(OutputBudget budget) {
        return LIMITS.get(budget);
    }

    @Override
    public String host() {
        URI u = URI.create(baseUrl);
        return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    // ── chat ─────────────────────────────────────────────────────────────────

    @Override
    public ChatResult chat(ChatRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("messages", toWire(req.messages()));
        body.put("stream", false);
        if (!req.tools().isEmpty()) body.put("tools", tools(req.tools()));
        // Thinking variants (e.g. Qwen3-4B-Thinking) spend the whole budget inside
        // <think> and return an empty `content`. Ask them not to; harmless on
        // models that ignore it.
        body.put("think", false);
        // The final message is a contract, not prose. Without this the model
        // sometimes answers in plain text, which parses to zero citations.
        if (req.jsonAnswer()) body.put("format", "json");

        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("temperature", req.temperature());
        opts.put("num_ctx", req.contextTokens());
        opts.put("repeat_penalty", req.repeatPenalty());
        opts.put("num_predict", maxTokens(req.budget()) * req.scale());
        body.put("options", opts);

        try {
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("[ai] ollama chat {}: {}", res.statusCode(), res.body());
                return ChatResult.empty();
            }
            JsonNode root = mapper.readTree(res.body());
            ChatResult r = fromWire(root.path("message"), mapper);
            return new ChatResult(r.content(), r.toolCalls(), "length".equals(root.path("done_reason").asText()));
        } catch (Exception e) {
            log.warn("[ai] ollama chat failed: {}", e.getMessage());
            return ChatResult.empty();
        }
    }

    /** Domain messages → Ollama's /api/chat shape (arguments as objects, no ids). */
    static List<Map<String, Object>> toWire(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            String role = m.role().name().toLowerCase(Locale.ROOT);
            if (!m.toolCalls().isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (ToolCall c : m.toolCalls()) {
                    calls.add(Map.of("function", Map.of("name", c.name(), "arguments", c.arguments())));
                }
                out.add(Map.of("role", role, "content", m.content(), "tool_calls", calls));
            } else {
                out.add(Map.of("role", role, "content", m.content()));
            }
        }
        return out;
    }

    /** Ollama's assistant message → the domain result. */
    static ChatResult fromWire(JsonNode msg, ObjectMapper mapper) {
        String content = msg.path("content").asText(null);

        // Ollama puts reasoning in a separate `thinking` field. If the model burned
        // its budget there and emitted no content, the answer may still be
        // recoverable from the tail of the reasoning.
        String thinking = msg.path("thinking").asText(null);
        if ((content == null || content.isBlank()) && thinking != null && !thinking.isBlank()) {
            log.warn("[ai] model returned only reasoning ({} chars), recovering from it. "
                    + "Consider an instruct model rather than a thinking one.", thinking.length());
            content = thinking;
        }
        // Some builds inline the block instead. Strip it either way.
        if (content != null && content.contains("<think>")) {
            content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        }

        List<ToolCall> calls = new ArrayList<>();
        int i = 0;
        for (JsonNode node : msg.path("tool_calls")) {
            JsonNode fn = node.path("function");
            calls.add(new ToolCall("r" + (++i), fn.path("name").asText(), arguments(fn.path("arguments"), mapper)));
        }
        return new ChatResult(content, calls);
    }

    /** Ollama sends arguments as an object or as a JSON string; numbers stay numbers. */
    static Map<String, Object> arguments(JsonNode a, ObjectMapper mapper) {
        if (a.isTextual()) {
            try {
                a = mapper.readTree(a.asText());
            } catch (Exception ignored) {
                a = mapper.createObjectNode();
            }
        }
        Map<String, Object> args = new LinkedHashMap<>();
        a.fields().forEachRemaining(e ->
                args.put(e.getKey(), e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asText()));
        return args;
    }

    static List<Map<String, Object>> tools(List<ToolSpec> specs) {
        return specs.stream()
                .map(t -> Map.<String, Object>of("type", "function", "function", Map.of(
                        "name", t.name(), "description", t.description(), "parameters", t.parameters())))
                .toList();
    }

    // ── completion ───────────────────────────────────────────────────────────

    @Override
    public Completion complete(CompletionRequest req) {
        String raw = rest.post().uri("/api/generate").body(generateBody(req, maxTokens(req.budget()) * req.scale()))
                .retrieve().body(String.class);
        try {
            JsonNode root = mapper.readTree(raw == null ? "{}" : raw);
            String text = root.path("response").asText(null);
            if (text == null) throw new IllegalStateException("Ollama returned null response");
            return new Completion(text, "length".equals(root.path("done_reason").asText()));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Ollama sent unreadable JSON", e);
        }
    }

    /**
     * /api/generate body. Generation limits go inside {@code options}: Ollama ignores
     * them at the top level, which is where they used to be sent, so until
     * 2026-10-03 no local completion was ever capped. No {@code num_ctx}: the old
     * top-level value was ignored too, so Ollama's default is what has always run,
     * and setting one would make Ollama reload the model.
     */
    static Map<String, Object> generateBody(CompletionRequest req, int maxTokens) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", req.temperature());
        options.put("top_p", req.topP());
        options.put("repeat_penalty", req.repeatPenalty());
        options.put("num_predict", maxTokens);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("prompt", req.prompt());
        body.put("stream", false);
        body.put("format", "json");
        body.put("keep_alive", "2h");
        body.put("options", options);
        return body;
    }

    // ── health ───────────────────────────────────────────────────────────────

    @Override
    public Health health() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            int status = http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
            return status == 200 ? Health.up() : Health.down("Ollama answered HTTP " + status);
        } catch (Exception e) {
            return Health.down("Ollama is not reachable at " + baseUrl);
        }
    }
}
