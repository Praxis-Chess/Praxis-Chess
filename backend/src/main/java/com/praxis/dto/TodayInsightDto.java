package com.praxis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The "Today" page action card. Built deterministically from engine-measured
 * counts by TodayInsightService; no model writes it.
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
