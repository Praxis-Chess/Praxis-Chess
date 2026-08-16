package com.praxis.api;

import com.praxis.config.AppProperties;
import com.praxis.domain.Game;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.dto.AnalysisProgressDto;
import com.praxis.dto.MoveErrorDto;
import com.praxis.pipeline.AnalysisPipelineOrchestrator;
import com.praxis.pipeline.AnalysisProgressTracker;
import com.praxis.repository.AttemptRepository;
import com.praxis.repository.CardRepository;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final MoveErrorRepository moveErrorRepository;
    private final GameRepository gameRepository;
    private final CardRepository cardRepository;
    private final AttemptRepository attemptRepository;
    private final AnalysisPipelineOrchestrator pipelineOrchestrator;
    private final AppProperties appProperties;
    private final AnalysisProgressTracker progressTracker;

    public AnalysisController(MoveErrorRepository moveErrorRepository,
                              GameRepository gameRepository,
                              CardRepository cardRepository,
                              AttemptRepository attemptRepository,
                              AnalysisPipelineOrchestrator pipelineOrchestrator,
                              AppProperties appProperties,
                              AnalysisProgressTracker progressTracker) {
        this.moveErrorRepository = moveErrorRepository;
        this.gameRepository = gameRepository;
        this.cardRepository = cardRepository;
        this.attemptRepository = attemptRepository;
        this.pipelineOrchestrator = pipelineOrchestrator;
        this.appProperties = appProperties;
        this.progressTracker = progressTracker;
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<List<MoveErrorDto>> getMoveErrors(@PathVariable UUID gameId) {
        List<MoveErrorDto> errors = moveErrorRepository.findByGameId(gameId)
                .stream()
                .map(MoveErrorDto::from)
                .toList();
        return ResponseEntity.ok(errors);
    }

    @GetMapping("/progress")
    public ResponseEntity<AnalysisProgressDto> getProgress() {
        boolean running    = progressTracker.isRunning();
        boolean patternGen = progressTracker.isPatternGenerating();
        boolean queued     = progressTracker.isQueued();
        int completed      = progressTracker.getCompleted();
        int total          = progressTracker.getTotal();

        // DB fallback: show queued if in-memory state says idle but unprocessed games exist
        // (covers restarts and cases where the flag was lost)
        if (!running && !queued && !patternGen) {
            String username = appProperties.chessCom().username();
            long pending   = gameRepository.countByUsernameAndAnalysisStatus(username, AnalysisStatus.PENDING);
            long analyzing = gameRepository.countByUsernameAndAnalysisStatus(username, AnalysisStatus.ANALYZING);
            if (pending > 0 || analyzing > 0) {
                queued = true;
                total  = (int) (pending + analyzing);
            }
        }

        int pct = total > 0 ? (int) ((completed * 100.0) / total) : 0;
        return ResponseEntity.ok(new AnalysisProgressDto(
                running, patternGen, queued, progressTracker.isStopping(), completed, total, pct,
                progressTracker.getEtaSeconds()));
    }

    /** Honoured between games, so the library is never left half-written. */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        boolean wasRunning = progressTracker.isRunning();
        progressTracker.requestStop();
        return ResponseEntity.ok(Map.of(
                "stopping", wasRunning,
                "completed", progressTracker.getCompleted(),
                "total", progressTracker.getTotal()));
    }

    @Transactional
    @PostMapping("/analyze-pending")
    public ResponseEntity<Map<String, Object>> analyzePending() {
        String username = appProperties.chessCom().username();
        List<Game> pending = gameRepository.findByUsernameAndAnalysisStatus(username, AnalysisStatus.PENDING);
        if (!pending.isEmpty()) {
            progressTracker.setQueued(true);
            pipelineOrchestrator.analyzeGames(pending, username);
        }
        return ResponseEntity.ok(Map.of(
                "message", "Pending games queued for analysis",
                "games_queued", pending.size()));
    }

    @Transactional
    @PostMapping("/reanalyze")
    public ResponseEntity<Map<String, Object>> reanalyzeAll() {
        String username = appProperties.chessCom().username();
        List<Game> games = gameRepository.findByUsernameOrderByPlayedAtDesc(username);

        // Delete in FK order: attempts → cards → move_errors → reset game status.
        // Cards hold a non-null FK to move_errors; attempts hold a non-null FK to cards.
        // Skipping this order would cause a DataIntegrityViolationException.
        attemptRepository.deleteByCardUsername(username);
        cardRepository.deleteByUsername(username);

        for (Game game : games) {
            moveErrorRepository.deleteByGameId(game.getId());
            game.setAnalysisStatus(AnalysisStatus.PENDING);
            game.setAnalyzedAt(null);
            gameRepository.save(game);
        }

        if (!games.isEmpty()) {
            progressTracker.setQueued(true);
            pipelineOrchestrator.analyzeGames(games, username);
        }

        return ResponseEntity.ok(Map.of(
                "message", "Reanalysis queued",
                "games_queued", games.size()));
    }
}
