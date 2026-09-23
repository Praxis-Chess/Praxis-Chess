package com.praxis.api;

import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import com.praxis.evidence.EvidenceExplainService;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One game re-diagnosed from a backend-rendered evidence block.
 *
 * <p>A read-only side door onto the analysis already in the database: it writes
 * nothing, versions nothing, and leaves the pipeline's own explanations exactly
 * where they are. That separation is the point — the stored explanations are the
 * baseline everything later is measured against, and re-running them through a
 * different model in place would destroy the comparison it exists to serve.
 */
@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

    private final GameRepository games;
    private final MoveErrorRepository moveErrors;
    private final EvidenceExplainService explainer;

    public EvidenceController(GameRepository games, MoveErrorRepository moveErrors,
                              EvidenceExplainService explainer) {
        this.games = games;
        this.moveErrors = moveErrors;
        this.explainer = explainer;
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<Map<String, Object>> explain(
            @PathVariable UUID gameId,
            @RequestParam(defaultValue = "3") int limit,
            @RequestParam(defaultValue = "praxis-phase1") String model) {

        Game game = games.findById(gameId).orElse(null);
        if (game == null) return ResponseEntity.notFound().build();

        // Worst first, which is also the order the pipeline should have used and
        // does not — see reports/baseline.md §2.
        List<MoveError> flagged = moveErrors.findByGameId(gameId).stream()
                .sorted(Comparator.comparing((MoveError m) -> severityRank(m.getSeverity().name()))
                        .thenComparing(MoveError::getMoveNumber))
                .limit(Math.max(1, Math.min(limit, 10)))
                .toList();

        List<EvidenceExplainService.Explained> explained = new ArrayList<>();
        long engineMs = 0;
        long modelMs = 0;
        for (MoveError error : flagged) {
            var result = explainer.explain(
                    error.getFenPosition(),
                    null,
                    error.getMovePlayed(),
                    error.getMoveNumber(),
                    error.getSeverity().name(),
                    error.getGamePhase() == null ? "MIDDLEGAME" : error.getGamePhase().name(),
                    model);
            if (result.isEmpty()) continue;
            explained.add(result.get());
            engineMs += result.get().engineMs();
            modelMs += result.get().modelMs();
        }

        // A LinkedHashMap, not Map.of: that factory stops at ten pairs, and the
        // eleventh is a compile error whose message names none of them.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game_id", gameId.toString());
        body.put("player_color", nullSafe(game.getPlayerColor()));
        body.put("opening", nullSafe(game.getOpeningName()));
        body.put("played_at", game.getPlayedAt() == null ? "" : game.getPlayedAt().toString());
        body.put("model", model);
        body.put("flagged_total", moveErrors.findByGameId(gameId).size());
        body.put("explained", explained);
        body.put("engine_ms_total", engineMs);
        body.put("model_ms_total", modelMs);
        body.put("engine_ms_per_mistake", explained.isEmpty() ? 0 : engineMs / explained.size());
        body.put("model_ms_per_mistake", explained.isEmpty() ? 0 : modelMs / explained.size());
        return ResponseEntity.ok(body);
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "BLUNDER" -> 0;
            case "MISTAKE" -> 1;
            default -> 2;
        };
    }

    private static Object nullSafe(Object value) {
        return value == null ? "" : value;
    }
}
