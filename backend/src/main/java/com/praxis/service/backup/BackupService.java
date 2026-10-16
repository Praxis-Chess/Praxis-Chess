package com.praxis.service.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.config.BackupProperties;
import com.praxis.dto.BackupDto.Commands;
import com.praxis.dto.BackupDto.Overview;
import com.praxis.dto.BackupDto.Result;
import com.praxis.dto.BackupDto.Snapshot;
import com.praxis.dto.BackupDto.Verification;
import com.praxis.dto.SetupDto.Check;
import com.praxis.dto.SetupDto.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The app's side of the backups. The backup service (docker/backup/backup.sh)
 * does the work and writes index.jsonl, verify.jsonl and status.json; this
 * reads them, and asks for a snapshot by leaving a request file it answers.
 *
 * Nothing here writes or deletes a snapshot.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** The service rewrites status.json about every 10 minutes. */
    static final Duration SERVICE_STALE = Duration.ofMinutes(30);
    /** No captured data for this long is worth a warning (the service checks daily). */
    static final Duration CHECK_STALE = Duration.ofHours(48);

    private final Path dir;
    private final Path requests;
    private final boolean docker;
    private final ObjectMapper json = new ObjectMapper();

    private volatile Index cached;

    // Two constructors (the other is for tests), so Spring must be told which.
    @Autowired
    public BackupService(BackupProperties props) {
        this(resolveDir(props), props, System.getenv("PRAXIS_SETUP_DIR") != null);
    }

    BackupService(Path dir, BackupProperties props, boolean docker) {
        this.dir = dir;
        String r = props == null ? null : props.requestsDir();
        this.requests = r != null && !r.isBlank() ? Path.of(r) : dir == null ? null : dir.resolve(".requests");
        this.docker = docker;
    }

    /** Configured, or backups/dev of the repository the backend was started from. */
    static Path resolveDir(BackupProperties props) {
        if (props != null && props.dir() != null && !props.dir().isBlank()) return Path.of(props.dir().trim());
        for (String base : List.of(".", "..")) {
            Path root = Path.of(base).toAbsolutePath().normalize();
            if (Files.exists(root.resolve("docker-compose.yml")) && Files.isDirectory(root.resolve("docker"))) {
                return root.resolve("backups").resolve("dev");
            }
        }
        return null;
    }

    // ── reading ─────────────────────────────────────────────────────────────

    public Overview overview() {
        Index idx = index();
        JsonNode status = status();
        Instant lastCheck = epoch(status, "last_check_epoch");
        Verification lastVerification = idx.verifications.isEmpty() ? null : idx.verifications.getLast();
        List<Snapshot> newestFirst = idx.snapshots.reversed();
        return new Overview(dir != null, serviceRunning(status), docker, dir == null ? null : dir.toString(),
                lastCheck, text(status, "last_check_result"), text(status, "last_error"),
                newestFirst.size(), idx.snapshots.stream().mapToLong(Snapshot::bytes).sum(),
                newestFirst.isEmpty() ? null : newestFirst.getFirst(), lastVerification, newestFirst, commands());
    }

    /** The "Backups" row of the Setup checklist. Never FAIL: the app works without backups. */
    public Check check() {
        String start = docker ? "docker compose -f docker/compose.yaml up -d backup" : "docker compose up -d backup";
        if (dir == null) {
            return new Check("backups", "Backups", State.WARN, "Backup folder unknown",
                    "Set praxis-chess.backup.dir in application.yml, then " + start);
        }
        Overview o = overview();
        if (!o.serviceRunning()) {
            return new Check("backups", "Backups", State.WARN,
                    o.count() == 0 ? "No backups yet: the backup service is not running"
                                   : "Backup service not running. Last backup " + ago(o.lastCheckAt()),
                    start);
        }
        if ("error".equals(o.lastCheckResult())) {
            return new Check("backups", "Backups", State.WARN, "The last backup failed: " + o.lastError(),
                    (docker ? "docker compose -f docker/compose.yaml logs backup" : "docker compose logs backup"));
        }
        if (o.lastVerification() != null && !o.lastVerification().ok()) {
            return new Check("backups", "Backups", State.WARN,
                    "Test restore of " + o.lastVerification().file() + " failed: " + o.lastVerification().error(),
                    "Settings → Backups → Back up now; the next test restore runs within a day");
        }
        if (o.count() == 0 || o.lastCheckAt() == null) {
            return new Check("backups", "Backups", State.OK, "Taking the first backup…", null);
        }
        if (Duration.between(o.lastCheckAt(), Instant.now()).compareTo(CHECK_STALE) > 0) {
            return new Check("backups", "Backups", State.WARN, "Last backup " + ago(o.lastCheckAt()),
                    "Settings → Backups → Back up now");
        }
        StringBuilder d = new StringBuilder("Last backup ").append(ago(o.lastCheckAt()))
                .append(" · ").append(o.count()).append(o.count() == 1 ? " snapshot" : " snapshots")
                .append(" · ").append(size(o.totalBytes()));
        if (o.lastVerification() != null) d.append(" · test restore ").append(ago(o.lastVerification().verifiedAt()));
        return new Check("backups", "Backups", State.OK, d.toString(), null);
    }

    private boolean serviceRunning(JsonNode status) {
        Instant at = epoch(status, "updated_epoch");
        return at != null && Duration.between(at, Instant.now()).compareTo(SERVICE_STALE) < 0;
    }

    private JsonNode status() {
        if (dir == null) return null;
        try {
            Path f = dir.resolve("status.json");
            return Files.isReadable(f) ? json.readTree(Files.readString(f)) : null;
        } catch (Exception e) {
            log.debug("[backup] status.json unreadable: {}", e.getMessage());
            return null;
        }
    }

    private record Index(long stamp, List<Snapshot> snapshots, List<Verification> verifications) {}

    /** index.jsonl and verify.jsonl, re-read only when either file changes. */
    private Index index() {
        if (dir == null) return new Index(0, List.of(), List.of());
        Path indexFile = dir.resolve("index.jsonl");
        Path verifyFile = dir.resolve("verify.jsonl");
        long stamp = stamp(indexFile) * 31 + stamp(verifyFile);
        Index c = cached;
        if (c != null && c.stamp == stamp) return c;

        List<Verification> verifications = new ArrayList<>();
        Set<String> verified = new HashSet<>();
        for (JsonNode v : lines(verifyFile)) {
            boolean ok = v.path("ok").asBoolean(false);
            String file = v.path("file").asText(null);
            verifications.add(new Verification(file, epoch(v, "verified_epoch"), ok, text(v, "error")));
            if (ok) verified.add(file);
        }
        List<Snapshot> snapshots = new ArrayList<>();
        for (JsonNode s : lines(indexFile)) {
            String file = s.path("file").asText(null);
            if (file == null) continue;
            long rows = 0;
            for (JsonNode n : s.path("rows")) rows += n.asLong(0);
            JsonNode games = s.path("rows").path("games");
            snapshots.add(new Snapshot(file, epoch(s, "taken_epoch"), s.path("reason").asText("daily"),
                    s.path("bytes").asLong(0), rows, games.isNumber() ? games.asLong() : null, verified.contains(file)));
        }
        snapshots.sort(Comparator.comparing(Snapshot::takenAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        Index fresh = new Index(stamp, List.copyOf(snapshots), List.copyOf(verifications));
        cached = fresh;
        return fresh;
    }

    private List<JsonNode> lines(Path f) {
        List<JsonNode> out = new ArrayList<>();
        if (!Files.isReadable(f)) return out;
        try {
            for (String line : Files.readAllLines(f)) {
                if (line.isBlank()) continue;
                try {
                    out.add(json.readTree(line));
                } catch (Exception e) {
                    log.debug("[backup] skipping a bad line in {}", f.getFileName());
                }
            }
        } catch (IOException e) {
            log.debug("[backup] {} unreadable: {}", f, e.getMessage());
        }
        return out;
    }

    private static long stamp(Path f) {
        try {
            return Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() ^ Files.size(f) : 0;
        } catch (IOException e) {
            return -1;
        }
    }

    private static Instant epoch(JsonNode n, String field) {
        if (n == null) return null;
        long s = n.path(field).asLong(0);
        return s > 0 ? Instant.ofEpochSecond(s) : null;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.hasNonNull(field)) return null;
        String s = n.path(field).asText("");
        return s.isBlank() ? null : s;
    }

    private Commands commands() {
        String dc = docker ? "docker compose -f docker/compose.yaml" : "docker compose";
        String flag = docker ? "" : " --dev";
        return new Commands(
                dc + " exec backup backup list",
                dc + " exec backup backup snapshot",
                "docker/backup/restore.sh" + flag + " {file}",
                "docker/backup/restore.sh" + flag + " --side {file}",
                dc + " exec backup backup restore {file}");
    }

    // ── asking for a snapshot ───────────────────────────────────────────────

    /**
     * Asks the backup service for a snapshot and waits for its answer. Returns
     * at once, not ok, when the service is not running.
     *
     * @param reason lowercase words and dashes, recorded in the index
     */
    public Result snapshot(String reason, Duration timeout) {
        if (!reason.matches("[a-z-]+")) throw new IllegalArgumentException("bad reason: " + reason);
        if (requests == null) return failed("The backup folder is unknown. Set praxis-chess.backup.dir.");
        if (!serviceRunning(status())) {
            return failed("The backup service is not running. Start it: "
                    + (docker ? "docker compose -f docker/compose.yaml up -d backup" : "docker compose up -d backup"));
        }
        String id = UUID.randomUUID().toString();
        Path request = requests.resolve(id + ".json");
        Path answer = requests.resolve(id + ".done.json");
        try {
            Files.createDirectories(requests);
            Path tmp = requests.resolve(id + ".json.tmp");
            Files.writeString(tmp, "{\"reason\":\"" + reason + "\"}\n");
            Files.move(tmp, request, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return failed("Could not reach the backup service: " + e.getMessage());
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                if (Files.exists(answer)) {
                    JsonNode a = json.readTree(Files.readString(answer));
                    Files.deleteIfExists(answer);
                    return new Result(a.path("ok").asBoolean(false), text(a, "file"),
                            a.path("unchanged").asBoolean(false), text(a, "error"));
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            return failed("Could not read the backup service's answer: " + e.getMessage());
        }
        try {
            // Not picked up yet: withdraw it, so it does not run later unasked.
            Files.deleteIfExists(request);
        } catch (IOException ignored) {
            // the service took it after all; its answer is cleaned up within an hour
        }
        return failed("The backup service did not answer within " + timeout.toSeconds() + " seconds");
    }

    private static Result failed(String error) {
        return new Result(false, null, false, error);
    }

    // ── wording ─────────────────────────────────────────────────────────────

    static String ago(Instant at) {
        if (at == null) return "never";
        long min = Math.max(0, Duration.between(at, Instant.now()).toMinutes());
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        long h = min / 60;
        if (h < 48) return h + " h ago";
        return (h / 24) + " days ago";
    }

    static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }
}
