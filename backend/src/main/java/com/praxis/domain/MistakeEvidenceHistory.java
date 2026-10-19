package com.praxis.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Earlier versions of {@link MistakeEvidence} rows, append-only (Phase 12).
 *
 * <p>A diagnosis is updated in place: a newer graph builder rebuilds it, a new
 * commentary model rewrites its commentary, a person relabels it. Before any of
 * those overwrite the row, the whole old row is copied here as JSON
 * ({@code row_to_json}), so nothing a run produced is lost. JSON rather than a
 * mirror of every column: the evidence table gains columns over time, and a
 * mirror would silently drop the new ones.
 *
 * <p>Never read by the app; it is history for recovery and later analysis.
 */
@Entity
@Table(name = "mistake_evidence_history", indexes = {
        @Index(name = "idx_mistake_evidence_history_position", columnList = "game_id, move_number")
})
@Getter
@Setter
@NoArgsConstructor
public class MistakeEvidenceHistory {

    @Id
    private UUID id;

    /** The {@code mistake_evidence} row this was copied from. */
    @Column(name = "evidence_id", nullable = false)
    private UUID evidenceId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "move_number", nullable = false)
    private int moveNumber;

    @Column(name = "graph_version")
    private Integer graphVersion;

    @Column(name = "archived_at", nullable = false)
    private OffsetDateTime archivedAt;

    /** rebuild, commentary, relabel. */
    @Column(nullable = false, length = 24)
    private String reason;

    /** The complete old row. */
    @Column(name = "row_json", nullable = false, columnDefinition = "TEXT")
    private String rowJson;
}
