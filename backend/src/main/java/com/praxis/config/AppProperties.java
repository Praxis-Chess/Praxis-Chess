package com.praxis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "praxis-chess")
public record AppProperties(
    Ollama ollama,
    ChessCom chessCom,
    Stockfish stockfish,
    Tts tts
) {
    public record Ollama(String baseUrl, String model, String moveModel, String reportModel,
                         String reasoningModel) {}
    public record ChessCom(String username) {}
    public record Stockfish(String path) {}

    /** Local Kokoro service. Optional — Prax stays silent but functional without it. */
    public record Tts(String baseUrl, Boolean enabled) {}

    public String stockfishPath() {
        return stockfish != null && stockfish.path() != null ? stockfish.path() : "";
    }

    /**
     * The agent model is separate from the analysis model on purpose: a batch
     * run and a live conversation must not fight over 4GB of VRAM. Defaults to
     * a small tool-calling model that fits fully in VRAM (Reasoning Plan §2).
     */
    public String reasoningModel() {
        if (ollama != null && ollama.reasoningModel() != null && !ollama.reasoningModel().isBlank()) {
            return ollama.reasoningModel();
        }
        return "qwen3:4b";
    }

    public String ttsBaseUrl() {
        return tts != null && tts.baseUrl() != null ? tts.baseUrl() : "http://127.0.0.1:8087";
    }

    public boolean ttsEnabled() {
        return tts == null || tts.enabled() == null || tts.enabled();
    }
}
