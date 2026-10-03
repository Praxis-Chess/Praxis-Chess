package com.praxis.ai;

import java.util.Locale;

/**
 * What kind of answer a call expects, rather than a token count. Each provider
 * turns this into its own limit: tokens differ in size between tokenizers, a
 * local model is capped to stop it rambling on a 4 GB GPU, and a cloud model
 * bills only for what it writes, so a tight cap there only risks a cut-off.
 */
public enum OutputBudget {
    /** A per-move explanation: a sentence or two plus a motif, as JSON. */
    EXPLANATION,
    /** Today's insight: a short headline with evidence, as JSON. */
    INSIGHT,
    /** The pattern report or the training plan, as JSON. */
    REPORT,
    /** A Prax turn that may call tools. */
    TOOL_TURN,
    /** Prax's final answer, as the JSON contract. */
    CHAT_ANSWER;

    /** The configuration key: "explanation", "tool_turn", and so on. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
