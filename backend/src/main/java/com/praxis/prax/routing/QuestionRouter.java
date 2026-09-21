package com.praxis.prax.routing;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides, before any model runs, whether a question is about the player's own
 * chess or about the world.
 *
 * DELIBERATELY NOT AN LLM CALL. The lesson this codebase keeps re-learning is
 * that prompt rules do not hold on a small local model but structural changes
 * do. A classification pass would add latency, a second failure mode, and the
 * very tool-selection weakness it was meant to fix. This is a pure function
 * instead: no network, no database, no Ollama, and therefore testable as a
 * table.
 *
 * The lane decides which tools the model is even SHOWN (see
 * ToolRegistry.schemasFor). On a PLAYER question web_search is not in the list,
 * so it cannot be chosen by mistake — the failure mode is designed out rather
 * than asked away.
 *
 * Bias: when nothing matches, the answer is PLAYER. Web access is opt-in on
 * positive evidence only, so a router bug degrades to today's behaviour rather
 * than to a confident answer with citations to a blog post.
 */
public final class QuestionRouter {

    private QuestionRouter() {}

    public enum Lane {
        /** The player's own games, drills and history. The existing behaviour. */
        PLAYER,
        /** General chess or world knowledge. Nothing in the database can answer it. */
        GENERAL,
        /** Genuinely both — "how do I do in the Najdorf, and what is its main line". */
        HYBRID;

        public boolean allowsWeb() {
            return this == GENERAL || this == HYBRID;
        }

        public boolean allowsPlayerData() {
            return this == PLAYER || this == HYBRID;
        }
    }

    /**
     * First person, meaning "the player's own chess".
     *
     * Bare "me" is excluded on purpose: "tell me", "show me" and "give me" are
     * requests for information, not claims of ownership. Including it routed
     * "Tell me how chess relates to philosophy" to PLAYER — the exact question
     * that motivated this feature.
     */
    private static final List<Pattern> FIRST_PERSON = compile(
            "\\bmy\\b",
            "\\bmine\\b",
            "\\bi\\b",
            "\\bi'm\\b",
            "\\bi've\\b",
            "\\bi'd\\b",
            "\\bam i\\b",
            "\\bdo i\\b",
            "\\bwas i\\b",
            "\\bwe\\b"
    );

    /**
     * A reference to a concrete game or position. Only the database holds these,
     * so they are as strong a player signal as first person.
     */
    private static final List<Pattern> CONCRETE_GAME = compile(
            // a FEN
            "\\b[rnbqkpRNBQKP1-8]{4,}/[rnbqkpRNBQKP1-8/]+\\s+[wb]\\s",
            // a UUID
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b",
            "\\bthat game\\b",
            "\\blast game\\b",
            "\\bmy game\\b",
            "\\bthis position\\b"
    );

    /**
     * Move notation — 23...h6, 12. Nf3, 5.Bg5. A WEAK signal, deliberately.
     *
     * On its own it means the player's own game ("why was 23...h6 bad"). But
     * alongside a definitional phrase it usually names shared theory rather than
     * anything they played — "explain the theory behind 5.Bg5" is a question
     * about the Najdorf, not about them. Treating it as strong sent that to
     * HYBRID, which hands a 4B model both toolsets and invites exactly the
     * tool-selection confusion this router exists to prevent.
     */
    private static final List<Pattern> MOVE_NOTATION = compile(
            "\\b\\d{1,3}\\s*\\.{1,3}\\s*[KQRBNa-h]"
    );

    /**
     * Definitional phrasing whose subject is NOT the player.
     *
     * Every pattern carries a negative lookahead for first person, so "what is
     * my accuracy" does not read as a request for an encyclopaedia entry while
     * "what is the Najdorf" does.
     *
     * Bare "explain" is deliberately absent — "explain my last blunder" is a
     * player question, and no lookahead makes that verb reliable.
     */
    private static final List<Pattern> DEFINITIONAL = compile(
            "\\bwhat(?:'s| is| are| was| were)\\s+(?:a |an |the )?(?!my\\b|i\\b|our\\b|we\\b)",
            "\\bwho(?:'s| is| are| was| were)\\s+(?!my\\b|i\\b)",
            "\\bhow (?:do|does|did|is|are)\\s+(?!i\\b|my\\b|we\\b|our\\b)",
            "\\bwhy (?:do|does|did|is|are)\\s+(?!i\\b|my\\b|we\\b|our\\b)",
            // "When was the last Nimzowitsch-Larsen match?" reached PLAYER and
            // died there, because no pattern covered a question of time or
            // place. Same copula requirement and same lookahead as the rest, so
            // "when did I last play" and "where do I blunder" stay PLAYER.
            "\\bwhen (?:was|were|is|are|did|does|do)\\s+(?!i\\b|my\\b|we\\b|our\\b)",
            "\\bwhere (?:was|were|is|are|did|does|do)\\s+(?!i\\b|my\\b|we\\b|our\\b)",
            "\\btell me (?:how|why|what|about)\\s+(?!i\\b|my\\b|we\\b|our\\b)",
            "\\bdefine\\b",
            "\\bmeaning of\\b",
            "\\bmain line\\b",
            "\\bopening theory\\b",
            "\\btheory behind\\b",
            "\\bhistory of\\b",
            "\\bwho invented\\b",
            "\\bnamed after\\b",
            "\\bwhat does .{1,40} mean\\b"
    );

    /**
     * Did the player point at their OWN chess, explicitly?
     *
     * "my", "I played", "that game" — a positive claim of ownership, as opposed
     * to landing on PLAYER because nothing else matched.
     *
     * The distinction matters to the grounding layer. A PLAYER verdict reached
     * by DEFAULT may well be a misroute worth rescuing with a web search. A
     * PLAYER verdict reached by first person is not: asked "suggest me best move
     * I played", the model called nothing, the answer was escalated, and Prax
     * web-searched a question about the player's own games — returning links to
     * online move calculators.
     */
    public static boolean hasStrongPlayerSignal(String question) {
        if (question == null || question.isBlank()) return false;
        String q = question.toLowerCase();
        return matchesAny(FIRST_PERSON, q) || matchesAny(CONCRETE_GAME, q);
    }

    public static Lane route(String question) {
        if (question == null || question.isBlank()) return Lane.PLAYER;
        String q = question.toLowerCase();

        // Strong: this question is unambiguously about their own chess.
        boolean strongPlayer = matchesAny(FIRST_PERSON, q) || matchesAny(CONCRETE_GAME, q);
        boolean general = matchesAny(DEFINITIONAL, q);

        // Both, genuinely — "how do I score in the Najdorf, and what is its main line".
        if (strongPlayer && general) return Lane.HYBRID;

        // Definitional with at most a weak player signal. A move number here is
        // naming theory, not a game they played.
        if (general) return Lane.GENERAL;

        // Everything else, including move notation on its own and anything
        // unmatched. The safe direction: today's grounded behaviour.
        return Lane.PLAYER;
    }

    private static boolean matchesAny(List<Pattern> patterns, String q) {
        for (Pattern p : patterns) {
            if (p.matcher(q).find()) return true;
        }
        return false;
    }

    private static List<Pattern> compile(String... regexes) {
        return java.util.Arrays.stream(regexes)
                .map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE))
                .toList();
    }
}
