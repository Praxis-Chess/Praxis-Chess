package com.praxis.api;

import com.praxis.dto.TrainingPlanDto;
import com.praxis.service.TrainingPlanService;
import com.praxis.service.settings.PlayerIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/training-plan")
public class TrainingPlanController {

    private final TrainingPlanService trainingPlanService;
    private final PlayerIdentity player;
    private final com.praxis.service.PlanRecallService recall;

    public TrainingPlanController(TrainingPlanService trainingPlanService, PlayerIdentity player,
                                  com.praxis.service.PlanRecallService recall) {
        this.trainingPlanService = trainingPlanService;
        this.player = player;
        this.recall = recall;
    }

    @GetMapping
    public ResponseEntity<TrainingPlanDto> getLatest() {
        return trainingPlanService.getLatest(player.username())
                .map(TrainingPlanDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    public record SessionResult(int solved, int total) {}

    /** A Training Plan session finished in Drills. */
    @PostMapping("/{id}/sessions/{session}")
    public ResponseEntity<TrainingPlanDto> sessionDone(@PathVariable java.util.UUID id, @PathVariable int session,
                                                       @RequestBody SessionResult result) {
        return trainingPlanService.sessionDone(id, session, result.solved(), result.total())
                .map(TrainingPlanDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** F10: each focus in games played since the plan, against before. Verified or silent: see SincePlan. */
    @GetMapping("/{id}/since")
    public ResponseEntity<com.praxis.report.SincePlan.Result> since(@PathVariable java.util.UUID id) {
        return trainingPlanService.sincePlan(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** F11: reviews since the plan, for the plan's positions that are also cards in the review deck. */
    @GetMapping("/{id}/recall")
    public ResponseEntity<com.praxis.service.PlanRecallService.Result> recall(@PathVariable java.util.UUID id) {
        return recall.recall(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    public record Answer(java.util.UUID gameId, int ply, java.util.UUID moveErrorId, boolean correct,
                         int wrongTries, boolean revealed, Long responseMs) {}

    /** One position answered in a Training Plan session: kept per position, not only as a total (F12). */
    @PostMapping("/{id}/sessions/{session}/answers")
    public ResponseEntity<Void> answer(@PathVariable java.util.UUID id, @PathVariable int session,
                                       @RequestBody Answer a) {
        if (a.gameId() == null) return ResponseEntity.badRequest().build();
        return trainingPlanService.recordAnswer(id, session, a.gameId(), a.ply(), a.moveErrorId(), a.correct(),
                        a.wrongTries(), a.revealed(), a.responseMs())
                .<ResponseEntity<Void>>map(saved -> ResponseEntity.noContent().build())
                .orElse(ResponseEntity.notFound().build());
    }

    /** 409 with a reason when there is nothing to plan from yet. */
    @PostMapping("/generate")
    public ResponseEntity<?> generate() {
        try {
            return ResponseEntity.ok(TrainingPlanDto.from(trainingPlanService.generate(player.username())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(java.util.Map.of("error", e.getMessage()));
        }
    }
}
