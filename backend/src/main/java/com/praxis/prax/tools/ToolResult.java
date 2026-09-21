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
        Instant computedAt,
        /**
         * Things to SHOW, built in Java from this result.
         *
         * Deliberately separate from `data`: only `data` is serialised into the
         * tool message the model reads, so artifacts cost no context and cannot
         * be quoted, paraphrased or altered by the model. They travel alongside
         * the answer to the UI instead.
         */
        java.util.List<com.praxis.prax.artifact.PraxArtifact> artifacts
) {
    public static ToolResult playerData(String tool, Object data, int sampleSize) {
        return new ToolResult(tool, data, sampleSize, Provenance.PLAYER_DATA,
                Instant.now(), java.util.List.of());
    }

    public static ToolResult engine(String tool, Object data) {
        return new ToolResult(tool, data, 0, Provenance.ENGINE, Instant.now(), java.util.List.of());
    }

    /** One specific thing, not a population — sample size does not apply. */
    public static ToolResult singleObject(String tool, Object data) {
        return new ToolResult(tool, data, 0, Provenance.PLAYER_DATA,
                Instant.now(), java.util.List.of());
    }

    /** Same result, now carrying something to render. */
    public ToolResult withArtifacts(java.util.List<com.praxis.prax.artifact.PraxArtifact> added) {
        java.util.List<com.praxis.prax.artifact.PraxArtifact> kept = added == null
                ? java.util.List.of()
                : added.stream().filter(java.util.Objects::nonNull).toList();
        return new ToolResult(tool, data, sampleSize, provenance, computedAt, kept);
    }

    /**
     * External web material. Sample size is meaningless for prose, and the
     * provenance keeps it out of the evidence table by construction.
     */
    public static ToolResult web(String tool, Object data) {
        return new ToolResult(tool, data, 0, Provenance.WEB, Instant.now(), java.util.List.of());
    }

    /** Typed failure — never thrown into the agent loop (§5). */
    public static ToolResult error(String tool, String message) {
        return new ToolResult(tool, java.util.Map.of("error", message), 0,
                Provenance.PLAYER_DATA, Instant.now(), java.util.List.of());
    }
}
