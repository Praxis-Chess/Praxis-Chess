package com.praxis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the backup service (docker/backup/backup.sh) keeps its snapshots.
 *
 * @param dir         the folder holding index.jsonl and the snapshots. Unset:
 *                    {@code backups/dev} of the repository, found from the
 *                    working directory (the repository root or backend/).
 * @param requestsDir where "Back up now" leaves its request. Unset:
 *                    {@code <dir>/.requests}. The Docker install uses a shared
 *                    volume, because the app sees {@code dir} read-only.
 */
@ConfigurationProperties(prefix = "praxis-chess.backup")
public record BackupProperties(String dir, String requestsDir) {}
