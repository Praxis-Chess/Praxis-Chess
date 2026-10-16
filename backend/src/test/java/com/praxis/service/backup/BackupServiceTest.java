package com.praxis.service.backup;

import com.praxis.config.BackupProperties;
import com.praxis.dto.BackupDto.Overview;
import com.praxis.dto.BackupDto.Result;
import com.praxis.dto.SetupDto.Check;
import com.praxis.dto.SetupDto.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app reads what docker/backup/backup.sh writes. These files are that
 * script's exact line formats.
 */
@DisplayName("Backups as the app sees them")
class BackupServiceTest {

    @TempDir Path dir;

    private BackupService service() {
        return new BackupService(dir, new BackupProperties(null, null), false);
    }

    private static long ago(Duration d) {
        return Instant.now().minus(d).getEpochSecond();
    }

    private void status(long updated, long lastCheck, String result, String error) throws Exception {
        Files.writeString(dir.resolve("status.json"), ("{\"service\":\"running\",\"updated_at\":\"x\",\"updated_epoch\":%d,"
                + "\"last_check_at\":\"x\",\"last_check_epoch\":%d,\"last_check_result\":\"%s\",\"last_error\":%s,"
                + "\"last_snapshot\":null,\"last_verify_at\":null,\"last_verify_ok\":null,\"last_verify_error\":null,"
                + "\"interval_hours\":24,\"verify_days\":7}\n")
                .formatted(updated, lastCheck, result, error == null ? "null" : "\"" + error + "\""));
    }

    private void snapshot(String file, long taken, String reason, long bytes, int games) throws Exception {
        Files.writeString(dir.resolve("index.jsonl"), ("{\"file\":\"%s\",\"taken_at\":\"x\",\"taken_epoch\":%d,\"reason\":\"%s\","
                + "\"bytes\":%d,\"sha256\":\"ab\",\"content_sha256\":\"cd\",\"rows\":{\"games\":%d,\"move_errors\":40},"
                + "\"seconds\":2,\"pg_version\":\"17.6\",\"app_version\":null}\n")
                .formatted(file, taken, reason, bytes, games), java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private void verification(String file, boolean ok, String error) throws Exception {
        Files.writeString(dir.resolve("verify.jsonl"), ("{\"file\":\"%s\",\"verified_at\":\"x\",\"verified_epoch\":%d,"
                + "\"ok\":%s,\"tables\":30,\"rows\":52,\"seconds\":4,\"error\":%s}\n")
                .formatted(file, ago(Duration.ofDays(1)), ok, error == null ? "null" : "\"" + error + "\""),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    @Test
    void readsTheIndexNewestFirstWithTotalsAndVerifiedFlags() throws Exception {
        snapshot("praxis-20261013-090000.sql.gz", ago(Duration.ofDays(2)), "daily", 2_000_000, 10);
        snapshot("praxis-20261014-090000.sql.gz", ago(Duration.ofDays(1)), "manual", 3_000_000, 12);
        verification("praxis-20261013-090000.sql.gz", true, null);
        status(ago(Duration.ofMinutes(5)), ago(Duration.ofHours(3)), "unchanged", null);

        Overview o = service().overview();

        assertThat(o.serviceRunning()).isTrue();
        assertThat(o.count()).isEqualTo(2);
        assertThat(o.totalBytes()).isEqualTo(5_000_000);
        assertThat(o.latest().file()).isEqualTo("praxis-20261014-090000.sql.gz");
        assertThat(o.latest().games()).isEqualTo(12);
        assertThat(o.latest().rows()).isEqualTo(52);
        assertThat(o.latest().verified()).isFalse();
        assertThat(o.snapshots().get(1).verified()).isTrue();
        assertThat(o.commands().restore()).isEqualTo("docker/backup/restore.sh --dev {file}");
    }

    @Test
    void anUnchangedDayStillCountsAsABackup() throws Exception {
        snapshot("praxis-20261001-090000.sql.gz", ago(Duration.ofDays(14)), "daily", 2_097_152, 10);
        verification("praxis-20261001-090000.sql.gz", true, null);
        status(ago(Duration.ofMinutes(5)), ago(Duration.ofHours(3)), "unchanged", null);

        Check c = service().check();

        assertThat(c.state()).isEqualTo(State.OK);
        assertThat(c.detail()).startsWith("Last backup 3 h ago · 1 snapshot · 2.0 MB · test restore");
    }

    @Test
    void warnsWhenTheServiceIsNotRunningWithTheCommandToStartIt() throws Exception {
        status(ago(Duration.ofHours(2)), ago(Duration.ofHours(3)), "created", null);
        Check c = service().check();
        assertThat(c.state()).isEqualTo(State.WARN);
        assertThat(c.fix()).isEqualTo("docker compose up -d backup");
    }

    @Test
    void warnsAfterTwoDaysWithoutABackupAndOnAFailedTestRestore() throws Exception {
        snapshot("praxis-20261001-090000.sql.gz", ago(Duration.ofDays(3)), "daily", 1_000, 10);
        status(ago(Duration.ofMinutes(1)), ago(Duration.ofDays(3)), "created", null);
        assertThat(service().check().state()).isEqualTo(State.WARN);

        status(ago(Duration.ofMinutes(1)), ago(Duration.ofHours(1)), "created", null);
        verification("praxis-20261001-090000.sql.gz", false, "row counts differ");
        Check c = service().check();
        assertThat(c.state()).isEqualTo(State.WARN);
        assertThat(c.detail()).contains("Test restore").contains("row counts differ");
    }

    @Test
    void neverFailsTheChecklistEvenWhenTheLastBackupFailed() throws Exception {
        status(ago(Duration.ofMinutes(1)), ago(Duration.ofMinutes(1)), "error", "pg_dump failed");
        Check c = service().check();
        assertThat(c.state()).isEqualTo(State.WARN);
        assertThat(c.detail()).contains("pg_dump failed");
    }

    @Test
    void backUpNowLeavesARequestAndReturnsTheServicesAnswer() throws Exception {
        status(ago(Duration.ofMinutes(1)), ago(Duration.ofHours(1)), "created", null);
        Path requests = dir.resolve(".requests");
        Files.createDirectories(requests);

        // Stands in for backup.sh: take the request, answer it.
        CompletableFuture<String> reason = CompletableFuture.supplyAsync(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    try (var files = Files.list(requests)) {
                        Path r = files.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".done.json"))
                                .findFirst().orElse(null);
                        if (r != null) {
                            String body = Files.readString(r);
                            String id = r.getFileName().toString().replace(".json", "");
                            Files.delete(r);
                            Files.writeString(requests.resolve(id + ".done.json"),
                                    "{\"ok\":true,\"file\":\"praxis-20261015-100000.sql.gz\",\"unchanged\":false,\"error\":null}\n");
                            return body;
                        }
                    }
                    Thread.sleep(50);
                }
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Result r = service().snapshot("before-reanalysis", Duration.ofSeconds(10));

        assertThat(r.ok()).isTrue();
        assertThat(r.file()).isEqualTo("praxis-20261015-100000.sql.gz");
        assertThat(reason.get()).contains("\"reason\":\"before-reanalysis\"");
        try (var left = Files.list(requests)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void backUpNowFailsAtOnceWhenTheServiceIsNotRunning() {
        long start = System.nanoTime();
        Result r = service().snapshot("manual", Duration.ofSeconds(30));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("docker compose up -d backup");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void sizesAndAgesReadNaturally() {
        assertThat(BackupService.size(500)).isEqualTo("1 KB");
        assertThat(BackupService.size(41 * 1048576L)).isEqualTo("41.0 MB");
        assertThat(BackupService.ago(Instant.now().minus(Duration.ofDays(3)))).isEqualTo("3 days ago");
    }
}
