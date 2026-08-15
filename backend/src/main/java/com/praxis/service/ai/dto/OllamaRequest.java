package com.praxis.service.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OllamaRequest(
    String model,
    String prompt,
    boolean stream,
    String format,
    @JsonProperty("num_predict") int numPredict,
    @JsonProperty("keep_alive") String keepAlive,
    @JsonProperty("num_ctx") Integer numCtx,
    OllamaOptions options
) {
    /** Legacy constructor for callers that don't need temperature control (defaults to standard). */
    public OllamaRequest(String model, String prompt, boolean stream, String format,
                         int numPredict, String keepAlive, Integer numCtx) {
        this(model, prompt, stream, format, numPredict, keepAlive, numCtx, OllamaOptions.standard());
    }
}
