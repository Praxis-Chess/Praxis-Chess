package com.praxis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured LLM output for the "Today" page action card.
 *
 * The model is given only a fixed `availableFields` contract — aggregate statistics only.
 * It may not reference any field outside that contract. The response is validated before
 * being returned; if validation fails the server returns a template-generated fallback.
 *
 * Schema: { title, evidence: { metric, value, sampleSize }, action, expectedMinutes }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TodayInsightDto(
        String title,
        Evidence evidence,
        String action,
        @JsonProperty("expected_minutes") int expectedMinutes
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evidence(
            String metric,    // e.g. "Endgame blunder rate"
            String value,     // e.g. "3 blunders in 5 endgames"
            @JsonProperty("sample_size") int sampleSize  // number of games/positions analysed
    ) {}

    /** True when all required fields are present and non-blank. */
    public boolean isValid() {
        return title != null && !title.isBlank()
                && action != null && !action.isBlank()
                && expectedMinutes > 0 && expectedMinutes <= 60
                && evidence != null
                && evidence.metric() != null && !evidence.metric().isBlank()
                && evidence.value() != null && !evidence.value().isBlank()
                && evidence.sampleSize() >= 0;
    }
}
