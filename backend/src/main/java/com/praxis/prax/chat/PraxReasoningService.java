package com.praxis.prax.chat;

import com.praxis.prax.evidence.Evidence;
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
    private static final int MAX_HISTORY_MESSAGES = 12;

    private final PraxAgent agent;
    private final OllamaChatClient llm;
    private final List<Map<String, Object>> history = Collections.synchronizedList(new ArrayList<>());

    public PraxReasoningService(PraxAgent agent, OllamaChatClient llm) {
        this.agent = agent;
        this.llm = llm;
    }

    public record Answer(String answer, List<Evidence> evidence, List<String> findings,
                         List<PraxAgent.Step> steps, boolean partial, String model) {}

    public boolean isAvailable() {
        return llm.isHealthy();
    }

    public Answer ask(String question) {
        long t0 = System.currentTimeMillis();

        List<Map<String, Object>> prior;
        synchronized (history) {
            prior = new ArrayList<>(history);
        }

        PraxAgent.Outcome out = agent.run(question, prior);

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

        synchronized (history) {
            history.add(Map.of("role", "user", "content", question));
            history.add(Map.of("role", "assistant", "content", answer));
            // Trim from the front — the system prompt is prepended per run, so
            // only the exchange pairs need bounding.
            while (history.size() > MAX_HISTORY_MESSAGES) history.remove(0);
        }

        log.info("[prax] answered in {}ms · {} tool calls · {} evidence{}",
                System.currentTimeMillis() - t0, out.steps().size(),
                out.evidence().size(), out.partial() ? " (partial)" : "");

        return new Answer(answer, out.evidence(), out.findings(), out.steps(),
                out.partial(), llm.model());
    }

    public void reset() {
        synchronized (history) {
            history.clear();
        }
    }
}
