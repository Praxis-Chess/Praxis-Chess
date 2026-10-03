package com.praxis.ai;

import com.praxis.ai.ollama.OllamaProvider;
import com.praxis.ai.openai.OpenAiCompatibleProvider;
import com.praxis.config.AiProperties;
import com.praxis.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Which provider and model serve each feature. The only class that reads the AI
 * configuration, so routing is decided, and tested, in one place.
 *
 * <ul>
 *   <li>Ollama on this machine by default, with the feature's local model
 *       ({@code praxis-chess.ollama.*}).</li>
 *   <li>The cloud provider when the feature is listed in
 *       {@code praxis-chess.ai.use-cloud-for} AND a provider is configured
 *       ({@code praxis-chess.ai.cloud.*}). Listed but unconfigured stays local.</li>
 * </ul>
 */
@Component
public class ModelRouter {

    /** Where one feature runs. */
    public record Route(Feature feature, LlmProvider provider, String model, boolean cloud) {}

    private final AppProperties props;
    private final AiProperties ai;
    private final LlmProvider local;
    private final LlmProvider cloud;

    public ModelRouter(AppProperties props, AiProperties ai, OllamaProvider local, OpenAiCompatibleProvider cloud) {
        this.props = props;
        this.ai = ai;
        this.local = local;
        this.cloud = cloud;
    }

    public Route forFeature(Feature f) {
        if (ai.usesCloud(f)) return new Route(f, cloud, ai.cloudModel(f), true);
        return new Route(f, local, localModel(f), false);
    }

    /** Listed in use-cloud-for, whether or not a provider is configured. */
    public boolean requestedCloud(Feature f) {
        return ai.requested(f);
    }

    public boolean cloudConfigured() {
        return ai.cloudConfigured();
    }

    public boolean cloudKeySet() {
        return ai.keySet();
    }

    private String localModel(Feature f) {
        var o = props.ollama();
        String base = o == null ? null : o.model();
        return switch (f) {
            case PRAX -> props.reasoningModel();
            case EXPLANATIONS -> o == null || blank(o.moveModel()) ? base : o.moveModel();
            case REPORTS -> o == null || blank(o.reportModel()) ? base : o.reportModel();
        };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
