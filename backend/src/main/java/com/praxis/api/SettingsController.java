package com.praxis.api;

import com.praxis.ai.Feature;
import com.praxis.ai.ModelRouter;
import com.praxis.config.AppProperties;
import com.praxis.dto.SettingsDto.*;
import com.praxis.service.settings.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Settings page: sync and analysis date ranges, versioned engine settings,
 * the coverage view and measured time estimates.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;
    private final ModelRouter router;
    private final AppProperties props;

    public SettingsController(SettingsService settings, ModelRouter router, AppProperties props) {
        this.settings = settings;
        this.router = router;
        this.props = props;
    }

    /**
     * Where each AI feature runs: Ollama on this machine, or the cloud provider
     * from {@code praxis-chess.ai}. Read-only: the API key lives in
     * application.yml or an environment variable, never in the browser.
     */
    @GetMapping("/ai")
    public AiStatus ai() {
        String commentary = props.ollama() == null ? null : props.ollama().commentaryModel();
        String cloudHost = null;
        List<AiFeature> features = new ArrayList<>();
        for (Feature f : Feature.values()) {
            ModelRouter.Route r = router.forFeature(f);
            if (r.cloud()) cloudHost = r.provider().host();
            features.add(new AiFeature(f.key, LABELS.get(f), r.cloud(), r.model(), router.requestedCloud(f), false));
        }
        // The trained commentary model is this project's own file: Ollama only.
        features.add(new AiFeature("commentary", "Checked commentary (your trained model)", false,
                commentary == null || commentary.isBlank() ? null : commentary, false, true));
        return new AiStatus(features, router.cloudConfigured(), router.cloudKeySet(), cloudHost);
    }

    private static final Map<Feature, String> LABELS = Map.of(
            Feature.PRAX, "Prax chat",
            Feature.EXPLANATIONS, "Move explanations",
            Feature.REPORTS, "Pattern report, training plan, today's insight");

    @GetMapping
    public View get() {
        return settings.view();
    }

    /**
     * 200 with the saved settings, or 400 with every invalid field at once.
     * Out-of-range values are rejected, never silently clamped: a clamped depth
     * would be a different ruler from the one the user asked for, recorded on
     * every game analysed with it.
     */
    @PutMapping
    public ResponseEntity<?> update(@RequestBody Update body) {
        try {
            return ResponseEntity.ok(settings.update(body));
        } catch (SettingsService.InvalidSettings e) {
            return ResponseEntity.badRequest().body(new Invalid(e.errors()));
        }
    }

    @GetMapping("/coverage")
    public Coverage coverage() {
        return settings.coverage();
    }

    /** POST because it takes a proposed configuration as a body; it changes nothing. */
    @PostMapping("/estimate")
    public Estimate estimate(@RequestBody(required = false) EstimateRequest body) {
        return settings.estimate(body);
    }
}
