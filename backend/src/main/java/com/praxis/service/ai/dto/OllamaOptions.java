package com.praxis.service.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ollama generation options. Passed as the `options` field in the generate request.
 */
public record OllamaOptions(
        double temperature,
        @JsonProperty("top_p") double topP,
        @JsonProperty("repeat_penalty") double repeatPenalty
) {
    /** Low-temperature preset for structured JSON output (Phase 5 contract). */
    public static OllamaOptions structured() {
        return new OllamaOptions(0.2, 0.9, 1.1);
    }

    /** Standard preset for explanations. */
    public static OllamaOptions standard() {
        return new OllamaOptions(0.3, 0.95, 1.0);
    }
}
