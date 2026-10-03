package com.praxis.ai;

/**
 * One prompt in, one JSON answer out.
 *
 * @param budget        what kind of answer is expected; the provider sets the token limit
 * @param scale         1 normally; 2 when retrying an answer that was cut off
 * @param repeatPenalty applied where the provider supports it (Ollama); ignored elsewhere
 */
public record CompletionRequest(String model, String prompt, OutputBudget budget, int scale,
                                double temperature, double topP, double repeatPenalty) {

    public CompletionRequest {
        scale = Math.max(1, scale);
    }

    /** For explanations and reports: a little variety in the wording. */
    public static CompletionRequest standard(String model, String prompt, OutputBudget budget) {
        return new CompletionRequest(model, prompt, budget, 1, 0.3, 0.95, 1.0);
    }

    /** For short structured answers that must follow a contract closely. */
    public static CompletionRequest structured(String model, String prompt, OutputBudget budget) {
        return new CompletionRequest(model, prompt, budget, 1, 0.2, 0.9, 1.1);
    }

    /** The same request with twice the output room, for one retry after a cut-off. */
    public CompletionRequest doubled() {
        return new CompletionRequest(model, prompt, budget, scale * 2, temperature, topP, repeatPenalty);
    }
}
