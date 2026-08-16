package com.praxis.service.voice;

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

/**
 * Thin client for the local Kokoro service.
 *
 * Spring proxies rather than letting the browser call :8087 directly — that keeps
 * a single origin, puts TTS config beside the Ollama settings, and lets the
 * Python service bind localhost only.
 */
@Service
public class TtsClient {

    private static final Logger log = LoggerFactory.getLogger(TtsClient.class);
    private static final int MAX_CHARS = 2000;

    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public TtsClient(AppProperties props) {
        this.props = props;
    }

    /** WAV bytes, or null when the service is unreachable or disabled. */
    public byte[] synthesize(String text, String voice, Double speed) {
        if (!props.ttsEnabled()) return null;

        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) return null;
        if (clean.length() > MAX_CHARS) clean = clean.substring(0, MAX_CHARS);

        String body = "{\"text\":" + jsonString(clean)
                + (voice != null ? ",\"voice\":" + jsonString(voice) : "")
                + (speed != null ? ",\"speed\":" + speed : "")
                + "}";

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.ttsBaseUrl() + "/tts"))
                    .header("Content-Type", "application/json")
                    // Generous: first call also pays the ~2s model load.
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                log.warn("TTS returned {} — Prax stays silent", res.statusCode());
                return null;
            }
            return res.body();
        } catch (Exception e) {
            log.warn("TTS unreachable ({}) — Prax stays silent", e.getMessage());
            return null;
        }
    }

    public boolean isHealthy() {
        if (!props.ttsEnabled()) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.ttsBaseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 && res.body().contains("\"ok\":true");
        } catch (Exception e) {
            return false;
        }
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
