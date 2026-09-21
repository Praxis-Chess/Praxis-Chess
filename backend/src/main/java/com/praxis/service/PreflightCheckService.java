package com.praxis.service;

import com.praxis.config.AppProperties;
import com.praxis.service.voice.TtsClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;

/**
 * Runs at startup and logs clear warnings for any missing dependency so the
 * user knows what to fix before hitting Sync or Analyze.
 * Non-fatal — the app starts regardless so the user can fix one thing at a time.
 */
@Service
public class PreflightCheckService {

    private static final Logger log = LoggerFactory.getLogger(PreflightCheckService.class);

    private final DataSource dataSource;
    private final AppProperties props;
    private final TtsClient tts;

    public PreflightCheckService(DataSource dataSource, AppProperties props, TtsClient tts) {
        this.dataSource = dataSource;
        this.props = props;
        this.tts = tts;
    }

    @PostConstruct
    public void runChecks() {
        log.info("=== Praxis Chess preflight checks ===");
        checkPostgres();
        checkOllama();
        checkStockfish();
        checkTts();
        checkWebResearch();
        log.info("=== Preflight complete — open http://localhost:8086 ===");
    }

    /**
     * Web research configured but unreachable is the failure worth catching here.
     *
     * SearXNG sits behind a Docker Compose profile, so a plain `docker compose
     * up -d` brings PostgreSQL back and leaves it behind. The symptom is a
     * general question refused with "I couldn't find a source" — which reads as
     * "the web does not know" rather than "your search container is not
     * running", and costs a debugging session to tell apart.
     */
    private void checkWebResearch() {
        if (!props.webEnabled()) {
            log.info("[SKIP] Web research disabled in config");
            return;
        }
        if (!"searxng".equals(props.webProvider())) {
            log.info("[OK] Web research using provider '{}'", props.webProvider());
            return;
        }

        String url = props.searxngUrl() + "/search?format=json&q=test";
        try {
            HttpResponse<String> res = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder().uri(URI.create(url))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200) {
                log.info("[OK] Web research reachable at {}", props.searxngUrl());
            } else if (res.statusCode() == 403) {
                // The one misconfiguration that looks exactly like a network fault.
                log.warn("[WARN] SearXNG at {} returned 403 — the JSON format is not enabled. "
                        + "Check that searxng/settings.yml is mounted and contains "
                        + "`formats: [html, json]`", props.searxngUrl());
            } else {
                log.warn("[WARN] SearXNG at {} returned {} — general-knowledge questions "
                        + "will be refused", props.searxngUrl(), res.statusCode());
            }
        } catch (Exception e) {
            log.warn("[WARN] Web research is enabled but SearXNG is not reachable at {} — "
                    + "general-knowledge questions will be refused. Start it with: "
                    + "docker compose up -d searxng", props.searxngUrl());
        }
    }

    private void checkTts() {
        if (!props.ttsEnabled()) {
            log.info("[SKIP] Prax voice disabled in config");
            return;
        }
        if (tts.isHealthy()) {
            log.info("[OK] Prax TTS reachable at {}", props.ttsBaseUrl());
        } else {
            log.warn("[WARN] Prax TTS not reachable at {} — Prax will stay silent. "
                    + "Start it with: cd tts-service && python main.py", props.ttsBaseUrl());
        }
    }

    private void checkPostgres() {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SELECT 1");
            log.info("[OK] PostgreSQL reachable");
        } catch (Exception e) {
            log.error("[FAIL] PostgreSQL not reachable: {}. Run: docker-compose up -d", e.getMessage());
        }
    }

    private void checkOllama() {
        String baseUrl = props.ollama().baseUrl();
        String model   = props.ollama().moveModel() != null ? props.ollama().moveModel() : props.ollama().model();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                if (resp.body().contains(model.split(":")[0])) {
                    log.info("[OK] Ollama reachable and model '{}' found", model);
                } else {
                    log.warn("[WARN] Ollama is running but model '{}' is not pulled. Run: ollama pull {}", model, model);
                }
            } else {
                log.warn("[WARN] Ollama returned HTTP {}. Is it running? Run: ollama serve", resp.statusCode());
            }
        } catch (Exception e) {
            log.error("[FAIL] Ollama not reachable at {}. Install from https://ollama.com then run: ollama pull {}", baseUrl, model);
        }
    }

    private void checkStockfish() {
        String path = props.stockfishPath();
        if (path == null || path.isBlank()) {
            log.warn("[WARN] stockfish.path is not set in application.yml. Analysis will fail until configured.");
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            log.error("[FAIL] Stockfish not found at '{}'. Download from https://stockfishchess.org/download/", path);
        } else if (!f.canExecute()) {
            log.error("[FAIL] Stockfish at '{}' is not executable. Check file permissions.", path);
        } else {
            log.info("[OK] Stockfish found at '{}'", path);
        }
    }
}
