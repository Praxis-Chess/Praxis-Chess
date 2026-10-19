package com.praxis.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "training_plans")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "generated_at", nullable = false)
    private OffsetDateTime generatedAt;

    @Column(name = "based_on_games", nullable = false)
    private int basedOnGames;

    // Full structured plan JSON from Ollama
    @Column(name = "plan_json", nullable = false, columnDefinition = "TEXT")
    private String planJson;

    // JSON array string: ["C60: Ruy Lopez", ...]
    @Column(name = "openings_to_drill", columnDefinition = "TEXT")
    private String openingsToDrill;

    // JSON array string: ["FORK", "PIN", ...]
    @Column(name = "tactical_patterns", columnDefinition = "TEXT")
    private String tacticalPatterns;

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

    /**
     * Sessions done, as JSON: {"1": {"done_at": "…", "solved": 5, "total": 6}}.
     * Kept with the plan, so a new plan starts a fresh week and the old one's
     * progress stays in its history.
     */
    @Column(name = "progress_json", columnDefinition = "TEXT")
    private String progressJson;
}
