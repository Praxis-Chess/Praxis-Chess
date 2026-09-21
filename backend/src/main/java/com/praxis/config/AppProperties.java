package com.praxis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "praxis-chess")
public record AppProperties(
    Ollama ollama,
    ChessCom chessCom,
    Stockfish stockfish,
    Tts tts,
    Practice practice,
    Web web
) {
    public record Ollama(String baseUrl, String model, String moveModel, String reportModel,
                         String reasoningModel) {}
    public record ChessCom(String username) {}
    public record Stockfish(String path) {}

    /**
     * Web research. OFF unless switched on, because it is the only part of this
     * application that reaches outside the machine — that should be a decision,
     * not a default.
     *
     * @param provider    "searxng" (self-hosted, no key) or "brave" (key required)
     * @param maxPages    pages actually fetched and read. Each one costs both
     *                    latency and a large slice of the 4096-token context, so
     *                    the useful range is 2-3.
     * @param maxResults  search hits considered before fetching begins
     */
    public record Web(
            Boolean enabled,
            String provider,
            String searxngUrl,
            String braveApiKey,
            Integer maxPages,
            Integer maxResults,
            Integer cacheSearchHours,
            Integer cachePageDays
    ) {}

    /** Local Kokoro service. Optional — Prax stays silent but functional without it. */
    public record Tts(String baseUrl, Boolean enabled) {}

    /**
     * @param timezone IANA zone deciding when "today" rolls over. Blank means the
     *                 machine's own zone, which is correct for a local single-user
     *                 tool — set it only when the backend runs on another host.
     */
    public record Practice(String timezone) {}

    public java.time.ZoneId practiceZone() {
        if (practice != null && practice.timezone() != null && !practice.timezone().isBlank()) {
            try {
                return java.time.ZoneId.of(practice.timezone().trim());
            } catch (java.time.DateTimeException ignored) {
                // Fall through — a typo in config must not stop the app booting.
            }
        }
        return java.time.ZoneId.systemDefault();
    }

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

    // --- web research accessors ---
    // Null-tolerant throughout: the whole `web:` block is absent from existing
    // application.yml files, and a missing section must mean "off", not a
    // NullPointerException on startup.

    public boolean webEnabled() {
        return web != null && Boolean.TRUE.equals(web.enabled());
    }

    public String webProvider() {
        return web != null && web.provider() != null && !web.provider().isBlank()
                ? web.provider().trim().toLowerCase() : "searxng";
    }

    public String searxngUrl() {
        return web != null && web.searxngUrl() != null && !web.searxngUrl().isBlank()
                ? web.searxngUrl().trim() : "http://localhost:8888";
    }

    public String braveApiKey() {
        return web != null && web.braveApiKey() != null ? web.braveApiKey().trim() : "";
    }

    /** Two by default, not three: the third page rarely adds anything the first two missed. */
    public int webMaxPages() {
        return clamp(web == null ? null : web.maxPages(), 2, 1, 4);
    }

    public int webMaxResults() {
        return clamp(web == null ? null : web.maxResults(), 5, 1, 10);
    }

    public int webCacheSearchHours() {
        return clamp(web == null ? null : web.cacheSearchHours(), 24, 1, 24 * 30);
    }

    public int webCachePageDays() {
        return clamp(web == null ? null : web.cachePageDays(), 7, 1, 365);
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        if (value == null) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    public String ttsBaseUrl() {
        return tts != null && tts.baseUrl() != null ? tts.baseUrl() : "http://127.0.0.1:8087";
    }

    public boolean ttsEnabled() {
        return tts == null || tts.enabled() == null || tts.enabled();
    }
}
