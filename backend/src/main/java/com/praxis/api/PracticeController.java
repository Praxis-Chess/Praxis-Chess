package com.praxis.api;

import com.praxis.dto.PracticeStreakDto;
import com.praxis.service.practice.PracticeStreakService;
import com.praxis.service.settings.PlayerIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    private final PracticeStreakService streakService;
    private final PlayerIdentity player;

    public PracticeController(PracticeStreakService streakService, PlayerIdentity player) {
        this.streakService = streakService;
        this.player = player;
    }

    @GetMapping("/streak")
    public ResponseEntity<PracticeStreakDto> streak() {
        return ResponseEntity.ok(streakService.compute(player.username()));
    }
}
