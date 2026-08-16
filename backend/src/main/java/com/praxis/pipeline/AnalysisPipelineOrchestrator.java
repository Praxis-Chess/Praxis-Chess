package com.praxis.pipeline;

import com.praxis.domain.Game;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.repository.GameRepository;
import com.praxis.service.PatternAggregator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AnalysisPipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipelineOrchestrator.class);

    private final GameAnalysisTransactionService gameAnalysisTransactionService;
    private final GameRepository gameRepository;
    private final PatternAggregator patternAggregator;
    private final AnalysisProgressTracker progressTracker;

    public AnalysisPipelineOrchestrator(GameAnalysisTransactionService gameAnalysisTransactionService,
                                        GameRepository gameRepository,
                                        PatternAggregator patternAggregator,
                                        AnalysisProgressTracker progressTracker) {
        this.gameAnalysisTransactionService = gameAnalysisTransactionService;
        this.gameRepository = gameRepository;
        this.patternAggregator = patternAggregator;
        this.progressTracker = progressTracker;
    }

    @PostConstruct
    public void recoverStuckGames() {
        int count = gameRepository.resetAnalysisStatus(AnalysisStatus.ANALYZING, AnalysisStatus.PENDING);
        if (count > 0) {
            log.warn("Recovered {} game(s) stuck in ANALYZING state from a previous crash", count);
        }
    }

    @Async("analysisExecutor")
    public void analyzeGames(List<Game> games, String username) {
        log.info("Starting analysis pipeline for {} games (user: {})", games.size(), username);
        progressTracker.start(games.size());

        for (Game game : games) {
            if (progressTracker.isStopRequested()) {
                log.info("Analysis stopped by user after {} of {} games",
                        progressTracker.getCompleted(), games.size());
                progressTracker.finish();
                return;
            }
            try {
                // Each game commits independently via REQUIRES_NEW — crash mid-run
                // loses only the in-flight game; all prior games remain ANALYZED.
                gameAnalysisTransactionService.analyzeOne(game);
            } catch (Exception e) {
                log.error("Pipeline failed for game {}: {}", game.getChessComId(), e.getMessage(), e);
                try {
                    gameAnalysisTransactionService.markFailed(game);
                } catch (Exception ex) {
                    log.error("Could not mark game {} as FAILED: {}", game.getChessComId(), ex.getMessage());
                }
            } finally {
                progressTracker.increment();
            }
        }

        log.info("Analysis pipeline complete. Running pattern aggregation...");
        progressTracker.setPatternGenerating(true);
        try {
            patternAggregator.recompute(username);
        } finally {
            progressTracker.finish();
        }
    }
}
