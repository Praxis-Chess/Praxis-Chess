package com.praxis.service.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.ai.Feature;
import com.praxis.ai.ModelRouter;
import com.praxis.config.AppProperties;
import com.praxis.dto.SetupDto.Check;
import com.praxis.dto.SetupDto.Model;
import com.praxis.dto.SetupDto.State;
import com.praxis.dto.SetupDto.Status;
import com.praxis.service.analysis.StockfishService;
import com.praxis.service.backup.BackupService;
import com.praxis.service.settings.PlayerIdentity;
import com.praxis.service.settings.SettingsService;
import com.praxis.domain.AnalysisSettings;
import com.praxis.service.voice.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What this install needs and whether it has it: the database, Stockfish, the
 * Chess.com username, Ollama and each configured model, and the optional voice
 * and web research.
 *
 * The same checks feed the startup log ({@link com.praxis.service.PreflightCheckService})
 * and the Setup checklist in the UI, so the two can never disagree. Cheap
 * enough to poll while models download: one SELECT and one Ollama call; the
 * optional services are re-checked at most every 30 seconds.
 *
 * In the Docker install, the "models" service writes download progress to
 * {@code $PRAXIS_SETUP_DIR/models.json}; when present, it becomes the byte
 * counts on each model.
 */
@Service
public class SetupStatusService {

    private static final Logger log = LoggerFactory.getLogger(SetupStatusService.class);
    private static final long OPTIONAL_TTL_MS = 30_000;

    private final DataSource dataSource;
    private final AppProperties props;
    private final StockfishService stockfish;
    private final PlayerIdentity player;
    private final ModelRouter router;
    private final TtsClient tts;
    private final BackupService backups;
    private final SettingsService settings;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Path setupDir;

    private volatile List<Check> optional;
    private volatile long optionalAt;

    public SetupStatusService(DataSource dataSource, AppProperties props, StockfishService stockfish,
                              PlayerIdentity player, ModelRouter router, TtsClient tts, BackupService backups,
                              SettingsService settings) {
        this.dataSource = dataSource;
        this.props = props;
        this.stockfish = stockfish;
        this.player = player;
        this.router = router;
        this.tts = tts;
        this.backups = backups;
        this.settings = settings;
        String dir = System.getenv("PRAXIS_SETUP_DIR");
        this.setupDir = dir == null || dir.isBlank() ? null : Path.of(dir);
    }

    public boolean docker() {
        return setupDir != null;
    }

    public Status status() {
        List<Check> checks = new ArrayList<>();
        checks.add(database());
        checks.add(engine());
        checks.add(username());
        List<Model> models = new ArrayList<>();
        checks.add(ollama(models));
        checks.addAll(optionalChecks());
        checks.add(backups.check());
        boolean ready = checks.stream().noneMatch(c -> c.state() == State.FAIL);
        return new Status(ready, docker(), stockfish.engineName(), checks, models);
    }

    // ── checks ──────────────────────────────────────────────────────────────

    private Check database() {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SELECT 1");
            return new Check("database", "Database", State.OK, "PostgreSQL reachable", null);
        } catch (Exception e) {
            return new Check("database", "Database", State.FAIL, "PostgreSQL not reachable: " + e.getMessage(),
                    docker() ? "docker compose -f docker/compose.yaml up -d postgres" : "docker compose up -d");
        }
    }

    private Check engine() {
        String path = props.stockfishPath();
        if (stockfish.isAvailable()) {
            String name = stockfish.engineName();
            return new Check("stockfish", "Stockfish", State.OK, name != null ? name : "running", null);
        }
        if (path == null || path.isBlank()) {
            return new Check("stockfish", "Stockfish", State.FAIL, "No engine configured",
                    "Download Stockfish from stockfishchess.org/download and set praxis-chess.stockfish.path in application.yml");
        }
        File f = new File(path);
        if (!f.exists()) {
            return new Check("stockfish", "Stockfish", State.FAIL, "Not found at " + path,
                    "Fix praxis-chess.stockfish.path in application.yml");
        }
        if (!f.canExecute()) {
            return new Check("stockfish", "Stockfish", State.FAIL, "Not executable: " + path,
                    "Make the file executable (check its permissions)");
        }
        return new Check("stockfish", "Stockfish", State.FAIL, "Found at " + path + " but it did not start",
                "Check the backend log for the Stockfish error, then restart Praxis");
    }

    private Check username() {
        PlayerIdentity.Current c = player.current();
        if (c.source() == PlayerIdentity.Source.NONE) {
            return new Check("username", "Chess.com username", State.FAIL, "Not set yet",
                    "Enter it in Settings → Player");
        }
        return new Check("username", "Chess.com username", State.OK, c.username(), null);
    }

    /** Ollama itself, plus one {@link Model} row per model this install is configured to use. */
    private Check ollama(List<Model> out) {
        Map<String, String> wanted = wantedModels();
        Map<String, JsonNode> progress = downloadProgress();
        String base = props.ollama() == null ? null : props.ollama().baseUrl();

        Set<String> installed = null;
        String error = null;
        if (base != null && !base.isBlank() && !wanted.isEmpty()) {
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(base + "/api/tags"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    installed = new HashSet<>();
                    for (JsonNode m : json.readTree(res.body()).path("models")) {
                        installed.add(normalise(m.path("name").asText()));
                    }
                } else {
                    error = "Ollama answered HTTP " + res.statusCode();
                }
            } catch (Exception e) {
                error = "Ollama not reachable at " + base;
            }
        }

        int missing = 0;
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            String name = e.getKey();
            JsonNode p = progress.get(normalise(name));
            boolean present = installed != null && installed.contains(normalise(name));
            String state = present ? "present" : (p != null ? p.path("state").asText("missing") : "missing");
            if ("done".equals(state)) state = present ? "present" : "missing";
            if (!present) missing++;
            out.add(new Model(name, e.getValue(), state,
                    p == null ? 0 : p.path("completed").asLong(0),
                    p == null ? 0 : p.path("total").asLong(0),
                    p == null || p.path("message").asText("").isBlank() ? null : p.path("message").asText()));
        }

        if (wanted.isEmpty()) {
            return new Check("ollama", "AI models", State.SKIP, "Every AI feature uses the cloud provider", null);
        }
        if (installed == null) {
            return new Check("ollama", "AI models", State.WARN, error,
                    docker() ? "docker compose -f docker/compose.yaml up -d ollama"
                             : "Install Ollama from ollama.com and start it");
        }
        if (missing == 0) {
            return new Check("ollama", "AI models", State.OK, wanted.size() + " model(s) ready", null);
        }
        boolean downloading = out.stream().anyMatch(m -> m.state().equals("downloading") || m.state().equals("creating"));
        String fix = downloading ? null : docker() ? "docker compose -f docker/compose.yaml up models" : localFix(out);
        return new Check("ollama", "AI models", State.WARN,
                downloading ? "Downloading " + missing + " model(s)" : missing + " model(s) not downloaded yet", fix);
    }

    /** Outside Docker: the pull commands, and where the commentary model comes from (it is built, not pulled). */
    private String localFix(List<Model> models) {
        String commentary = props.ollama() == null ? null : props.ollama().commentaryModel();
        List<String> steps = new ArrayList<>();
        for (Model m : models) {
            if (m.state().equals("present")) continue;
            steps.add(m.name().equals(commentary)
                    ? m.name() + ": README step 3b (built from Hugging Face)"
                    : "ollama pull " + m.name());
        }
        return String.join("; ", steps);
    }

    /** name → what it is for. Features routed to a cloud provider need no local model. */
    private Map<String, String> wantedModels() {
        Map<String, String> m = new LinkedHashMap<>();
        Map<Feature, String> roles = Map.of(
                Feature.EXPLANATIONS, "Move explanations",
                Feature.REPORTS, "Pattern report, training plan",
                Feature.PRAX, "Ask Prax");
        for (Feature f : List.of(Feature.EXPLANATIONS, Feature.REPORTS, Feature.PRAX)) {
            // Phase 12: per-move explanations are off by default; their model is
            // needed only while a settings version still asks for some.
            if (f == Feature.EXPLANATIONS && !explanationsOn()) continue;
            ModelRouter.Route r = router.forFeature(f);
            if (r.cloud() || r.model() == null || r.model().isBlank()) continue;
            m.merge(r.model(), roles.get(f), (a, b) -> a + ", " + b.toLowerCase(Locale.ROOT));
        }
        String commentary = props.ollama() == null ? null : props.ollama().commentaryModel();
        if (commentary != null && !commentary.isBlank()) {
            m.merge(commentary, "Checked commentary (trained for Praxis)", (a, b) -> a + ", " + b);
        }
        return m;
    }

    private boolean explanationsOn() {
        try {
            for (String kind : List.of(AnalysisSettings.LIBRARY, AnalysisSettings.PRACTICE)) {
                Integer n = settings.active(kind).settings().getMaxExplanations();
                if (n == null || n > 0) return true;
            }
            return false;
        } catch (Exception e) {
            return true;   // unknown: list it rather than hide a model that may be needed
        }
    }

    private Map<String, JsonNode> downloadProgress() {
        Map<String, JsonNode> out = new HashMap<>();
        if (setupDir == null) return out;
        Path f = setupDir.resolve("models.json");
        try {
            if (!Files.isReadable(f)) return out;
            for (JsonNode m : json.readTree(Files.readString(f)).path("models")) {
                out.put(normalise(m.path("name").asText()), m);
            }
        } catch (Exception e) {
            log.debug("[setup] could not read {}: {}", f, e.getMessage());
        }
        return out;
    }

    /** Ollama lists "praxis-grid-2b-r3:latest" for a model created as "praxis-grid-2b-r3". */
    static String normalise(String name) {
        if (name == null) return "";
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.contains(":") ? n : n + ":latest";
    }

    private List<Check> optionalChecks() {
        List<Check> cached = optional;
        if (cached != null && System.currentTimeMillis() - optionalAt < OPTIONAL_TTL_MS) return cached;
        List<Check> fresh = List.of(voice(), web());
        optional = fresh;
        optionalAt = System.currentTimeMillis();
        return fresh;
    }

    private Check voice() {
        if (!props.ttsEnabled()) return new Check("voice", "Prax's voice", State.SKIP, "Off", null);
        if (tts.isHealthy()) return new Check("voice", "Prax's voice", State.OK, "Voice service reachable", null);
        return new Check("voice", "Prax's voice", State.WARN, "Voice service not reachable at " + props.ttsBaseUrl(),
                docker() ? "COMPOSE_PROFILES=voice in docker/.env, then up -d"
                         : "cd tts-service && python main.py");
    }

    private Check web() {
        if (!props.webEnabled()) return new Check("web", "Web research", State.SKIP, "Off", null);
        if (!"searxng".equals(props.webProvider())) {
            return new Check("web", "Web research", State.OK, "Provider: " + props.webProvider(), null);
        }
        try {
            HttpResponse<Void> res = http.send(HttpRequest.newBuilder(
                            URI.create(props.searxngUrl() + "/search?format=json&q=test"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() == 200) return new Check("web", "Web research", State.OK, "SearXNG reachable", null);
            if (res.statusCode() == 403) {
                return new Check("web", "Web research", State.WARN, "SearXNG refuses JSON (HTTP 403)",
                        "Enable `formats: [html, json]` in the SearXNG settings");
            }
            return new Check("web", "Web research", State.WARN, "SearXNG answered HTTP " + res.statusCode(), null);
        } catch (Exception e) {
            return new Check("web", "Web research", State.WARN, "SearXNG not reachable at " + props.searxngUrl(),
                    docker() ? "COMPOSE_PROFILES=web in docker/.env, then up -d" : "docker compose up -d searxng");
        }
    }
}
