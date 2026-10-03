package com.praxis.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A language-model backend. Callers depend on this and on {@link ModelRouter},
 * never on a concrete provider, so adding one (say, Anthropic) is a new class
 * and a configuration value; no caller changes.
 *
 * <p>Callers state an {@link OutputBudget}; each provider turns it into its own
 * token limit and reports when an answer stopped at that limit. The
 * {@code *WithRetry} methods give a cut-off answer one more try with twice the
 * room, so a limit that was too tight shows up in the log instead of as a
 * silently dropped explanation.
 */
public interface LlmProvider {

    Logger LOG = LoggerFactory.getLogger(LlmProvider.class);

    /** Stable identifier: "ollama", "openai-compatible". */
    String id();

    /** Where requests go, for display: "localhost:11434", "api.openai.com". */
    String host();

    /** The token limit this provider applies for a budget at scale 1. */
    int maxTokens(OutputBudget budget);

    /**
     * One chat turn. Never throws: an unreachable or failing provider returns
     * {@link ChatResult#empty()}, which the agent reports as "couldn't reach the model".
     */
    ChatResult chat(ChatRequest request);

    /**
     * One JSON completion.
     *
     * @throws IllegalStateException when the provider fails or returns nothing
     */
    Completion complete(CompletionRequest request);

    /** A cheap reachability check; implementations may cache it. */
    Health health();

    /** {@link #chat}, retried once with twice the room if the answer was cut off. */
    default ChatResult chatWithRetry(ChatRequest request) {
        ChatResult first = chat(request);
        if (!first.truncated()) return first;
        int limit = maxTokens(request.budget()) * request.scale();
        LOG.warn("[ai] {} {}: {} answer cut off at {} tokens; retrying with {}",
                id(), request.model(), request.budget().key(), limit, limit * 2);
        ChatResult second = chat(request.doubled());
        if (second.truncated()) {
            LOG.warn("[ai] {} {}: {} answer cut off again at {} tokens; raise this budget",
                    id(), request.model(), request.budget().key(), limit * 2);
        }
        return second;
    }

    /**
     * {@link #complete}, retried once with twice the room if the answer was cut off.
     *
     * @throws IllegalStateException when the answer is still cut off, so the caller's
     *                               fallback runs with the reason in the log
     */
    default String completeWithRetry(CompletionRequest request) {
        Completion first = complete(request);
        if (!first.truncated()) return first.text();
        int limit = maxTokens(request.budget()) * request.scale();
        LOG.warn("[ai] {} {}: {} answer cut off at {} tokens; retrying with {}",
                id(), request.model(), request.budget().key(), limit, limit * 2);
        Completion second = complete(request.doubled());
        if (second.truncated()) {
            throw new IllegalStateException(id() + " " + request.model() + ": " + request.budget().key()
                    + " answer cut off again at " + (limit * 2) + " tokens; raise this budget");
        }
        return second.text();
    }
}
