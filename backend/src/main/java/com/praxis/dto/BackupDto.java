package com.praxis.dto;

import java.time.Instant;
import java.util.List;

/**
 * What the backup service has done, read from the files it writes
 * (docker/backup/backup.sh). Records, so Jackson's SNAKE_CASE applies.
 */
public final class BackupDto {

    private BackupDto() {}

    /**
     * One snapshot from index.jsonl.
     *
     * @param reason   daily, manual, before-reanalysis, before-restore, imported
     * @param rows     rows across all tables
     * @param games    rows in the games table, or null if the dump had none
     * @param verified a test restore of this file passed
     */
    public record Snapshot(String file, Instant takenAt, String reason, long bytes,
                           long rows, Long games, boolean verified) {}

    public record Verification(String file, Instant verifiedAt, boolean ok, String error) {}

    /**
     * Commands to copy, with {@code {file}} where a snapshot name goes.
     *
     * @param restoreDirect the same restore without the bash script (Windows
     *                      PowerShell); the app must be stopped first
     */
    public record Commands(String list, String snapshot, String restore, String restoreSide,
                           String restoreDirect) {}

    /**
     * @param configured     the backup folder is known
     * @param serviceRunning the backup service wrote its status in the last 30 minutes
     * @param lastCheckAt    the last time the data was captured: a new snapshot or
     *                       "unchanged since the newest one"
     * @param snapshots      newest first
     */
    public record Overview(boolean configured, boolean serviceRunning, boolean docker, String dir,
                           Instant lastCheckAt, String lastCheckResult, String lastError,
                           int count, long totalBytes, Snapshot latest, Verification lastVerification,
                           List<Snapshot> snapshots, Commands commands) {}

    /**
     * @param file      the new snapshot, or the newest one when nothing changed
     * @param unchanged nothing changed since that snapshot, so no file was written
     */
    public record Result(boolean ok, String file, boolean unchanged, String error) {}
}
