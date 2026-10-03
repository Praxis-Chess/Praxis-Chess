package com.praxis.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.ai.*;
import com.praxis.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Any OpenAI-compatible chat-completions endpoint: OpenAI, OpenRouter, Groq,
 * Together, DeepSeek, Mistral, Gemini's compatible endpoint, LM Studio, vLLM.
 * Used only for features {@link com.praxis.ai.ModelRouter} sends here.
 *
 * <p>The API key goes in the Authorization header and nowhere else: never
 * logged, never returned to the browser.
 */
@Component
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final long HEALTH_CACHE_MS = 60_000;

    /**
     * Cloud defaults: generous, because a provider bills only for what the model
     * writes, so a tight cap saves nothing and risks a cut-off. Overridable per
     * budget in {@code praxis-chess.ai.cloud.max-tokens}.
     */
    static final Map<OutputBudget, Integer> DEFAULTS = Map.of(
            OutputBudget.EXPLANATION, 1024,
            OutputBudget.INSIGHT, 1024,
            OutputBudget.REPORT, 2048,
            OutputBudget.TOOL_TURN, 2048,
            OutputBudget.CHAT_ANSWER, 2048);

    /** A reasoning model's hidden reasoning counts against the limit; give it this much more. */
    static final int REASONING_FACTOR = 4;

    private final AiProperties ai;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile Health cachedHealth;
    private volatile long healthCheckedAt;

    public OpenAiCompatibleProvider(AiProperties ai, ObjectMapper mapper) {
        this.ai = ai;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "openai-compatible";
    }

    @Override
    public String host() {
        return ai.cloudHost();
    }

    /** The configured limit for this budget, else the default (times four for a reasoning model). */
    @Override
    public int maxTokens(OutputBudget budget) {
        Integer configured = ai.cloudMaxTokens(budget.key());
        if (configured != null) return configured;
        return DEFAULTS.get(budget) * (ai.reasoningModel() ? REASONING_FACTOR : 1);
    }

    // ── chat ─────────────────────────────────────────────────────────────────

    @Override
    public ChatResult chat(ChatRequest req) {
        try {
            Map<String, Object> body = body(req.model(), toWire(req.messages(), mapper),
                    maxTokens(req.budget()) * req.scale(), req.temperature(), null);
            if (!req.tools().isEmpty()) body.put("tools", tools(req.tools()));
            JsonNode choice = post(body, req.jsonAnswer() && req.tools().isEmpty()).path("choices").path(0);
            ChatResult r = fromWire(choice.path("message"), mapper);
            return new ChatResult(r.content(), r.toolCalls(), truncated(choice));
        } catch (Exception e) {
            log.warn("[ai] {} chat failed: {}", host(), e.getMessage());
            return ChatResult.empty();
        }
    }

    // ── completion ───────────────────────────────────────────────────────────

    @Override
    public Completion complete(CompletionRequest req) {
        Map<String, Object> body = body(req.model(), List.of(Map.of("role", "user", "content", req.prompt())),
                maxTokens(req.budget()) * req.scale(), req.temperature(), req.topP());
        JsonNode choice = post(body, true).path("choices").path(0);
        String content = choice.path("message").path("content").asText(null);
        boolean cut = truncated(choice);
        // A reasoning model can spend the whole limit thinking and write nothing:
        // that is a cut-off, worth one retry, not a broken provider.
        if ((content == null || content.isBlank()) && !cut) {
            throw new IllegalStateException(host() + " returned no content");
        }
        return new Completion(content == null ? "" : content, cut);
    }

    /** The answer stopped at the token limit. */
    static boolean truncated(JsonNode choice) {
        return "length".equals(choice.path("finish_reason").asText());
    }

    // ── health ───────────────────────────────────────────────────────────────

    /** GET {base}/models, cached for a minute: it is called on page loads. */
    @Override
    public Health health() {
        long now = System.currentTimeMillis();
        Health cached = cachedHealth;
        if (cached != null && now - healthCheckedAt < HEALTH_CACHE_MS) return cached;
        Health h;
        if (!ai.cloudConfigured()) {
            h = Health.down("No cloud provider is configured");
        } else {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + "/models"))
                        .timeout(Duration.ofSeconds(5)).GET();
                if (ai.keySet()) req.header("Authorization", "Bearer " + ai.cloud().apiKey().trim());
                int status = http.send(req.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
                h = status / 100 == 2 ? Health.up()
                        : Health.down(host() + " answered HTTP " + status + (status == 401 ? " (check the API key)" : ""));
            } catch (Exception e) {
                h = Health.down(host() + " is not reachable");
            }
        }
        cachedHealth = h;
        healthCheckedAt = now;
        return h;
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    /**
     * A reasoning model takes {@code max_completion_tokens} and rejects sampling
     * parameters; every other model takes {@code max_tokens} and the sampling we ask for.
     */
    Map<String, Object> body(String model, List<Map<String, Object>> messages, int maxTokens,
                             double temperature, Double topP) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (ai.reasoningModel()) {
            body.put("max_completion_tokens", maxTokens);
        } else {
            body.put("temperature", temperature);
            if (topP != null) body.put("top_p", topP);
            body.put("max_tokens", maxTokens);
        }
        return body;
    }

    private JsonNode post(Map<String, Object> body, boolean json) {
        boolean jsonMode = json && ai.jsonMode();
        if (jsonMode) body.put("response_format", Map.of("type", "json_object"));
        HttpResponse<String> res = send(body);
        // Some compatible providers reject response_format: once, without it.
        if (res.statusCode() == 400 && jsonMode) {
            log.info("[ai] {} rejected response_format; retrying without it", host());
            body.remove("response_format");
            res = send(body);
        }
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(host() + " HTTP " + res.statusCode() + ": " + abbreviate(res.body()));
        }
        try {
            return mapper.readTree(res.body());
        } catch (Exception e) {
            throw new IllegalStateException(host() + " sent unreadable JSON", e);
        }
    }

    private HttpResponse<String> send(Map<String, Object> body) {
        if (!ai.cloudConfigured()) throw new IllegalStateException("No cloud provider is configured");
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (ai.keySet()) req.header("Authorization", "Bearer " + ai.cloud().apiKey().trim());
            return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (Exception e) {
            throw new IllegalStateException(host() + " is not reachable: " + e.getMessage(), e);
        }
    }

    private String base() {
        return ai.cloud().baseUrl().trim().replaceAll("/+$", "");
    }

    // ── translation (pure, tested) ───────────────────────────────────────────

    /** Domain messages → chat-completions messages; tool calls and results paired by id. */
    static List<Map<String, Object>> toWire(List<ChatMessage> messages, ObjectMapper mapper) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            switch (m.role()) {
                case ASSISTANT -> {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("role", "assistant");
                    if (m.toolCalls().isEmpty()) {
                        msg.put("content", m.content());
                    } else {
                        msg.put("content", m.content().isEmpty() ? null : m.content());
                        List<Map<String, Object>> calls = new ArrayList<>();
                        for (ToolCall c : m.toolCalls()) {
                            calls.add(Map.of("id", c.id(), "type", "function", "function", Map.of(
                                    "name", c.name(), "arguments", mapper.writeValueAsString(c.arguments()))));
                        }
                        msg.put("tool_calls", calls);
                    }
                    out.add(msg);
                }
                case TOOL -> out.add(Map.of("role", "tool", "tool_call_id", String.valueOf(m.toolCallId()),
                        "content", m.content()));
                default -> out.add(Map.of("role", m.role().name().toLowerCase(Locale.ROOT), "content", m.content()));
            }
        }
        return out;
    }

    /** A chat-completions assistant message → the domain result. */
    static ChatResult fromWire(JsonNode msg, ObjectMapper mapper) {
        String content = msg.path("content").isNull() ? null : msg.path("content").asText(null);
        List<ToolCall> calls = new ArrayList<>();
        int i = 0;
        for (JsonNode tc : msg.path("tool_calls")) {
            JsonNode fn = tc.path("function");
            String id = tc.path("id").asText("r" + (i + 1));
            i++;
            calls.add(new ToolCall(id, fn.path("name").asText(), arguments(fn.path("arguments"), mapper)));
        }
        return new ChatResult(content, calls);
    }

    private static Map<String, Object> arguments(JsonNode a, ObjectMapper mapper) {
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

    private static List<Map<String, Object>> tools(List<ToolSpec> specs) {
        return specs.stream()
                .map(t -> Map.<String, Object>of("type", "function", "function", Map.of(
                        "name", t.name(), "description", t.description(), "parameters", t.parameters())))
                .toList();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
