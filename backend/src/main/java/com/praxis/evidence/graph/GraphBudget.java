package com.praxis.evidence.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * The §6.6 budget: at most {@value #MAX_ITEMS} items and about
 * {@value #MAX_TOKENS} tokens per serialised graph.
 *
 * <p>This is not tidiness. Prompt length is a confound in H1 — if R3 wins only
 * because it is longer, the experiment has measured length, not structure — so
 * every graph's size is recorded and the cap is enforced by dropping the
 * lowest-priority items first, deterministically, in the plan's order.
 *
 * <p><b>Tokens are estimated, not counted.</b> Java has no Qwen tokenizer; the
 * estimate is characters divided by {@link #CHARS_PER_TOKEN}, a ratio measured
 * against the real Qwen3.5 tokenizer on rendered graphs. It is set to err high:
 * an estimate that undercounts lets over-budget graphs through. The training
 * side re-counts exactly anyway.
 */
public final class GraphBudget {

    private GraphBudget() {}

    public static final int MAX_ITEMS = 40;
    public static final int MAX_TOKENS = 900;

    /**
     * Characters per Qwen token on rendered graph text, measured with the real
     * Qwen3.5 tokenizer on 60 R3 graphs from the player's own games: median 2.54,
     * lowest 2.34. Chess notation, square names and the · separators tokenise far
     * worse than prose (~4 for English).
     *
     * <p>Set just under the lowest ratio seen, so the estimate over-counts on every
     * graph measured. The first value, 3.0, was a guess; it under-counted on all
     * 60 of 60 graphs, while this comment claimed it erred high.
     */
    public static final double CHARS_PER_TOKEN = 2.3;

    public static int estimateTokens(String text) {
        return text == null ? 0 : (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * @param kept      items that survive, in priority order
     * @param dropped   IDs removed to meet the budget, lowest priority first
     * @param tokens    estimated tokens of the rendered result
     */
    public record Fitted(List<EvidenceGraph.GraphItem> kept, List<String> dropped, int tokens) {
        public boolean truncated() {
            return !dropped.isEmpty();
        }
    }

    /**
     * Drop from the lowest priority up until both limits hold. The skeleton —
     * priority 0 to 5: P0, B, M, PC, R1, CF1, T1, D1 — is never dropped; a graph
     * too large even at its skeleton is returned as it is and reported over
     * budget, rather than silently losing the consequence.
     *
     * @param render turns a candidate item list into the text whose size is judged
     */
    public static Fitted fit(List<EvidenceGraph.GraphItem> items,
                             java.util.function.Function<List<EvidenceGraph.GraphItem>, String> render) {
        List<EvidenceGraph.GraphItem> kept = new ArrayList<>(items);
        List<String> dropped = new ArrayList<>();
        int tokens = estimateTokens(render.apply(kept));

        while ((kept.size() > MAX_ITEMS || tokens > MAX_TOKENS) && !kept.isEmpty()) {
            EvidenceGraph.GraphItem last = kept.get(kept.size() - 1);
            if (last.priority() <= 5) break;   // skeleton reached
            kept.remove(kept.size() - 1);
            dropped.add(last.id());
            tokens = estimateTokens(render.apply(kept));
        }
        return new Fitted(kept, dropped, tokens);
    }

    public static boolean within(Fitted fitted) {
        return fitted.kept().size() <= MAX_ITEMS && fitted.tokens() <= MAX_TOKENS;
    }
}
