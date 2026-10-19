package com.praxis.api;

import com.praxis.dto.PatternDto;
import com.praxis.repository.PlayerPatternRepository;
import com.praxis.service.PatternAggregator;
import com.praxis.service.settings.PlayerIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patterns")
public class PatternsController {

    private final PlayerPatternRepository playerPatternRepository;
    private final PlayerIdentity player;
    private final PatternAggregator aggregator;
    private final com.praxis.report.ReportWriter writer;

    public PatternsController(PlayerPatternRepository playerPatternRepository, PlayerIdentity player,
                              PatternAggregator aggregator, com.praxis.report.ReportWriter writer) {
        this.playerPatternRepository = playerPatternRepository;
        this.player = player;
        this.aggregator = aggregator;
        this.writer = writer;
    }

    /**
     * Rebuild the report now: the facts from the current verified diagnoses, and
     * one checked model call for the prose (up to a minute). Adds a row; earlier
     * reports stay. 204 when there are no analysed mistakes to report on.
     */
    @PostMapping("/recompute")
    public ResponseEntity<PatternDto> recompute() {
        aggregator.recompute(player.username());
        return getLatestPattern();
    }

    /**
     * Phase 12, step 7: the same facts through each candidate report writer,
     * {@code runs} times each, one model after the other (so each loads once).
     * A measurement, not a feature: nothing is saved and nothing is shown in the
     * app. Slow: one model call per run, two when the first fails the checker.
     */
    @PostMapping("/compare")
    public java.util.Map<String, Object> compare(
            @RequestParam(defaultValue = "qwen2.5:7b,qwen3:4b-instruct") String models,
            @RequestParam(defaultValue = "5") int runs) {
        var facts = aggregator.facts(player.username());
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (String model : models.split(",")) {
            for (int i = 1; i <= Math.min(Math.max(runs, 1), 10); i++) {
                var p = writer.writeReport(facts, model.trim());
                java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
                r.put("model", p.model());
                r.put("run", i);
                r.put("status", p.status());
                r.put("attempts", p.attempts());
                r.put("millis", p.millis());
                r.put("violations", p.violations());
                r.put("answer", p.lastAnswer());
                out.add(r);
            }
        }
        return java.util.Map.of("facts", facts, "runs", out);
    }

    @GetMapping
    public ResponseEntity<PatternDto> getLatestPattern() {
        return playerPatternRepository
                .findTopByUsernameOrderByComputedAtDesc(player.username())
                .map(PatternDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}
