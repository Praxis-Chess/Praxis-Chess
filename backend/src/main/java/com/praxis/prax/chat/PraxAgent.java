package com.praxis.prax.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.prax.evidence.Evidence;
import com.praxis.prax.tools.ToolRegistry;
import com.praxis.prax.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The bounded tool loop (Reasoning Plan §5).
 *
 * Bounded in three dimensions, because an unbounded loop driven by a small
 * local model is a hang rather than a feature. On breach it forces an answer
 * from whatever evidence exists — a partial answer with three real facts is
 * useful, and "the model got confused" is never the user's problem to see.
 */
@Service
public class PraxAgent {

    private static final Logger log = LoggerFactory.getLogger(PraxAgent.class);

    static final int MAX_TURNS = 6;
    static final int MAX_TOOL_CALLS = 10;
    // A warm 4B pass on this hardware runs ~20s, so 45s only ever bought two
    // turns. Measured, not guessed.
    static final long MAX_WALL_CLOCK_MS = 120_000;

    private final OllamaChatClient llm;
    private final ToolRegistry tools;
    private final ObjectMapper mapper;

    public PraxAgent(OllamaChatClient llm, ToolRegistry tools, ObjectMapper mapper) {
        this.llm = llm;
        this.tools = tools;
        this.mapper = mapper;
    }

    /** One tool call as it happened — streamed to the UI so investigating is visible. */
    public record Step(String tool, Map<String, Object> args, int sampleSize) {}

    /**
     * @param findings verified chess statements, rendered exactly as the fact
     *                 builder wrote them. The model chooses which appear and
     *                 never rewrites them — paraphrasing is where the last of
     *                 the errors lived ("Black was deeply behind" for a position
     *                 Black was winning by seven pawns).
     */
    public record Outcome(String answer, List<Evidence> evidence, List<String> findings,
                          List<Step> steps, boolean partial) {}

    public Outcome run(String question, List<Map<String, Object>> priorMessages) {
        long deadline = System.currentTimeMillis() + MAX_WALL_CLOCK_MS;

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", PraxPrompt.SYSTEM));
        messages.addAll(priorMessages);
        messages.add(Map.of("role", "user", "content", question));

        /** Results this turn, keyed by call id — what citations must resolve against. */
        Map<String, ToolResult> resultsById = new LinkedHashMap<>();
        Map<String, ToolResult> dedupe = new HashMap<>();
        List<Step> steps = new ArrayList<>();
        int callCount = 0;
        boolean partial = false;
        // Ids must be unique across the WHOLE run. The client restarts its counter
        // every response, so three turns each produced "tc_1" and each overwrote
        // the last — every citation then resolved to whichever tool happened to
        // run last, with its provenance. That silently broke the entire evidence
        // guarantee while still looking valid.
        int callSeq = 0;
        /** Only one shove per run — otherwise a stubborn model burns every turn. */
        boolean nudged = false;

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            boolean outOfBudget = System.currentTimeMillis() > deadline || callCount >= MAX_TOOL_CALLS;

            // Withholding the tool list is what forces a final answer.
            var reply = llm.chat(messages, outOfBudget ? List.of() : tools.schemas());

            if (outOfBudget) {
                partial = true;
                return finish(reply.content(), resultsById, steps, true);
            }
            if (!reply.wantsTools()) {
                // The model said it would go and look, and then stopped. The
                // loop is synchronous — nothing runs after this — so a promise
                // to check is a dead end that reads to the player as work in
                // progress. Push it once; the turn budget bounds the retry.
                if (resultsById.isEmpty() && !nudged && promisesToAct(reply.content())) {
                    nudged = true;
                    log.debug("[prax] model promised to act without acting; forcing a tool pass");
                    messages.add(Map.of("role", "assistant", "content",
                            reply.content() == null ? "" : reply.content()));
                    messages.add(Map.of("role", "user", "content",
                            "Do it now. Call the tool you need and answer from what it returns. "
                            + "Never say you will check something — check it."));
                    continue;
                }

                // This is the usual exit: the model stops calling tools and just
                // answers. But tools were still offered on that request, so the
                // JSON contract was not enforced and the reply is often prose —
                // which validates to zero citations and ships unchecked chess
                // claims. If it is off-contract, spend one more pass with tools
                // withheld, where format:json applies.
                if (!PraxResponse.isContract(reply.content(), mapper) && !resultsById.isEmpty()) {
                    log.debug("[prax] final reply was off-contract; re-asking for JSON");
                    messages.add(Map.of("role", "assistant", "content",
                            reply.content() == null ? "" : reply.content()));
                    messages.add(Map.of("role", "user", "content",
                            "Now give that answer in the required JSON object: "
                            + "answer, evidence (each with label, value, callId), followUp."));
                    var strict = llm.chat(messages, List.of());
                    if (PraxResponse.isContract(strict.content(), mapper)) {
                        return finish(strict.content(), resultsById, steps, false);
                    }
                }
                return finish(reply.content(), resultsById, steps, false);
            }

            List<Map<String, Object>> assistantCalls = new ArrayList<>();
            // Results produced by THIS turn only. Re-sending the whole map each
            // turn duplicated every earlier result and blew past num_ctx, which
            // Ollama answers by truncating — hence an empty final message.
            List<String> turnIds = new ArrayList<>();
            for (var call : reply.toolCalls()) {
                if (callCount >= MAX_TOOL_CALLS) break;
                callCount++;

                String callId = "tc_" + (++callSeq);
                String key = call.name() + mapper.valueToTree(call.arguments());
                ToolResult result = dedupe.get(key);
                if (result == null) {
                    result = tools.execute(call.name(), call.arguments());
                    dedupe.put(key, result);
                } else {
                    log.debug("[prax] cached {}", call.name());
                }

                resultsById.put(callId, result);
                turnIds.add(callId);
                steps.add(new Step(call.name(), call.arguments(), result.sampleSize()));

                assistantCalls.add(Map.of("function",
                        Map.of("name", call.name(), "arguments", call.arguments())));

                // Some results are inert alone — find_mistakes names the move but
                // cannot say why it was bad. Run the follow-up here rather than
                // hoping the model does. It gets its own callId and its own step,
                // so provenance stays exact and the extra call is visible.
                var chained = tools.followUp(call.name(), result);
                if (chained.isPresent() && callCount < MAX_TOOL_CALLS) {
                    callCount++;
                    String chainId = "tc_" + (++callSeq);
                    ToolResult cr = tools.execute("analyze_position", chained.get());
                    resultsById.put(chainId, cr);
                    turnIds.add(chainId);
                    steps.add(new Step("analyze_position", chained.get(), cr.sampleSize()));
                    assistantCalls.add(Map.of("function",
                            Map.of("name", "analyze_position", "arguments", chained.get())));
                    log.debug("[prax] auto-chained analyze_position after {}", call.name());
                }
            }

            messages.add(Map.of("role", "assistant", "content", "", "tool_calls", assistantCalls));
            for (String id : turnIds) {
                ToolResult r = resultsById.get(id);
                messages.add(Map.of(
                        "role", "tool",
                        "content", toJson(Map.of(
                                "callId", id,
                                "tool", r.tool(),
                                "sampleSize", r.sampleSize(),
                                "data", r.data()))));
            }
        }

        // Ran out of turns: one final pass with tools withheld.
        var forced = llm.chat(messages, List.of());
        return finish(forced.content(), resultsById, steps, true);
    }

    private Outcome finish(String raw, Map<String, ToolResult> results, List<Step> steps, boolean partial) {
        var parsed = PraxResponse.parse(raw, mapper);
        List<Evidence> validated = EvidenceValidator.validate(parsed.evidence(), results);
        List<String> findings = renderFindings(parsed.factIds(), results);
        String answer = parsed.answer();

        // §7.4 — no surviving player-data evidence means this cannot be presented
        // as personal analysis, whatever the model believes it just did.
        boolean hasPlayerData = validated.stream()
                .anyMatch(e -> e.source() == Evidence.Provenance.PLAYER_DATA);
        if (!hasPlayerData && !results.isEmpty() && answer != null) {
            log.debug("[prax] answer had no resolvable player-data citations");
        }

        // Figures with no tool call behind them at all. This is what conversation
        // history makes possible: asked "Hi Prax!", the model replayed the
        // previous answer's statistics verbatim — 27 games, 41%, 62.4% — without
        // calling anything. Citation validation cannot catch it, because nothing
        // was cited. An uncited statistic is precisely what this layer exists to
        // stop, so it does not ship.
        if (results.isEmpty() && answer != null && answer.matches("(?s).*\\d.*")) {
            log.warn("[prax] answer stated figures with no tool calls — suppressed as ungrounded");
            answer = "I'd have to look at your games to say anything with numbers in it. "
                   + "Ask me something specific and I'll check.";
        }
        return new Outcome(answer, validated, findings, steps, partial);
    }

    /**
     * Does this answer defer instead of acting?
     *
     * Matched on the shape of the sentence, not the topic — "I'll look into it",
     * "let me check your games". Deliberately narrow: a false positive costs one
     * extra pass, while a miss leaves the player waiting for work that will
     * never happen. Only consulted when the run made no tool calls at all, so a
     * genuine answer that happens to contain "let me" is unaffected.
     */
    private static boolean promisesToAct(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String a = answer.toLowerCase();
        return a.contains("i'll ") || a.contains("i will ") || a.contains("let me ")
                || a.contains("i'm going to") || a.contains("i am going to")
                || a.contains("one moment") || a.contains("give me a moment")
                || a.contains("checking your") || a.contains("let's take a look");
    }

    /** How many verified statements to show. Beyond this it reads as a dump. */
    private static final int MAX_FINDINGS = 4;

    /**
     * Resolve the model's chosen fact ids back to the statements themselves.
     *
     * Selection is the model's; wording is not. If it selects nothing usable we
     * fall back to payload order, which the builder already sorted decisive
     * first — so the player always sees the verified account of the position
     * even when the model's own prose is poor.
     */
    private List<String> renderFindings(List<String> wanted, Map<String, ToolResult> results) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (ToolResult r : results.values()) {
            if (!(r.data() instanceof Map<?, ?> m)
                    || !(m.get("verifiedFacts") instanceof List<?> facts)) {
                continue;
            }
            for (Object o : facts) {
                if (o instanceof Map<?, ?> f
                        && f.get("id") instanceof String id
                        && f.get("fact") instanceof String text) {
                    // Ids are per analyze_position call. Two calls in one run
                    // would collide; the later one wins, which is the more
                    // recent position and so the better default.
                    byId.put(id, text);
                }
            }
        }
        if (byId.isEmpty()) return List.of();

        List<String> out = new ArrayList<>();
        for (String id : wanted) {
            String text = byId.get(id);
            if (text != null && !out.contains(text)) out.add(text);
            if (out.size() >= MAX_FINDINGS) break;
        }
        if (out.isEmpty()) {
            log.debug("[prax] model selected no usable fact ids ({}); falling back to order", wanted);
            byId.values().stream().limit(MAX_FINDINGS).forEach(out::add);
        }
        return out;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
