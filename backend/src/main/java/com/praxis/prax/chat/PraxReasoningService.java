package com.praxis.prax.chat;

import com.praxis.prax.conversation.ConversationService;
import com.praxis.prax.conversation.domain.Conversation;
import com.praxis.prax.evidence.Evidence;
import com.praxis.prax.grounding.GroundingPolicy;
import com.praxis.prax.grounding.Verdict;
import com.praxis.prax.routing.QuestionRouter;
import com.praxis.prax.web.WebResearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Named for what it is. Chat is the interface; the product is a reasoning
 * system with access to the player's history, deterministic analytics and an
 * engine (Reasoning Plan §12). Calling this ChatService would invite treating
 * it as a wrapper around a text box.
 *
 * Conversation memory lives in memory for V1 — a single local user, one
 * session at a time. Persisting it to PostgreSQL is V4, and this is the only
 * class that would change.
 */
@Service
public class PraxReasoningService {

    private static final Logger log = LoggerFactory.getLogger(PraxReasoningService.class);

    /**
     * The whole question budget, across escalation. PraxAgent bounds a SINGLE
     * run at 120s; without a ceiling here, a run plus its fallback could take
     * four minutes.
     */
    private static final long MAX_QUESTION_MS = 150_000;

    private final PraxAgent agent;
    private final OllamaChatClient llm;
    private final WebResearchService web;
    private final ConversationService conversations;

    public PraxReasoningService(PraxAgent agent, OllamaChatClient llm, WebResearchService web,
                                ConversationService conversations) {
        this.agent = agent;
        this.llm = llm;
        this.web = web;
        this.conversations = conversations;
    }

    /**
     * @param grounding how the answer earned the right to ship. Surfaced so the
     *                  UI can say "answered from the web because your library
     *                  couldn't", and so escalation rate is measurable — a high
     *                  rate is direct evidence the router needs new patterns.
     */
    public record Answer(String answer, List<Evidence> evidence, List<String> findings,
                         List<PraxAgent.Step> steps, boolean partial, String model,
                         List<PraxAgent.Source> sources, String lane, String grounding,
                         List<com.praxis.prax.artifact.PraxArtifact> artifacts,
                         String conversationId) {}

    public boolean isAvailable() {
        return llm.isHealthy();
    }

    public Answer ask(String question) {
        return ask(question, null, PraxAgent.ProgressSink.NONE);
    }

    public Answer ask(String question, UUID conversationId) {
        return ask(question, conversationId, PraxAgent.ProgressSink.NONE);
    }

    /**
     * @param conversationId the thread to continue, or null to start one. Scoped
     *                       rather than global: a single shared history meant a
     *                       question asked in one browser tab changed the answer
     *                       given in another.
     */
    public Answer ask(String question, UUID conversationId, PraxAgent.ProgressSink sink) {
        long t0 = System.currentTimeMillis();

        Conversation thread = conversationId == null
                ? conversations.start(question)
                : conversations.find(conversationId).orElseGet(() -> conversations.start(question));

        List<Map<String, Object>> prior = conversations.contextFor(thread.getId());

        PraxAgent.Outcome out = agent.run(question, prior, QuestionRouter.route(question), sink);

        // ── THE GROUNDING INVARIANT ──
        // Routing decided which tools this question could use. This decides
        // whether what came back is allowed to reach the player.
        Verdict verdict = GroundingPolicy.assess(
                out.lane(), out.trustedCalls(), question,
                web.isAvailable(), false, budgetLeft(t0));
        String grounding = "GROUNDED";

        if (verdict.isEscalate()) {
            log.info("[prax] escalating to web: {}", verdict.reason());
            // Re-run through the GENERAL lane, which pre-searches before the
            // first model call. Reusing that path rather than writing a parallel
            // fallback means escalation inherits the injection boundary, the
            // source collection and the no-sources guard already tested there.
            //
            // No prior history: the earlier ungrounded attempt is exactly what
            // must not influence the retry.
            PraxAgent.Outcome escalated =
                    agent.run(question, List.of(), QuestionRouter.Lane.GENERAL, sink);

            Verdict second = GroundingPolicy.assess(
                    escalated.lane(), escalated.trustedCalls(), question,
                    web.isAvailable(), true, budgetLeft(t0));

            out = escalated;
            verdict = second;
            grounding = second.isShip() ? "ESCALATED_TO_WEB" : "REFUSED";
        } else if (verdict.isRefuse()) {
            grounding = "REFUSED";
        }

        // Distinguish "no model" from "model produced nothing usable" — the
        // original message blamed Ollama even when it had answered fine.
        String answer = out.answer();
        if (answer == null || answer.isBlank()) {
            answer = out.steps().isEmpty()
                    ? "I couldn't reach the model. Check that Ollama is running."
                    : "I gathered the data but couldn't form an answer from it. "
                      + "This usually means the model is a reasoning variant that ran out "
                      + "of output budget — try an instruct model.";
        }

        // A refusal REPLACES the answer rather than annotating it. Prefixing a
        // caveat would ship the claim with a disclaimer attached, and
        // disclaimers do not get read.
        if (verdict.isRefuse()) {
            log.warn("[prax] refused an ungrounded answer: {}", verdict.reason());
            // Prefer the run's own message when a guard already wrote one: it
            // names the actual cause ("the search service isn't responding")
            // where the policy can only say "I couldn't find a source", which
            // sends the player hunting for a better question rather than a
            // stopped container.
            answer = out.suppressed() && out.answer() != null && !out.answer().isBlank()
                    ? out.answer()
                    : verdict.playerMessage();
        }


        log.info("[prax] answered in {}ms · lane {} · {} · {} tool calls ({} trusted) "
                        + "· {} evidence · {} sources{}",
                System.currentTimeMillis() - t0, out.lane(), grounding, out.steps().size(),
                out.trustedCalls(), out.evidence().size(), out.sources().size(),
                out.partial() ? " (partial)" : "");

        Answer result = new Answer(answer, out.evidence(), out.findings(), out.steps(),
                out.partial(), llm.model(), out.sources(), out.lane().name(), grounding,
                out.artifacts(), thread.getId().toString());

        // One exchange per question, written after the FINAL verdict — never one
        // per attempt. An escalated question runs twice, and recording the
        // suppressed first answer would let the model read its own fabrication
        // back as context on the next turn.
        conversations.record(thread.getId(), question, answer, result);

        return result;
    }

    private static long budgetLeft(long startedAt) {
        return MAX_QUESTION_MS - (System.currentTimeMillis() - startedAt);
    }

    /**
     * Kept for the floating card, which has no notion of a thread.
     *
     * Starting a new conversation is now the real "reset" — see
     * ConversationService. This no longer needs to clear anything, because there
     * is no longer a global list to clear.
     */
    public void reset() {
        log.debug("[prax] reset called; conversations are scoped, so nothing global to clear");
    }
}
