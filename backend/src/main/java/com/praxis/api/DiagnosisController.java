package com.praxis.api;

import com.praxis.service.diagnosis.DiagnosisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Evidence graphs for the player's own mistakes, and the hand labels that
 * validate the rules against them (Phase 3's exit criterion).
 */
@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {

    private final DiagnosisService diagnosis;

    public DiagnosisController(DiagnosisService diagnosis) {
        this.diagnosis = diagnosis;
    }

    /** Build graphs for up to {@code limit} more mistakes. Bounded, so progress is visible. */
    @PostMapping("/build")
    public DiagnosisService.BuildResult build(@RequestParam(defaultValue = "25") int limit) {
        return diagnosis.build(Math.max(1, Math.min(limit, 100)));
    }

    /** Rebuild up to {@code limit} graphs made by an older builder, keeping their hand labels. */
    @PostMapping("/rebuild")
    public DiagnosisService.BuildResult rebuild(@RequestParam(defaultValue = "100") int limit) {
        return diagnosis.rebuild(Math.max(1, Math.min(limit, 100)));
    }

    /** The next mistake to label, without the rules' verdict. 204 when none is waiting. */
    @GetMapping("/next")
    public ResponseEntity<DiagnosisService.LabelCard> next() {
        return diagnosis.next().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    public record LabelRequest(String consequence, String mechanism, String note) {}

    @PostMapping("/label/{id}")
    public ResponseEntity<Map<String, Object>> label(@PathVariable UUID id, @RequestBody LabelRequest body) {
        try {
            diagnosis.label(id, body.consequence(), body.mechanism(), body.note());
            return ResponseEntity.ok(Map.of("saved", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Why a mistake was a mistake: the verified diagnosis, its steps, and the
     * board for each step. Built on first view and stored. 404 when the game has
     * no mistake at that ply.
     */
    @GetMapping("/why/{gameId}/{ply}")
    public ResponseEntity<DiagnosisService.Why> why(@PathVariable UUID gameId, @PathVariable int ply) {
        return diagnosis.why(gameId, ply).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** Rule precision and recall against the labels so far, plus the budget figures. */
    @GetMapping("/report")
    public DiagnosisService.Report report() {
        return diagnosis.report();
    }
}
