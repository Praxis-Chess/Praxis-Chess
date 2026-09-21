package com.praxis.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Daily metric snapshot for the Progress page.
 *
 * Captures a rolling summary of key stats per calendar day so we can plot
 * per-motif accuracy trends without re-querying the full attempt history.
 * One row per (username, date), upserted as work happens.
 *
 * This is also the practice ledger the streak is derived from. Deliberately not
 * a second table: two tables keyed (username, date) both meaning "what happened
 * that day" would eventually disagree, and this one was already being written on
 * the drill path.
 */
@Entity
@Table(name = "metric_snapshots",
       uniqueConstraints = @UniqueConstraint(columnNames = {"username", "snapshot_date"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    // --- Daily session stats ---

    @Column(name = "cards_reviewed")
    @Builder.Default
    private int cardsReviewed = 0;

    @Column(name = "cards_correct")
    @Builder.Default
    private int cardsCorrect = 0;

    /** Cards with AGAIN rating (lapsed or not recalled). */
    @Column(name = "cards_again")
    @Builder.Default
    private int cardsAgain = 0;

    // --- Per-phase counts ---
    @Column(name = "opening_correct")  @Builder.Default private int openingCorrect  = 0;
    @Column(name = "opening_total")    @Builder.Default private int openingTotal    = 0;
    @Column(name = "middlegame_correct") @Builder.Default private int middlegameCorrect = 0;
    @Column(name = "middlegame_total") @Builder.Default private int middlegameTotal = 0;
    @Column(name = "endgame_correct")  @Builder.Default private int endgameCorrect  = 0;
    @Column(name = "endgame_total")    @Builder.Default private int endgameTotal    = 0;

    /** Average interval of cards reviewed today (proxy for deck maturity). */
    @Column(name = "avg_interval_days")
    private Double avgIntervalDays;

    // --- Practice ledger ---
    //
    // A row now means "something happened on this day", which is broader than the
    // drill statistics above. An analysis-only day has cardsReviewed = 0 and a
    // populated `activities`, so nothing may infer drills from a row existing.

    /** CSV of MeaningfulActivity. Null on rows written before the ledger existed. */
    @Column(name = "activities", length = 255)
    private String activities;

    /**
     * Analysis runs finished today. Not games — Re-analyze All is one run.
     *
     * Integer, not int: ddl-auto adds this column to rows that already exist and
     * leaves them NULL, and Hibernate cannot assign null to a primitive. Read it
     * through {@link #analysisRunCount()} rather than the raw getter.
     */
    @Column(name = "analysis_runs")
    @Builder.Default
    private Integer analysisRuns = 0;

    public int analysisRunCount() {
        return analysisRuns == null ? 0 : analysisRuns;
    }

    /** True when this day counts toward the practice streak. */
    public boolean isPracticeDay() {
        return activities != null && !activities.isBlank();
    }

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
