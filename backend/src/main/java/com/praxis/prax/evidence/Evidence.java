package com.praxis.prax.evidence;

/**
 * A single quantitative claim, traceable to the tool call that produced it.
 *
 * Reasoning Plan §6 — the three provenance classes must never blur. The type
 * carries the class so the validator can enforce it rather than the prompt
 * merely requesting it.
 */
public record Evidence(
        String label,
        String value,
        int sampleSize,
        Provenance source,
        /** Which tool call produced this. Unresolvable ids are dropped (§7). */
        String toolCallId
) {
    public enum Provenance {
        /** PostgreSQL + deterministic analyzers. Stated as fact. */
        PLAYER_DATA,
        /** Stockfish. Stated as fact, cited to the engine. */
        ENGINE,
        /** Pretrained or curated corpus. Stated as general chess knowledge. */
        KNOWLEDGE
    }

    /**
     * Below this, an AGGREGATE player-data claim is noise rather than a finding
     * (§7.2). Single-object lookups report sampleSize 0 — a move number from one
     * game is a fact, not a statistic, and tagging it "(only 1 games)" was
     * nonsense.
     */
    public static final int MIN_SAMPLE = 5;

    public boolean isUnderpowered() {
        return source == Provenance.PLAYER_DATA && sampleSize > 0 && sampleSize < MIN_SAMPLE;
    }
}
