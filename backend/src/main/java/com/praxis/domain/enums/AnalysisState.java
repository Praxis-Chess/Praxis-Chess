package com.praxis.domain.enums;

public enum AnalysisState {
    EXPLAINED,   // LLM provided explanation + tactical motif
    SKIPPED,     // Engine-only: outside the profile's maxOllamaCalls budget
    LLM_FAILED   // LLM was called but returned invalid/unparseable response
}
