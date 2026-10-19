package com.praxis.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.praxis.report.ReportFactsBuilder;
import com.praxis.domain.TrainingPlan;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record TrainingPlanDto(
    UUID id,
    OffsetDateTime generatedAt,
    int basedOnGames,
    String planJson,
    String openingsToDrill,
    String tacticalPatterns,
    /** Phase 12: the plan decided in code (PlanFacts); null for plans made before. */
    @JsonRawValue String plan,
    /** The model's explanation, only when it passed the number checker. */
    @JsonRawValue String prose,
    String proseStatus,
    String proseModel,
    /** Sessions done: {"1": {"done_at", "solved", "total"}}; null before any. */
    @JsonRawValue String progress,
    /** Each drill as an imperative of at most eight words, by cause or tactic key. Derived, not stored. */
    Map<String, String> drillShort
) {
    public static TrainingPlanDto from(TrainingPlan p) {
        boolean phase12 = p.getFactsJson() != null;
        return new TrainingPlanDto(p.getId(), p.getGeneratedAt(), p.getBasedOnGames(),
                p.getPlanJson(), p.getOpeningsToDrill(), p.getTacticalPatterns(),
                phase12 ? p.getPlanJson() : null, p.getProseJson(), p.getProseStatus(), p.getProseModel(),
                p.getProgressJson(), ReportFactsBuilder.shortDrills());
    }
}
