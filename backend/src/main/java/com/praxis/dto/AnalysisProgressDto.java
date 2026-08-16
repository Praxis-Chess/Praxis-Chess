package com.praxis.dto;

public record AnalysisProgressDto(
    boolean running,
    boolean patternGenerating,
    boolean queued,
    /** Stop requested; the run ends after the game currently in flight. */
    boolean stopping,
    int completed,
    int total,
    int percentComplete,
    long etaSeconds
) {}
