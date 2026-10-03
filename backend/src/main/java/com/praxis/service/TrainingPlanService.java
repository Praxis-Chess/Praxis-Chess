package com.praxis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.domain.PlayerPattern;
import com.praxis.domain.TrainingPlan;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.repository.GameRepository;
import com.praxis.repository.PlayerPatternRepository;
import com.praxis.repository.TrainingPlanRepository;
import com.praxis.service.ai.AnalysisLlmClient;
import com.praxis.service.ai.PromptTemplates;
import com.praxis.service.ai.dto.TrainingPlanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
public class TrainingPlanService {

    private static final Logger log = LoggerFactory.getLogger(TrainingPlanService.class);

    private final PlayerPatternRepository playerPatternRepository;
    private final TrainingPlanRepository trainingPlanRepository;
    private final GameRepository gameRepository;
    private final AnalysisLlmClient analysisLlm;
    private final ObjectMapper objectMapper;

    public TrainingPlanService(PlayerPatternRepository playerPatternRepository,
                               TrainingPlanRepository trainingPlanRepository,
                               GameRepository gameRepository,
                               AnalysisLlmClient analysisLlm,
                               ObjectMapper objectMapper) {
        this.playerPatternRepository = playerPatternRepository;
        this.trainingPlanRepository = trainingPlanRepository;
        this.gameRepository = gameRepository;
        this.analysisLlm = analysisLlm;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TrainingPlan generate(String username) {
        Optional<PlayerPattern> patternOpt = playerPatternRepository
                .findTopByUsernameOrderByComputedAtDesc(username);

        if (patternOpt.isEmpty()) {
            throw new IllegalStateException("No pattern data found for " + username + ". Analyze some games first.");
        }

        PlayerPattern pattern = patternOpt.get();
        int gamesAnalyzed = (int) gameRepository.countByUsernameAndAnalysisStatus(username, AnalysisStatus.ANALYZED);

        String patternJson = buildPatternJson(pattern);
        String openingJson = pattern.getOpeningAccuracy() != null ? pattern.getOpeningAccuracy() : "{}";
        String prompt = PromptTemplates.trainingPlan(patternJson, openingJson);

        TrainingPlanResult result = analysisLlm.analyzeReport(prompt, TrainingPlanResult.class);

        String planJson = toJson(result);
        String openingsToDrill = toJson(result.openingsToDrill());
        String tacticalPatterns = toJson(result.tacticalPatternsToStudy());

        TrainingPlan plan = TrainingPlan.builder()
                .username(username)
                .generatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .basedOnGames(gamesAnalyzed)
                .planJson(planJson)
                .openingsToDrill(openingsToDrill)
                .tacticalPatterns(tacticalPatterns)
                .build();

        return trainingPlanRepository.save(plan);
    }

    public Optional<TrainingPlan> getLatest(String username) {
        return trainingPlanRepository.findTopByUsernameOrderByGeneratedAtDesc(username);
    }

    private String buildPatternJson(PlayerPattern p) {
        var summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("primary_weakness", p.getPrimaryWeakness());
        summary.put("secondary_weakness", p.getSecondaryWeakness());
        summary.put("tertiary_weakness", p.getTertiaryWeakness());
        summary.put("critical_move_range", p.getCriticalMoveRange());
        summary.put("dominant_motif", p.getDominantMotif());
        summary.put("opening_assessment", p.getOpeningAssessment());
        summary.put("mistakes_by_phase", java.util.Map.of(
                "opening", p.getMistakesOpening(),
                "middlegame", p.getMistakesMiddlegame(),
                "endgame", p.getMistakesEndgame()));
        return toJson(summary);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
