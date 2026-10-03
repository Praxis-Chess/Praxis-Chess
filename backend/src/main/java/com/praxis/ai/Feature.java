package com.praxis.ai;

/**
 * The application's AI features, as routed by {@link ModelRouter}. Each can run on
 * Ollama (the default) or on a configured cloud provider.
 */
public enum Feature {
    /** Prax: chat with tool calling. */
    PRAX("prax"),
    /** Per-move explanations during analysis. */
    EXPLANATIONS("explanations"),
    /** Pattern report, training plan, today's insight. */
    REPORTS("reports");

    /** The name used in configuration ({@code praxis-chess.ai.use-cloud-for}). */
    public final String key;

    Feature(String key) {
        this.key = key;
    }
}
