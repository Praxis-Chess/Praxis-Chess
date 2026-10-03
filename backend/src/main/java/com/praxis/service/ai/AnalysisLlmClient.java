package com.praxis.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.ai.CompletionRequest;
import com.praxis.ai.Feature;
import com.praxis.ai.ModelRouter;
import com.praxis.ai.OutputBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JSON completions for the analysis pipeline: per-move explanations (fast model,
 * ×N per game) and the pattern report / training plan (quality model, ×1).
 * Which provider and model serve each is {@link ModelRouter}'s decision.
 */
@Component
public class AnalysisLlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnalysisLlmClient.class);

    private final ModelRouter router;
    private final ObjectMapper objectMapper;

    public AnalysisLlmClient(ModelRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    /** Per-move explanations. */
    public <T> T analyzeMove(String prompt, Class<T> responseType) {
        return analyze(Feature.EXPLANATIONS, prompt, OutputBudget.EXPLANATION, responseType);
    }

    /** Pattern report and training plan. */
    public <T> T analyzeReport(String prompt, Class<T> responseType) {
        return analyze(Feature.REPORTS, prompt, OutputBudget.REPORT, responseType);
    }

    private <T> T analyze(Feature feature, String prompt, OutputBudget budget, Class<T> responseType) {
        ModelRouter.Route route = router.forFeature(feature);
        String text = route.provider().completeWithRetry(CompletionRequest.standard(route.model(), prompt, budget));
        String json = text.replaceAll("```json", "").replaceAll("```", "").trim();
        try {
            return objectMapper.readValue(json, responseType);
        } catch (Exception e) {
            log.warn("Failed to parse model response as {}: {}", responseType.getSimpleName(), json);
            throw new RuntimeException("Model response parse failed: " + e.getMessage(), e);
        }
    }
}
