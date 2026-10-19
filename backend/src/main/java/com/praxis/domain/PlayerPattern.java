package com.praxis.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "player_patterns", indexes = {
    @Index(name = "idx_player_patterns_username", columnList = "username")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "games_analyzed", nullable = false)
    private int gamesAnalyzed;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt;

    @Column(name = "mistakes_moves_1_10")
    @Builder.Default
    private int mistakesMoves1to10 = 0;

    @Column(name = "mistakes_moves_11_20")
    @Builder.Default
    private int mistakesMoves11to20 = 0;

    @Column(name = "mistakes_moves_21_30")
    @Builder.Default
    private int mistakesMoves21to30 = 0;

    @Column(name = "mistakes_moves_31_plus")
    @Builder.Default
    private int mistakesMoves31Plus = 0;

    @Column(name = "mistakes_opening")
    @Builder.Default
    private int mistakesOpening = 0;

    @Column(name = "mistakes_middlegame")
    @Builder.Default
    private int mistakesMiddlegame = 0;

    @Column(name = "mistakes_endgame")
    @Builder.Default
    private int mistakesEndgame = 0;

    // Stored as JSON string: {"FORK": 12, "PIN": 8, ...}
    @Column(name = "motif_frequency", columnDefinition = "TEXT")
    private String motifFrequency;

    // Stored as JSON string: {"C60": {"games": 5, "avg_deviation_move": 7}, ...}
    @Column(name = "opening_accuracy", columnDefinition = "TEXT")
    private String openingAccuracy;

    @Column(name = "primary_weakness", columnDefinition = "TEXT")
    private String primaryWeakness;

    @Column(name = "secondary_weakness", columnDefinition = "TEXT")
    private String secondaryWeakness;

    @Column(name = "tertiary_weakness", columnDefinition = "TEXT")
    private String tertiaryWeakness;

    @Column(name = "critical_move_range", length = 32)
    private String criticalMoveRange;

    @Column(name = "dominant_motif", length = 32)
    private String dominantMotif;

    @Column(name = "opening_assessment", columnDefinition = "TEXT")
    private String openingAssessment;

    // ── Phase 12: facts computed in code, prose checked against them ────────

    /** Every figure the page shows, as computed (ReportFacts / PlanFacts JSON). */
    @Column(name = "facts_json", columnDefinition = "TEXT")
    private String factsJson;

    /** The model's paragraphs, only when they passed the number checker. */
    @Column(name = "prose_json", columnDefinition = "TEXT")
    private String proseJson;

    /** checked, rejected or unavailable (ReportWriter.Prose). */
    @Column(name = "prose_status", length = 16)
    private String proseStatus;

    @Column(name = "prose_model", length = 64)
    private String proseModel;

    /** What the last rejected answer got wrong; the step-7 measurement. */
    @Column(name = "prose_violations", columnDefinition = "TEXT")
    private String proseViolations;

    @Column(name = "prose_attempts")
    private Integer proseAttempts;

    @Column(name = "prose_millis")
    private Long proseMillis;
}
