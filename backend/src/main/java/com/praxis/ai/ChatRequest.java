package com.praxis.ai;

import java.util.List;

/**
 * One chat turn.
 *
 * @param tools          offered tools; empty forces a final answer
 * @param jsonAnswer     the answer must be a JSON object
 * @param budget         what kind of answer is expected; the provider sets the token limit
 * @param scale          1 normally; 2 when retrying an answer that was cut off
 * @param repeatPenalty  applied where the provider supports it (Ollama); ignored elsewhere
 * @param contextTokens  the context window to run in, where the provider takes one (Ollama)
 */
public record ChatRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools, boolean jsonAnswer,
                          OutputBudget budget, int scale, double temperature, double repeatPenalty,
                          int contextTokens) {
    public ChatRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        scale = Math.max(1, scale);
    }

    /** The same request with twice the output room, for one retry after a cut-off. */
    public ChatRequest doubled() {
        return new ChatRequest(model, messages, tools, jsonAnswer, budget, scale * 2, temperature, repeatPenalty,
                contextTokens);
    }
}
