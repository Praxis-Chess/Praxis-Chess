package com.praxis.config;

import com.praxis.ai.Feature;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * An optional cloud model provider, used per feature. {@link com.praxis.ai.ModelRouter}
 * reads this. Ollama on this machine is the default for everything; nothing
 * leaves the laptop unless a feature is listed in {@code use-cloud-for} AND the
 * provider is configured.
 *
 * <pre>
 * praxis-chess:
 *   ai:
 *     use-cloud-for: [prax, explanations, reports]   # any subset; empty = all local
 *     cloud:
 *       base-url: https://api.openai.com/v1           # any OpenAI-compatible endpoint
 *       api-key: ${PRAXIS_AI_API_KEY:}                # never in git, never sent to the browser
 *       model: gpt-4o-mini                            # default for every feature
 *       prax-model:                                   # optional per-feature overrides
 *       explanation-model:
 *       report-model:
 *       max-tokens:                                   # optional, per answer kind
 *         report: 4096
 *       reasoning: false                              # true for o-series, R1 and similar
 * </pre>
 *
 * "OpenAI-compatible" covers OpenAI, OpenRouter, Groq, Together, DeepSeek,
 * Mistral, Gemini's compatible endpoint, and local servers such as LM Studio or
 * vLLM (which need no key).
 *
 * The trained commentary model and the evidence lab stay on Ollama always: they
 * run this project's own fine-tuned files.
 *
 * @param useCloudFor features sent to the cloud: "prax", "explanations", "reports"
 */
@ConfigurationProperties(prefix = "praxis-chess.ai")
public record AiProperties(Cloud cloud, List<String> useCloudFor) {

    /**
     * @param jsonMode  send {@code response_format: json_object} when a JSON answer is
     *                  needed. On by default; switch off for a provider that rejects it.
     * @param maxTokens per {@link com.praxis.ai.OutputBudget} key ("explanation",
     *                  "insight", "report", "tool_turn", "chat_answer"); unset keys use
     *                  the provider's defaults
     * @param reasoning a reasoning model (o-series, DeepSeek-R1, …): its hidden reasoning
     *                  counts against the limit, so it gets more room, is sent
     *                  {@code max_completion_tokens}, and no sampling parameters
     */
    public record Cloud(String baseUrl, String apiKey, String model, String praxModel,
                        String explanationModel, String reportModel, Boolean jsonMode,
                        java.util.Map<String, Integer> maxTokens, Boolean reasoning) {}

    /** A base URL and a model: enough to send a request. A key is optional (local servers). */
    public boolean cloudConfigured() {
        return cloud != null && !blank(cloud.baseUrl()) && !blank(cloud.model());
    }

    /** Listed for the cloud. Not the same as used: see {@link #usesCloud}. */
    public boolean requested(Feature f) {
        return useCloudFor != null && useCloudFor.stream()
                .anyMatch(s -> s != null && f.key.equals(s.trim().toLowerCase(Locale.ROOT)));
    }

    /** This feature goes to the cloud: listed, and the provider is configured. */
    public boolean usesCloud(Feature f) {
        return cloudConfigured() && requested(f);
    }

    public String cloudModel(Feature f) {
        if (cloud == null) return null;
        String specific = switch (f) {
            case PRAX -> cloud.praxModel();
            case EXPLANATIONS -> cloud.explanationModel();
            case REPORTS -> cloud.reportModel();
        };
        return blank(specific) ? cloud.model() : specific.trim();
    }

    public boolean keySet() {
        return cloud != null && !blank(cloud.apiKey());
    }

    /** A configured token limit for this budget key, or null to use the default. */
    public Integer cloudMaxTokens(String budgetKey) {
        if (cloud == null || cloud.maxTokens() == null) return null;
        Integer v = cloud.maxTokens().get(budgetKey);
        return v == null || v <= 0 ? null : v;
    }

    public boolean reasoningModel() {
        return cloud != null && Boolean.TRUE.equals(cloud.reasoning());
    }

    public boolean jsonMode() {
        return cloud == null || cloud.jsonMode() == null || cloud.jsonMode();
    }

    /** The provider's host, for the privacy notice: "api.openai.com". */
    public String cloudHost() {
        if (cloud == null || blank(cloud.baseUrl())) return null;
        try {
            String host = URI.create(cloud.baseUrl().trim()).getHost();
            return host == null ? cloud.baseUrl().trim() : host;
        } catch (IllegalArgumentException e) {
            return cloud.baseUrl().trim();
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
