package com.praxis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.domain.MoveError;
import com.praxis.domain.PlayerPattern;
import com.praxis.domain.enums.AnalysisState;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.domain.enums.GamePhase;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.repository.PlayerPatternRepository;
import com.praxis.service.ai.AnalysisLlmClient;
import com.praxis.service.ai.PromptTemplates;
import com.praxis.service.ai.dto.PatternReportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PatternAggregator {

    private static final Logger log = LoggerFactory.getLogger(PatternAggregator.class);
    private static final int TIME_PRESSURE_SECONDS = 30;

    private final MoveErrorRepository moveErrorRepository;
    private final GameRepository gameRepository;
    private final PlayerPatternRepository playerPatternRepository;
    private final AnalysisLlmClient analysisLlm;
    private final ObjectMapper objectMapper;

    public PatternAggregator(MoveErrorRepository moveErrorRepository,
                             GameRepository gameRepository,
                             PlayerPatternRepository playerPatternRepository,
                             AnalysisLlmClient analysisLlm,
                             ObjectMapper objectMapper) {
        this.moveErrorRepository = moveErrorRepository;
        this.gameRepository = gameRepository;
        this.playerPatternRepository = playerPatternRepository;
        this.analysisLlm = analysisLlm;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recompute(String username) {
        log.info("Recomputing patterns for {}", username);

        // All mistakes — used for phase/move-range/opening distributions
        List<MoveError> allErrors = moveErrorRepository.findAllByUsername(username);
        // Only LLM-explained mistakes — used for motif frequency (others have no motif)
        List<MoveError> explainedErrors = moveErrorRepository.findExplainedByUsername(username);

        if (allErrors.isEmpty()) {
            log.info("No move errors found for {}, skipping pattern aggregation", username);
            return;
        }

        int gamesAnalyzed = (int) gameRepository.countByUsernameAndAnalysisStatus(
                username, AnalysisStatus.ANALYZED);

        // Move range distribution (all mistakes)
        int m1to10  = (int) allErrors.stream().filter(e -> e.getMoveNumber() <= 10).count();
        int m11to20 = (int) allErrors.stream().filter(e -> e.getMoveNumber() >= 11 && e.getMoveNumber() <= 20).count();
        int m21to30 = (int) allErrors.stream().filter(e -> e.getMoveNumber() >= 21 && e.getMoveNumber() <= 30).count();
        int m31plus = (int) allErrors.stream().filter(e -> e.getMoveNumber() > 30).count();

        // Phase breakdown (all mistakes)
        int opening    = (int) allErrors.stream().filter(e -> e.getGamePhase() == GamePhase.OPENING).count();
        int middlegame = (int) allErrors.stream().filter(e -> e.getGamePhase() == GamePhase.MIDDLEGAME).count();
        int endgame    = (int) allErrors.stream().filter(e -> e.getGamePhase() == GamePhase.ENDGAME).count();

        // Time-pressure × phase cross-tab (all mistakes with clock data)
        Map<String, Long> timePressureByPhase = allErrors.stream()
                .filter(e -> e.getClockRemaining() != null && e.getClockRemaining() <= TIME_PRESSURE_SECONDS)
                .collect(Collectors.groupingBy(
                        e -> e.getGamePhase() != null ? e.getGamePhase().name() : "UNKNOWN",
                        Collectors.counting()));

        // Motif frequency — only EXPLAINED mistakes (others have no motif data)
        Map<String, Long> motifFreqRaw = explainedErrors.stream()
                .filter(e -> e.getTacticalMotif() != null)
                .collect(Collectors.groupingBy(e -> e.getTacticalMotif().name(), Collectors.counting()));
        Map<String, Integer> motifFreq = motifFreqRaw.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().intValue()));

        String moveRangeDist = "Moves 1-10: %d, Moves 11-20: %d, Moves 21-30: %d, Moves 31+: %d"
                .formatted(m1to10, m11to20, m21to30, m31plus);
        String timePressureDist = toJson(timePressureByPhase);
        String motifFreqJson  = toJson(motifFreq);
        String openingAccJson = buildOpeningAccuracyJson(username);

        String prompt = PromptTemplates.patternReport(
                gamesAnalyzed, allErrors.size(), moveRangeDist,
                timePressureDist, motifFreqJson, openingAccJson);

        PatternReportResult llmResult = null;
        try {
            llmResult = analysisLlm.analyzeReport(prompt, PatternReportResult.class);
        } catch (Exception e) {
            log.warn("Ollama pattern report failed: {}", e.getMessage());
        }

        PlayerPattern pattern = PlayerPattern.builder()
                .username(username)
                .gamesAnalyzed(gamesAnalyzed)
                .computedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .mistakesMoves1to10(m1to10)
                .mistakesMoves11to20(m11to20)
                .mistakesMoves21to30(m21to30)
                .mistakesMoves31Plus(m31plus)
                .mistakesOpening(opening)
                .mistakesMiddlegame(middlegame)
                .mistakesEndgame(endgame)
                .motifFrequency(motifFreqJson)
                .openingAccuracy(openingAccJson)
                .primaryWeakness(llmResult != null ? llmResult.primaryWeakness() : null)
                .secondaryWeakness(llmResult != null ? llmResult.secondaryWeakness() : null)
                .tertiaryWeakness(llmResult != null ? llmResult.tertiaryWeakness() : null)
                .criticalMoveRange(llmResult != null ? llmResult.criticalMoveRange() : null)
                .dominantMotif(llmResult != null ? llmResult.dominantMotif() : null)
                .openingAssessment(llmResult != null ? llmResult.openingAssessment() : null)
                .build();

        playerPatternRepository.save(pattern);
        log.info("Pattern aggregation complete for {} ({} total, {} explained, {} time-pressure)",
                username, allErrors.size(), explainedErrors.size(), timePressureByPhase.values().stream().mapToLong(Long::longValue).sum());
    }

    private String buildOpeningAccuracyJson(String username) {
        Map<String, Long> ecoErrors = moveErrorRepository.findAllByUsername(username).stream()
                .filter(e -> e.getGame().getOpeningEco() != null)
                .collect(Collectors.groupingBy(
                        e -> e.getGame().getOpeningEco(),
                        Collectors.counting()));
        return toJson(ecoErrors);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
