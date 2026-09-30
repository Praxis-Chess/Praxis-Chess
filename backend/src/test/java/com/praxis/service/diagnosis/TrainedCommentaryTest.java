package com.praxis.service.diagnosis;

import com.praxis.config.AppProperties;
import com.praxis.evidence.diagnosis.TestGraphs;
import com.praxis.evidence.graph.Representations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase 9b: the trained model is asked exactly as it was trained. */
@DisplayName("Trained commentary")
class TrainedCommentaryTest {

    private static AppProperties props(String model) {
        var p = mock(AppProperties.class);
        when(p.ollama()).thenReturn(new AppProperties.Ollama("http://localhost:11434", "qwen2.5:7b", null, null, null, model));
        return p;
    }

    /** The training format, byte for byte: system, the R3 render, and a closed thinking block. */
    @Test
    void asksInTheTrainingFormat() {
        var g = TestGraphs.scholarsMate();
        String prompt = TrainedCommentary.prompt(g);

        assertThat(prompt).startsWith("<|im_start|>system\n" + TrainedCommentary.SYSTEM + "<|im_end|>\n<|im_start|>user\n");
        assertThat(prompt).contains(Representations.render(g, Representations.Level.R3).text());
        assertThat(prompt).endsWith("<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n");
    }

    /** The system message the training rows carry (build_dataset6.SYSTEM). */
    @Test
    void usesTheTrainingSystemMessage() {
        assertThat(TrainedCommentary.SYSTEM).isEqualTo(
                "You are Prax. Diagnose the mistake using ONLY the evidence given. Every claim must be typed. Output JSON.");
    }

    /** Unset, nothing is asked: no model, no network. */
    @Test
    void isOffUntilAModelIsConfigured() {
        var off = new TrainedCommentary(props(null));
        assertThat(off.enabled()).isFalse();
        assertThat(off.write(TestGraphs.scholarsMate())).isEmpty();

        assertThat(new TrainedCommentary(props("praxis-grid-2b-r3")).enabled()).isTrue();
    }
}
