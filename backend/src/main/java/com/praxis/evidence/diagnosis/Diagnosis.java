package com.praxis.evidence.diagnosis;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed, cited reasoning about one mistake (§8.1) — what a model must produce and
 * what {@link DiagnosisRules} produces as the baseline and the fallback.
 *
 * <p>The chain comes first, so the labels and the prose are conditioned on it:
 * reason, then answer. It is visible, typed output rather than hidden thinking,
 * because hidden thinking cannot be checked and this can — every claim has a
 * type with a fixed argument schema, and {@link DiagnosisVerifier} tests each one
 * against the graph.
 *
 * <p>The taxonomy lives in strings, not Java enums mapped to columns. Hibernate
 * turns a string enum into a CHECK constraint that {@code ddl-auto: update} never
 * alters, and adding a value later then fails at insert time — a trap this
 * codebase has hit four times (§7.4). The closed sets below are enforced by the
 * verifier instead.
 */
public record Diagnosis(
        List<Claim> reasoningChain,
        String consequence,
        String mechanism,
        String motif,
        String criticalResponse,
        String visibility,
        String explanation
) {

    /** One reasoning step: a claim type, its arguments, the graph IDs it rests on. */
    public record Claim(String type, Map<String, Object> args, List<String> cites, String text) {}

    // ── the closed vocabularies (§7.1, §8.2) ───────────────────────────────────

    public static final Set<String> CONSEQUENCES = Set.of(
            "MATED", "LOST_MATERIAL", "MISSED_MATE", "MISSED_MATERIAL", "NOT_CONCRETE");

    /** The six mechanism rules. UNCLEAR and NONE are outcomes, not rules. */
    public static final List<String> MECHANISM_RULES = List.of(
            "IGNORED_THREAT", "REMOVED_DEFENDER", "MOVED_INTO_ATTACK",
            "LOSING_CAPTURE", "CREATED_TACTIC", "MISSED_OPPORTUNITY");

    /**
     * UNCLEAR: a concrete consequence no rule explains, or more than one rule
     * does — the composite subset. NONE: abstention, for NOT_CONCRETE mistakes,
     * which V1 does not try to explain (positional → V2).
     */
    public static final Set<String> MECHANISMS = Set.of(
            "IGNORED_THREAT", "REMOVED_DEFENDER", "MOVED_INTO_ATTACK", "LOSING_CAPTURE",
            "CREATED_TACTIC", "MISSED_OPPORTUNITY", "UNCLEAR", "NONE");

    public static final Set<String> MOTIFS = Set.of(
            "FORK", "PIN", "SKEWER", "BACK_RANK", "DISCOVERED_ATTACK",
            "HANGING_PIECE", "POSITIONAL", "OTHER");

    public static final Set<String> VISIBILITIES = Set.of("SHALLOW", "MEDIUM", "DEEP", "NEVER");

    public static final Set<String> CLAIM_TYPES = Set.of(
            "THREAT_EXISTS", "ATTACK_DEFENSE", "DEFENDER_REMOVED", "MOVED_INTO_ATTACK",
            "LOSING_CAPTURE", "BLOCKS_LINE", "OPENS_LINE", "DOES_NOT_ADDRESS",
            "CRITICAL_REPLY", "MATERIAL_CHANGE", "MATE_IN", "COUNTERFACTUAL",
            "TACTIC_GEOMETRY", "VISIBILITY");

    /**
     * Required arguments per claim type — the schema §8.2 says each type has.
     * Phase 5 hands this to Ollama as a JSON schema so a model cannot emit a
     * claim with a missing field; the verifier enforces it regardless.
     */
    public static final Map<String, Set<String>> REQUIRED_ARGS = Map.ofEntries(
            Map.entry("THREAT_EXISTS", Set.of("move")),
            Map.entry("ATTACK_DEFENSE", Set.of("square", "attackers", "defenders")),
            Map.entry("DEFENDER_REMOVED", Set.of("square", "defender")),
            Map.entry("MOVED_INTO_ATTACK", Set.of("square")),
            Map.entry("LOSING_CAPTURE", Set.of("move")),
            Map.entry("BLOCKS_LINE", Set.of("slider", "target")),
            Map.entry("OPENS_LINE", Set.of("slider", "target")),
            Map.entry("DOES_NOT_ADDRESS", Set.of("move", "threat")),
            Map.entry("CRITICAL_REPLY", Set.of("move")),
            Map.entry("MATERIAL_CHANGE", Set.of("amount")),
            Map.entry("MATE_IN", Set.of("n")),
            Map.entry("COUNTERFACTUAL", Set.of("alt_move", "effect")),
            Map.entry("TACTIC_GEOMETRY", Set.of("kind", "by", "targets")),
            Map.entry("VISIBILITY", Set.of("band")));

    public static final Set<String> COUNTERFACTUAL_EFFECTS = Set.of(
            "REPLY_ILLEGAL", "REPLY_FAILS", "WINS_MATERIAL", "MATES");

    public static final int MAX_CHAIN = 7;
}
