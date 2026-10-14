package com.praxis.api;

import com.praxis.domain.MoveError;
import com.praxis.dto.DrillDto;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.service.settings.PlayerIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/drills")
public class DrillsController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT     = 50;

    private final MoveErrorRepository moveErrorRepository;
    private final PlayerIdentity player;

    public DrillsController(MoveErrorRepository moveErrorRepository, PlayerIdentity player) {
        this.moveErrorRepository = moveErrorRepository;
        this.player = player;
    }

    // Returns a randomized set of drills built from the player's own mistakes.
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<DrillDto>> getDrills(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        String username = player.username();
        List<MoveError> candidates = new ArrayList<>(
                moveErrorRepository.findDrillCandidatesByUsername(username));
        Collections.shuffle(candidates);

        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<DrillDto> drills = candidates.stream()
                .limit(capped)
                .map(DrillDto::from)
                .toList();
        return ResponseEntity.ok(drills);
    }
}
