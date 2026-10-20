package com.praxis.api;

import org.springframework.web.bind.annotation.RequestBody;
import com.praxis.service.settings.PlayerIdentity;
import com.praxis.service.settings.SettingsService;
import com.praxis.domain.AnalysisSettings;
import com.praxis.domain.Game;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.dto.AnalysisProgressDto;
import com.praxis.dto.MoveErrorDto;
import com.praxis.pipeline.AnalysisPipelineOrchestrator;
import com.praxis.pipeline.AnalysisProgressTracker;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.dto.BackupDto;
import com.praxis.service.backup.BackupService;
import com.praxis.service.diagnosis.DiagnosisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final MoveErrorRepository moveErrorRepository;
    private final GameRepository gameRepository;
    private final AnalysisPipelineOrchestrator pipelineOrchestrator;
    private final PlayerIdentity player;
    private final AnalysisProgressTracker progressTracker;
    private final SettingsService settings;
    private final DiagnosisService diagnosis;
    private final BackupService backups;
    private final TransactionTemplate tx;

    public AnalysisController(MoveErrorRepository moveErrorRepository,
                              GameRepository gameRepository,
                              AnalysisPipelineOrchestrator pipelineOrchestrator,
                              PlayerIdentity player,
                              AnalysisProgressTracker progressTracker,
                              SettingsService settings,
                              DiagnosisService diagnosis,
                              BackupService backups,
                              TransactionTemplate tx) {
        this.moveErrorRepository = moveErrorRepository;
        this.gameRepository = gameRepository;
        this.pipelineOrchestrator = pipelineOrchestrator;
        this.player = player;
        this.progressTracker = progressTracker;
        this.settings = settings;
        this.diagnosis = diagnosis;
        this.backups = backups;
        this.tx = tx;
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<List<MoveErrorDto>> getMoveErrors(@PathVariable UUID gameId) {
        var verified = diagnosis.currentForGame(gameId);
        List<MoveErrorDto> errors = moveErrorRepository.findByGameId(gameId)
                .stream()
                .map(e -> MoveErrorDto.from(e, verified.get(e.getMoveNumber())))
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
            String username = player.username();
            long pending   = gameRepository.countByUsernameAndAnalysisStatusInTimeClasses(username, AnalysisStatus.PENDING,
                    settings.analysisTimeClasses(), SettingsService.TIME_CLASSES);
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
        String username = player.username();
        List<Game> pending = gameRepository.findByUsernameAndAnalysisStatus(username, AnalysisStatus.PENDING)
                .stream().filter(settings::inAnalysisScope).toList();
        if (!pending.isEmpty()) {
            progressTracker.setQueued(true);
            pipelineOrchestrator.analyzeGames(pending, username);
        }
        return ResponseEntity.ok(Map.of(
                "message", "Pending games queued for analysis",
                "games_queued", pending.size()));
    }

    /**
     * Re-analysis rewrites the analysis of many games at once, so a backup comes
     * first, even though nothing is deleted any more (old rows are superseded).
     * 409 with {@code backup_failed} when none could be taken; the UI then
     * offers {@code skip_backup=true}. The wait happens outside the
     * transaction, so it holds no database connection.
     */
    @PostMapping("/reanalyze")
    public ResponseEntity<Map<String, Object>> reanalyzeAll(
            @RequestParam(name = "outdated_only", defaultValue = "false") boolean outdatedOnly,
            @RequestParam(name = "skip_backup", defaultValue = "false") boolean skipBackup) {
        BackupDto.Result backup = null;
        if (!skipBackup) {
            backup = backups.snapshot("before-reanalysis", Duration.ofMinutes(5));
            if (!backup.ok()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "No backup could be taken first. " + backup.error(),
                        "backup_failed", true));
            }
        }
        Map<String, Object> body = new HashMap<>(tx.execute(status -> queueReanalysis(outdatedOnly)));
        if (backup != null) body.put("backup", backup.file());
        return ResponseEntity.ok(body);
    }

    /**
     * Re-analyse these games, as one job (the executor's queue holds 50, so one
     * call per game cannot carry a large set). A backup is taken first, as for
     * Re-analyse All. Used to repair accuracies stored before the 17 Aug sign fix
     * (POLISH_PLAN.md N23).
     */
    @PostMapping("/reanalyze-games")
    public ResponseEntity<Map<String, Object>> reanalyzeGames(
            @RequestBody Map<String, List<UUID>> body,
            @RequestParam(name = "skip_backup", defaultValue = "false") boolean skipBackup) {
        List<UUID> ids = body.getOrDefault("game_ids", List.of());
        BackupDto.Result backup = null;
        if (!skipBackup) {
            backup = backups.snapshot("before-reanalysis", Duration.ofMinutes(5));
            if (!backup.ok()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "No backup could be taken first. " + backup.error(),
                        "backup_failed", true));
            }
        }
        Map<String, Object> out = new HashMap<>(tx.execute(status -> {
            List<Game> games = gameRepository.findAllById(ids);
            for (Game game : games) {
                game.setAnalysisStatus(AnalysisStatus.PENDING);
                game.setAnalyzedAt(null);
                gameRepository.save(game);
            }
            if (!games.isEmpty()) {
                progressTracker.setQueued(true);
                pipelineOrchestrator.analyzeGames(games, player.username());
            }
            return Map.<String, Object>of("message", "Reanalysis queued", "games_queued", games.size());
        }));
        if (backup != null) out.put("backup", backup.file());
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> queueReanalysis(boolean outdatedOnly) {
        String username = player.username();
        // Every version that measures the same way as the active one, not just its
        // id — the same set the coverage counts as current, so the button cannot
        // queue games the page did not offer.
        Set<Long> sameRuler = settings.engineEquivalentIds(AnalysisSettings.LIBRARY);
        // Inside the analysis range; with outdated_only, just the games not yet
        // analysed with the current settings — "re-analyse with current settings".
        List<Game> games = gameRepository.findByUsernameOrderByPlayedAtDesc(username).stream()
                .filter(settings::inAnalysisScope)
                .filter(g -> !outdatedOnly || !sameRuler.contains(g.getAnalysisSettingsId()))
                .toList();
        // Nothing is deleted (Phase 12). Each game's analysis supersedes its old
        // mistake rows inside its own transaction, and drill cards are re-linked to
        // the new rows (GameAnalysisTransactionService); until then the previous
        // analysis stays visible.
        for (Game game : games) {
            game.setAnalysisStatus(AnalysisStatus.PENDING);
            game.setAnalyzedAt(null);
            gameRepository.save(game);
        }

        if (!games.isEmpty()) {
            progressTracker.setQueued(true);
            pipelineOrchestrator.analyzeGames(games, username);
        }

        return Map.of(
                "message", "Reanalysis queued",
                "games_queued", games.size());
    }
}
