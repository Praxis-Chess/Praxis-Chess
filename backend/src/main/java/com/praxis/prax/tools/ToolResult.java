package com.praxis.prax.tools;

import com.praxis.prax.evidence.Evidence.Provenance;

import java.time.Instant;

/**
 * Every tool answer carries its own sample size. That is not decoration — it is
 * what lets the validator suppress a confident claim built on three games
 * (Reasoning Plan §7.2).
 */
public record ToolResult(
        String tool,
        Object data,
        int sampleSize,
        Provenance provenance,
        Instant computedAt
) {
    public static ToolResult playerData(String tool, Object data, int sampleSize) {
        return new ToolResult(tool, data, sampleSize, Provenance.PLAYER_DATA, Instant.now());
    }

    public static ToolResult engine(String tool, Object data) {
        return new ToolResult(tool, data, 0, Provenance.ENGINE, Instant.now());
    }

    /** One specific thing, not a population — sample size does not apply. */
    public static ToolResult singleObject(String tool, Object data) {
        return new ToolResult(tool, data, 0, Provenance.PLAYER_DATA, Instant.now());
    }

    /** Typed failure — never thrown into the agent loop (§5). */
    public static ToolResult error(String tool, String message) {
        return new ToolResult(tool, java.util.Map.of("error", message), 0,
                Provenance.PLAYER_DATA, Instant.now());
    }
}
