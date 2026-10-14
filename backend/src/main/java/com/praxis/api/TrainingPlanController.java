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

    public TrainingPlanController(TrainingPlanService trainingPlanService, PlayerIdentity player) {
        this.trainingPlanService = trainingPlanService;
        this.player = player;
    }

    @GetMapping
    public ResponseEntity<TrainingPlanDto> getLatest() {
        return trainingPlanService.getLatest(player.username())
                .map(TrainingPlanDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/generate")
    public ResponseEntity<TrainingPlanDto> generate() {
        TrainingPlanDto dto = TrainingPlanDto.from(
                trainingPlanService.generate(player.username()));
        return ResponseEntity.ok(dto);
    }
}
