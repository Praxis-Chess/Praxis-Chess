package com.praxis.prax.grounding;

import com.praxis.prax.routing.QuestionRouter;
import com.praxis.prax.routing.QuestionRouter.Lane;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * THE GROUNDING INVARIANT.
 *
 *   A substantive factual answer may only ship if evidence entered the run
 *   through a trusted mechanism.
 *
 * The failure this exists to stop: asked "Did Magnus and Nimzovich-Larsen ever
 * have a match?", the router chose PLAYER, the model called nothing, and a
 * confident answer shipped containing an invented person — "Pal Benyamin
 * Larsen", a blend of Pal Benko and Bent Larsen. Nothing checked it, because the
 * only backstop on that lane tested whether the answer contained a DIGIT.
 *
 * That guard tested the SHAPE of a hallucination. This one tests its CAUSE.
 *
 * Pure and static: no Spring, no network, no model. Same discipline as
 * QuestionRouter, and for the same reason — a rule that decides whether the
 * player is told something must be exhaustively testable as a table.
 */
public final class GroundingPolicy {

    private GroundingPolicy() {}

    /**
     * Below this many words, with no question mark, an input is chatter rather
     * than an enquiry. "hi prax", "thanks", "ok".
     */
    static final int MIN_SUBSTANTIVE_WORDS = 4;

    /**
     * Whole-string conversational patterns.
     *
     * An EXCLUSION list, never an inclusion list — and that direction is the
     * whole design. QuestionRouter defaults toward PLAYER because a miss there
     * degrades to existing grounded behaviour. Here the asymmetry is reversed:
     *
     *   false positive (a greeting treated as factual) → one wasted search
     *   false negative (a question treated as chatter)  → a fabrication ships
     *
     * So anything not recognisably conversational is treated as substantive.
     * Listing every phrasing of a factual question is the losing game the router
     * already demonstrated; listing greetings is small and stable.
     */
    private static final List<Pattern> CONVERSATIONAL = compile(
            "^(hi|hello|hey|yo|sup|hola)( there| again| prax)*$",
            "^(thanks|thank you|ty|cheers|nice|cool|great|awesome|amazing|"
                    + "ok|okay|k|sure|right|got it|understood|i see|fair enough)( prax)?$",
            "^(bye|goodbye|see you|later|good night)( prax)?$",
            "^good (morning|afternoon|evening)( prax)?$",
            // "How you doing?" carries a question mark, so the short-input rule
            // below does not exempt it, and it was classified substantive. On its
            // own that ships — a tool ran. But had the model simply replied
            // "I'm well, thanks", the policy would have escalated a GREETING to
            // a web search. Cheap to list, and the list is small and stable.
            "^how (are|is|'?s|r) (you|u|it|things|everything)( doing| going| been)?$",
            "^how (you|u) (doing|going|been)$",
            "^how('?s| is| has) it (going|been)$",
            "^(what'?s|whats|what is) up$",
            "^how have (you|u) been$",
            "^(you )?(alright|ok|okay)( there)?$",
            // Meta-questions about Prax itself. Answered from the system prompt,
            // and no tool could ground them anyway.
            "^(who are you|what are you|what can you do|what do you do|"
                    + "how do you work|help|what should i ask)$"
    );

    /**
     * Is this worth grounding?
     *
     * Package-private rather than private so the test table can exercise it
     * directly — the classification is the risky half of this class.
     */
    static boolean isSubstantive(String question) {
        if (question == null || question.isBlank()) return false;

        String q = question.toLowerCase(Locale.ROOT)
                // Strip terminal punctuation and stray symbols so "Hi Prax!"
                // matches the same pattern as "hi prax".
                .replaceAll("[!.,;:]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        boolean asksSomething = q.contains("?");
        String bare = q.replace("?", "").trim();

        for (Pattern p : CONVERSATIONAL) {
            if (p.matcher(bare).find()) return false;
        }

        // Short and not phrased as a question — chatter.
        if (!asksSomething && bare.split("\\s+").length < MIN_SUBSTANTIVE_WORDS) {
            return false;
        }
        return true;
    }

    /**
     * The whole decision.
     *
     * @param lane             what the router chose
     * @param trustedCalls     tool calls that produced real data. NOT "calls
     *                         attempted" — a tool that errored grounds nothing.
     * @param question         the player's words, for classification
     * @param webAvailable     is web research configured and reachable?
     * @param alreadyEscalated true on the second pass, so escalation cannot loop
     * @param budgetLeftMs     wall clock remaining; a search that cannot finish
     *                         must not be started
     */
    public static Verdict assess(Lane lane, int trustedCalls, String question,
                                 boolean webAvailable, boolean alreadyEscalated,
                                 long budgetLeftMs) {

        // Evidence exists. The citation, figure and source validators already ran
        // on it; this layer has no further objection.
        if (trustedCalls > 0) {
            return Verdict.ship("grounded in " + trustedCalls + " tool result(s)");
        }

        // Nothing was consulted, but nothing needed to be.
        if (!isSubstantive(question)) {
            return Verdict.ship("conversational; nothing to ground");
        }

        // ── Ungrounded and substantive. It does not ship as written. ──

        if (alreadyEscalated) {
            return Verdict.refuse("escalation already attempted and found nothing",
                    "I couldn't find a source for that, and I won't answer it from memory.");
        }

        if (lane == Lane.GENERAL) {
            // GENERAL already pre-searches before the first model call, so a
            // second search would repeat work that just failed.
            return Verdict.refuse("general lane already searched and found nothing",
                    "I couldn't find a source for that, and I won't answer it from memory.");
        }

        // The player pointed at their OWN games, and nothing came back.
        //
        // Escalation exists to rescue MISROUTES — a world-knowledge question
        // that landed on PLAYER by default. This is not that: the router was
        // right, and the model simply failed to call a tool. Searching the web
        // for "suggest me best move I played" returned links to online move
        // calculators, which is worse than saying nothing.
        if (lane == Lane.PLAYER && QuestionRouter.hasStrongPlayerSignal(question)) {
            return Verdict.refuse("about the player's own games, but no tool returned data",
                    "That's about your own games, but I couldn't work out which position to "
                    + "look at. Point me at something specific — an opening, a colour, or "
                    + "your recent games — and I'll check.");
        }

        if (!webAvailable) {
            return Verdict.refuse("factual question, no player data, web disabled",
                    "That's a question about chess in general, and I can only answer those "
                    + "from the web — which is switched off. Ask me about your own games "
                    + "and I can show you the numbers.");
        }

        if (budgetLeftMs < MIN_ESCALATION_BUDGET_MS) {
            return Verdict.refuse("factual question but no time left to search",
                    "I couldn't find a source for that in time, and I won't answer it "
                    + "from memory.");
        }

        return Verdict.escalate("no player data for a factual question; searching the web");
    }

    /**
     * A search plus a synthesis pass needs roughly this long. Starting one with
     * less remaining buys a timeout instead of an answer.
     */
    static final long MIN_ESCALATION_BUDGET_MS = 25_000;

    private static List<Pattern> compile(String... regexes) {
        return java.util.Arrays.stream(regexes)
                .map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE))
                .toList();
    }
}
