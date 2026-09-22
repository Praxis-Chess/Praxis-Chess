package com.praxis.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * How long one game's analysis took, broken down by stage.
 *
 * This is what lets the Settings page show a MEASURED estimate for a proposed
 * configuration rather than a guess (see AnalysisCostModel). The stages are
 * recorded separately because they scale with different settings: the sweep
 * with scan time per move, the deep check with depth and candidate lines, and
 * explanations with how many are written.
 *
 * A separate table rather than more columns on `games`: it's instrumentation,
 * one row per analysis run, and a re-analysis adds a row instead of
 * overwriting the evidence of the last one.
 */
@Entity
@Table(name = "analysis_timing", indexes = {
        @Index(name = "idx_analysis_timing_settings", columnList = "settings_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisTiming {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    /** The {@link AnalysisSettings} row the run used. */
    @Column(name = "settings_id")
    private Long settingsId;

    /** Positions evaluated in the first pass. */
    @Column(nullable = false)
    private int plies;

    /** Flagged moves given the deep check. */
    @Column(nullable = false)
    private int candidates;

    /** Written explanations returned by the model. */
    @Column(nullable = false)
    private int explanations;

    @Column(name = "sweep_ms", nullable = false)
    private long sweepMs;

    /** Wall time of the deep-check loop (overlaps the explanations). */
    @Column(name = "enrich_ms", nullable = false)
    private long enrichMs;

    /** Sum of the individual explanation calls (they run on another thread). */
    @Column(name = "explain_ms", nullable = false)
    private long explainMs;

    @Column(name = "total_ms", nullable = false)
    private long totalMs;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
