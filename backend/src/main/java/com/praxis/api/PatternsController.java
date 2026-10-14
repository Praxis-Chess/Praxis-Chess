package com.praxis.api;

import com.praxis.dto.PatternDto;
import com.praxis.repository.PlayerPatternRepository;
import com.praxis.service.settings.PlayerIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patterns")
public class PatternsController {

    private final PlayerPatternRepository playerPatternRepository;
    private final PlayerIdentity player;

    public PatternsController(PlayerPatternRepository playerPatternRepository, PlayerIdentity player) {
        this.playerPatternRepository = playerPatternRepository;
        this.player = player;
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
