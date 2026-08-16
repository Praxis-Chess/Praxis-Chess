package com.praxis.service.ai;

import com.praxis.config.AppProperties;
import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.dto.TodayInsightDto;
import com.praxis.dto.TodayInsightDto.Evidence;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.service.ai.dto.OllamaOptions;
import com.praxis.service.ai.dto.OllamaRequest;
import com.praxis.service.ai.dto.OllamaGenerateResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a single structured training recommendation for the "Today" page.
 *
 * Phase 5 constraints:
 *   - availableFields contract: the model receives only aggregate statistics, never raw PGN or FEN
 *   - Temperature ≤ 0.3 (uses OllamaOptions.structured() = 0.2)
 *   - Response validated against TodayInsightDto.isValid(); falls back to template on failure
 *   - The model may not reference any field that is not present in the prompt
 */
@Service
public class TodayInsightService {

    private static final Logger log = LoggerFactory.getLogger(TodayInsightService.class);

    private final MoveErrorRepository moveErrorRepository;
    private final GameRepository gameRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public TodayInsightService(MoveErrorRepository moveErrorRepository,
                               GameRepository gameRepository,
                               AppProperties appProperties,
                               ObjectMapper objectMapper) {
        this.moveErrorRepository = moveErrorRepository;
        this.gameRepository = gameRepository;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(appProperties.ollama().baseUrl())
                .build();
    }

    public TodayInsightDto generate() {
        String username = appProperties.chessCom().username();
        AvailableFields fields = buildFields(username);
        String prompt = buildPrompt(fields);

        try {
            String model = appProperties.ollama().reportModel();
            if (model == null || model.isBlank()) model = appProperties.ollama().model();

            OllamaRequest req = new OllamaRequest(
                    model, prompt, false, "json", 256, "2h", 2048,
                    OllamaOptions.structured()); // temperature = 0.2

            OllamaGenerateResponse raw = restClient.post()
                    .uri("/api/generate")
                    .body(req)
                    .retrieve()
                    .body(OllamaGenerateResponse.class);

            if (raw == null || raw.response() == null) throw new RuntimeException("null response");

            String json = raw.response()
                    .replaceAll("```json", "").replaceAll("```", "").trim();

            TodayInsightDto result = objectMapper.readValue(json, TodayInsightDto.class);
            if (result.isValid()) {
                log.debug("TodayInsight generated successfully");
                return result;
            }
            log.warn("TodayInsight failed validation: {}", json);
        } catch (Exception e) {
            log.warn("TodayInsight LLM call failed: {}", e.getMessage());
        }

        return templateFallback(fields);
    }

    // --- Field extraction ---

    /** Aggregated statistics the model is allowed to reference. */
    record AvailableFields(
            int gamesAnalysed,
            int totalBlunders,
            int openingBlunders, int middlegameBlunders, int endgameBlunders,
            String worstPhase,
            String dominantMotif,
            int openingGames,
            double avgAccuracy
    ) {}

    private AvailableFields buildFields(String username) {
        List<MoveError> errors = moveErrorRepository.findAllByUsername(username);
        List<MoveError> blunders = errors.stream()
                .filter(e -> e.getSeverity() == Severity.BLUNDER).toList();

        int ob = (int) blunders.stream().filter(e -> e.getGamePhase() == GamePhase.OPENING).count();
        int mb = (int) blunders.stream().filter(e -> e.getGamePhase() == GamePhase.MIDDLEGAME).count();
        int eb = (int) blunders.stream().filter(e -> e.getGamePhase() == GamePhase.ENDGAME).count();

        String worstPhase = mb >= ob && mb >= eb ? "middlegame"
                : eb >= ob ? "endgame" : "opening";

        String dominantMotif = blunders.stream()
                .filter(e -> e.getTacticalMotif() != null)
                .collect(Collectors.groupingBy(e -> e.getTacticalMotif().name(), Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("POSITIONAL");

        long games = gameRepository.findByUsernameOrderByPlayedAtDesc(username).stream()
                .filter(Game::hasAccuracy).count();
        double avgAcc = gameRepository.findByUsernameOrderByPlayedAtDesc(username).stream()
                .filter(Game::hasAccuracy)
                .mapToDouble(Game::getAccuracy)
                .average().orElse(0);

        return new AvailableFields(
                (int) games, blunders.size(), ob, mb, eb,
                worstPhase, dominantMotif, (int) games,
                Math.round(avgAcc * 10.0) / 10.0
        );
    }

    private String buildPrompt(AvailableFields f) {
        return """
            You are a chess coach. Based ONLY on the statistics below (availableFields), generate a single
            concrete training recommendation for today. Do NOT invent statistics or reference information
            not present below.

            availableFields:
            - games_analysed: %d
            - avg_accuracy: %.1f%%
            - total_blunders: %d
            - blunders_by_phase: opening=%d, middlegame=%d, endgame=%d
            - worst_phase: %s
            - dominant_tactical_motif: %s

            Respond ONLY with this JSON (no other text):
            {
              "title": "<short imperative title, max 8 words>",
              "evidence": {
                "metric": "<which stat drove this recommendation>",
                "value": "<the stat value as a human-readable string>",
                "sample_size": <number of games/positions this is based on>
              },
              "action": "<one concrete drill or study task, 10-20 words>",
              "expected_minutes": <integer 5-30>
            }
            """.formatted(f.gamesAnalysed(), f.avgAccuracy(), f.totalBlunders(),
                f.openingBlunders(), f.middlegameBlunders(), f.endgameBlunders(),
                f.worstPhase(), f.dominantMotif());
    }

    /** Deterministic template fallback — no LLM needed. */
    private TodayInsightDto templateFallback(AvailableFields f) {
        String phase = f.worstPhase();
        String title = "Drill your " + phase + " blunders";
        String value = f.totalBlunders() + " blunders in " + f.gamesAnalysed() + " games";
        String action = "Re-solve the 5 worst " + phase + " positions from your drill queue";
        return new TodayInsightDto(title,
                new Evidence("Blunder phase distribution", value, f.gamesAnalysed()),
                action, 15);
    }
}
