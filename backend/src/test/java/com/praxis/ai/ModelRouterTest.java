package com.praxis.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.ai.ollama.OllamaProvider;
import com.praxis.ai.openai.OpenAiCompatibleProvider;
import com.praxis.config.AiProperties;
import com.praxis.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Routing is decided in one place: Ollama by default, the cloud only when listed AND configured. */
@DisplayName("Model router")
class ModelRouterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final AppProperties LOCAL = new AppProperties(
            new AppProperties.Ollama("http://localhost:11434", "qwen2.5:7b", "qwen3:4b", null, "qwen3:4b-instruct", null),
            null, null, null, null, null);

    static AiProperties cloud(List<String> features) {
        return new AiProperties(new AiProperties.Cloud("https://api.example.com/v1", "sk-test", "gpt-test",
                null, null, "gpt-report", null, null, null), features);
    }

    private static ModelRouter router(AiProperties ai) {
        return new ModelRouter(LOCAL, ai, new OllamaProvider(LOCAL, MAPPER), new OpenAiCompatibleProvider(ai, MAPPER));
    }

    @Test
    @DisplayName("every feature runs on Ollama with its own local model by default")
    void localByDefault() {
        var r = router(new AiProperties(null, null));
        assertThat(r.forFeature(Feature.PRAX).provider().id()).isEqualTo("ollama");
        assertThat(r.forFeature(Feature.PRAX).model()).isEqualTo("qwen3:4b-instruct");
        assertThat(r.forFeature(Feature.EXPLANATIONS).model()).isEqualTo("qwen3:4b");
        assertThat(r.forFeature(Feature.REPORTS).model()).isEqualTo("qwen2.5:7b");   // falls back to `model`
        for (Feature f : Feature.values()) assertThat(r.forFeature(f).cloud()).isFalse();
    }

    @Test
    @DisplayName("a listed feature goes to the cloud with its model; the rest stay local")
    void perFeature() {
        var r = router(cloud(List.of("prax", "reports")));
        assertThat(r.forFeature(Feature.PRAX).provider().id()).isEqualTo("openai-compatible");
        assertThat(r.forFeature(Feature.PRAX).model()).isEqualTo("gpt-test");
        assertThat(r.forFeature(Feature.REPORTS).model()).isEqualTo("gpt-report");
        assertThat(r.forFeature(Feature.EXPLANATIONS).provider().id()).isEqualTo("ollama");
        assertThat(r.forFeature(Feature.PRAX).provider().host()).isEqualTo("api.example.com");
    }

    @Test
    @DisplayName("listed but no provider configured: stays local, and says it was asked for")
    void listedButUnconfigured() {
        var r = router(new AiProperties(null, List.of(" Prax ")));
        assertThat(r.forFeature(Feature.PRAX).cloud()).isFalse();
        assertThat(r.requestedCloud(Feature.PRAX)).isTrue();
        assertThat(r.cloudConfigured()).isFalse();
    }
}
