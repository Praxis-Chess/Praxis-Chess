package com.praxis.prax.grounding;

/**
 * Whether an answer is allowed to leave the pipeline.
 *
 * Separate from routing on purpose. The router decides which tools a question
 * may USE; this decides whether what came back may SHIP. Conflating the two is
 * what let a fabricated name reach the player: the router put the question on
 * the PLAYER lane, the model called nothing, and no one asked whether the answer
 * rested on anything.
 */
public record Verdict(Decision decision, String reason, String playerMessage) {

    public enum Decision {
        /** Evidence was acquired, or the exchange was conversational. */
        SHIP,
        /** Ungrounded but externally answerable — search and try again. */
        ESCALATE,
        /** Ungrounded and unanswerable. The answer does not leave. */
        REFUSE
    }

    public static Verdict ship(String reason) {
        return new Verdict(Decision.SHIP, reason, null);
    }

    public static Verdict escalate(String reason) {
        return new Verdict(Decision.ESCALATE, reason, null);
    }

    /**
     * @param playerMessage replaces the answer entirely. It must NOT hedge —
     *                      "I think they never played, but I can't confirm"
     *                      ships the claim with a disclaimer attached, and
     *                      disclaimers are not read.
     */
    public static Verdict refuse(String reason, String playerMessage) {
        return new Verdict(Decision.REFUSE, reason, playerMessage);
    }

    public boolean isShip()     { return decision == Decision.SHIP; }
    public boolean isEscalate() { return decision == Decision.ESCALATE; }
    public boolean isRefuse()   { return decision == Decision.REFUSE; }
}
