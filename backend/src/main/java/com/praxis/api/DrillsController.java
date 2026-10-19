package com.praxis.api;

import com.praxis.domain.MoveError;
import com.praxis.dto.DrillDto;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.service.settings.PlayerIdentity;
import com.praxis.service.diagnosis.VerifiedMistakes;
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
    private final VerifiedMistakes verified;

    public DrillsController(MoveErrorRepository moveErrorRepository, PlayerIdentity player,
                            VerifiedMistakes verified) {
        this.moveErrorRepository = moveErrorRepository;
        this.player = player;
        this.verified = verified;
    }

    // Returns a randomized set of drills built from the player's own mistakes.
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<DrillDto>> getDrills(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String positions) {
        // A Training Plan session: exactly these positions, in this order
        // ("gameId:ply,gameId:ply"). Ones no longer a mistake are skipped.
        if (positions != null && !positions.isBlank()) {
            List<MoveError> chosen = new ArrayList<>();
            for (String p : positions.split(",")) {
                if (chosen.size() >= MAX_LIMIT) break;
                String[] parts = p.trim().split(":");
                if (parts.length != 2) continue;
                try {
                    moveErrorRepository.findByGameIdAndPlyWithGame(java.util.UUID.fromString(parts[0]),
                                    Integer.parseInt(parts[1])).stream()
                            .filter(e -> e.getBetterMove() != null)
                            .findFirst().ifPresent(chosen::add);
                } catch (IllegalArgumentException malformed) {
                    // skip it: a bad id in a shared link must not fail the session
                }
            }
            return ResponseEntity.ok(verified.of(chosen).stream().map(DrillDto::from).toList());
        }
        String username = player.username();
        List<MoveError> candidates = new ArrayList<>(
                moveErrorRepository.findDrillCandidatesByUsername(username));
        Collections.shuffle(candidates);

        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<DrillDto> drills = verified.of(candidates.stream().limit(capped).toList()).stream()
                .map(DrillDto::from)
                .toList();
        return ResponseEntity.ok(drills);
    }
}
