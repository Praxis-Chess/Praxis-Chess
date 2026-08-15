package com.praxis.pipeline;

import com.praxis.config.AppProperties;
import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import com.praxis.domain.enums.AnalysisState;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.domain.enums.TacticalMotif;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.service.EcoTable;
import com.praxis.service.ai.OllamaAnalysisClient;
import com.praxis.service.ai.PromptTemplates;
import com.praxis.service.ai.dto.MoveAnalysisResult;
import com.praxis.service.analysis.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Wraps per-game analysis in REQUIRES_NEW so each game commits independently.
 *
 * Pipeline per game (overlapped):
 *   Main thread  — Stockfish MultiPV depth-18 per candidate (CPU-bound)
 *   Consumer thread — Ollama HTTP calls per candidate (GPU-bound)
 * Both run concurrently via a LinkedBlockingQueue so GPU and CPU are used simultaneously.
 */
@Service
public class GameAnalysisTransactionService {

    private static final Logger log = LoggerFactory.getLogger(GameAnalysisTransactionService.class);
    private static final int MAX_OLLAMA_CALLS_PER_GAME = 3;
    private static final int OLLAMA_MAX_RETRIES        = 3;

    private final PgnParserService pgnParserService;
    private final PositionEvaluator positionEvaluator;
    private final MistakeCandidateFilter candidateFilter;
    private final StockfishService stockfishService;
    private final OllamaAnalysisClient ollamaClient;
    private final GameRepository gameRepository;
    private final MoveErrorRepository moveErrorRepository;
    private final AppProperties appProperties;
    private final EcoTable ecoTable;

    public GameAnalysisTransactionService(PgnParserService pgnParserService,
                                          PositionEvaluator positionEvaluator,
                                          MistakeCandidateFilter candidateFilter,
                                          StockfishService stockfishService,
                                          OllamaAnalysisClient ollamaClient,
                                          GameRepository gameRepository,
                                          MoveErrorRepository moveErrorRepository,
                                          AppProperties appProperties,
                                          EcoTable ecoTable) {
        this.pgnParserService = pgnParserService;
        this.positionEvaluator = positionEvaluator;
        this.candidateFilter = candidateFilter;
        this.stockfishService = stockfishService;
        this.ollamaClient = ollamaClient;
        this.gameRepository = gameRepository;
        this.moveErrorRepository = moveErrorRepository;
        this.appProperties = appProperties;
        this.ecoTable = ecoTable;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void analyzeOne(Game game) {
        log.debug("Analyzing game: {}", game.getChessComId());
        game.setAnalysisStatus(AnalysisStatus.ANALYZING);
        gameRepository.save(game);

        String username = appProperties.chessCom().username();
        ParsedGame parsedGame = pgnParserService.parse(
                game.getId().toString(), game.getRawPgn(), username);

        if (parsedGame.moves().isEmpty()) {
            log.warn("No moves parsed for game {}, marking FAILED", game.getChessComId());
            game.setAnalysisStatus(AnalysisStatus.FAILED);
            gameRepository.save(game);
            return;
        }

        if (game.getOpeningEco() == null && !parsedGame.openingEco().isEmpty()) {
            game.setOpeningEco(parsedGame.openingEco());
            // Prefer PGN header name; fall back to ECO table when absent or blank.
            String pgnName = parsedGame.openingName();
            String resolvedName = (pgnName != null && !pgnName.isBlank() && !pgnName.equals("?"))
                    ? pgnName
                    : ecoTable.lookup(parsedGame.openingEco());
            game.setOpeningName(resolvedName);
        }

        List<Double> scores = positionEvaluator.evaluateAll(parsedGame.moves());

        if (game.getAccuracy() == null && !scores.isEmpty()) {
            game.setAccuracy(computeAccuracy(parsedGame, scores));
        }

        // Fast first pass: score-based filtering, no Stockfish calls yet
        List<CandidateMove> candidates = candidateFilter.filterCandidates(parsedGame, scores);
        List<CandidateMove> sorted = candidates.stream()
                .sorted(Comparator.comparingDouble(CandidateMove::materialSwing))
                .toList();

        log.debug("Found {} candidate mistakes in game {}", sorted.size(), game.getChessComId());

        // Overlapped pipeline:
        //   This thread   → Stockfish MultiPV depth-18 per candidate (CPU)
        //   Consumer thread → Ollama HTTP explanation per top-N candidate (GPU)
        LinkedBlockingQueue<Optional<CandidateMove>> ollamaQueue = new LinkedBlockingQueue<>();
        String playerColor = parsedGame.playerColor();
        String gameId = game.getChessComId();

        CompletableFuture<List<OllamaResult>> ollamaFuture = CompletableFuture.supplyAsync(() -> {
            List<OllamaResult> results = new ArrayList<>();
            try {
                while (true) {
                    Optional<CandidateMove> item = ollamaQueue.take();
                    if (item.isEmpty()) break; // poison pill
                    results.add(callOllamaWithRetry(item.get(), playerColor));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Ollama consumer interrupted for game {}", gameId);
            }
            return results;
        });

        // Main thread: enrich each candidate with MultiPV, push top-N to Ollama queue
        List<CandidateMove> enriched = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            CandidateMove c = sorted.get(i);
            MultiPVResult mpv = stockfishService.evaluateWithMultiPV(c.move().fenBefore(), 18, 3);
            String bestMove = mpv.bestMoveUci() != null ? mpv.bestMoveUci() : c.bestMoveUci();
            CandidateMove enrichedCandidate = new CandidateMove(
                    c.move(), c.materialSwing(), c.phase(), c.isTimePressure(),
                    c.severity(), bestMove, c.evalBefore(), c.evalAfter(), mpv.pvLines());
            enriched.add(enrichedCandidate);
            if (i < MAX_OLLAMA_CALLS_PER_GAME) {
                ollamaQueue.offer(Optional.of(enrichedCandidate));
            }
        }
        ollamaQueue.offer(Optional.empty()); // signal consumer to stop

        List<OllamaResult> ollamaResults;
        try {
            ollamaResults = ollamaFuture.join();
        } catch (Exception e) {
            log.error("Ollama consumer failed for game {}: {}", game.getChessComId(), e.getMessage());
            ollamaResults = List.of();
        }

        // Persist all results in main thread (inside the REQUIRES_NEW transaction)
        for (int i = 0; i < enriched.size(); i++) {
            CandidateMove c = enriched.get(i);
            if (i < MAX_OLLAMA_CALLS_PER_GAME && i < ollamaResults.size()) {
                persistWithResult(game, c, playerColor, ollamaResults.get(i));
            } else {
                persistSkipped(game, c, playerColor);
            }
        }

        game.setAnalysisStatus(AnalysisStatus.ANALYZED);
        game.setAnalyzedAt(OffsetDateTime.now(ZoneOffset.UTC));
        gameRepository.save(game);

        log.debug("Game {} analyzed successfully", game.getChessComId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Game game) {
        game.setAnalysisStatus(AnalysisStatus.FAILED);
        gameRepository.save(game);
    }

    // --- Private helpers ---

    private void persistWithResult(Game game, CandidateMove c, String playerColor, OllamaResult result) {
        ParsedMove move = c.move();
        moveErrorRepository.save(MoveError.builder()
                .game(game)
                .moveNumber(move.moveNumber())
                .playerColor(playerColor)
                .movePlayed(move.san())
                .betterMove(c.bestMoveUci())
                .fenPosition(move.fenBefore())
                .severity(c.severity())
                .gamePhase(c.phase())
                .clockRemaining(move.clockRemainingSeconds())
                .evalBefore(c.evalBefore())
                .evalAfter(c.evalAfter())
                .winPctDrop(computeWinPctDrop(c, playerColor))
                .explanation(result.explanation())
                .tacticalMotif(result.motif())
                .analysisState(result.state())
                .build());
    }

    private void persistSkipped(Game game, CandidateMove c, String playerColor) {
        ParsedMove move = c.move();
        moveErrorRepository.save(MoveError.builder()
                .game(game)
                .moveNumber(move.moveNumber())
                .playerColor(playerColor)
                .movePlayed(move.san())
                .betterMove(c.bestMoveUci())
                .fenPosition(move.fenBefore())
                .severity(c.severity())
                .gamePhase(c.phase())
                .clockRemaining(move.clockRemainingSeconds())
                .evalBefore(c.evalBefore())
                .evalAfter(c.evalAfter())
                .winPctDrop(computeWinPctDrop(c, playerColor))
                .analysisState(AnalysisState.SKIPPED)
                .build());
    }

    private double computeWinPctDrop(CandidateMove c, String playerColor) {
        boolean isWhite = "white".equals(playerColor);
        double wBefore = MistakeCandidateFilter.winPct(c.evalBefore());
        double wAfter  = MistakeCandidateFilter.winPct(c.evalAfter());
        return isWhite ? (wBefore - wAfter) : ((100 - wBefore) - (100 - wAfter));
    }

    // Calls Ollama with up to OLLAMA_MAX_RETRIES attempts and exponential backoff.
    // Runs on the consumer thread (no Spring TX context — HTTP only, no DB).
    private OllamaResult callOllamaWithRetry(CandidateMove c, String playerColor) {
        String prompt = PromptTemplates.moveAnalysis(
                c.move().fenBefore(), c.move().san(), c.bestMoveUci(),
                c.evalBefore(), c.evalAfter(),
                playerColor, c.phase().name(), c.move().moveNumber(),
                c.engineLines());

        for (int attempt = 1; attempt <= OLLAMA_MAX_RETRIES; attempt++) {
            try {
                MoveAnalysisResult result = ollamaClient.analyzeMove(prompt, MoveAnalysisResult.class);
                return new OllamaResult(result.explanation(), parseMotif(result.tacticalMotif()), AnalysisState.EXPLAINED);
            } catch (Exception e) {
                if (attempt == OLLAMA_MAX_RETRIES) {
                    log.warn("Ollama failed after {} attempts for move {}: {}",
                            OLLAMA_MAX_RETRIES, c.move().moveNumber(), e.getMessage());
                    return new OllamaResult(null, null, AnalysisState.LLM_FAILED);
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new OllamaResult(null, null, AnalysisState.LLM_FAILED);
                }
            }
        }
        return new OllamaResult(null, null, AnalysisState.LLM_FAILED);
    }

    /**
     * Win-percentage-based game accuracy (Chess.com formula).
     *
     * Per move: winPctDrop = winPct(evalBefore) − winPct(evalAfter) from player's POV.
     * moveAccuracy = clamp(103.1668 × exp(−0.04354 × drop) − 3.1669, 0, 100).
     * Game accuracy = mean of per-move accuracies.
     *
     * This matches the chess.com display and eliminates the mate-sentinel distortion
     * that plagued the old ACPL formula (mate ±100 pawns → ACPL ≈ 400 → accuracy ≈ 0).
     */
    private double computeAccuracy(ParsedGame game, List<Double> scores) {
        boolean playerIsWhite = "white".equals(game.playerColor());
        double totalAccuracy = 0.0;
        int moveCount = 0;

        for (int i = 0; i < game.moves().size(); i++) {
            ParsedMove move = game.moves().get(i);
            boolean isWhiteMove = (move.moveNumber() % 2 == 1);
            if (playerIsWhite != isWhiteMove) continue;

            int afterIdx  = Math.min(i, scores.size() - 1);
            int beforeIdx = Math.max(0, i - 1);
            double evalAfter  = scores.get(afterIdx);
            double evalBefore = scores.get(beforeIdx);

            double wBefore = MistakeCandidateFilter.winPct(evalBefore);
            double wAfter  = MistakeCandidateFilter.winPct(evalAfter);
            double drop = playerIsWhite ? (wBefore - wAfter) : ((100 - wBefore) - (100 - wAfter));

            totalAccuracy += MistakeCandidateFilter.moveAccuracy(Math.max(0.0, drop));
            moveCount++;
        }

        if (moveCount == 0) return 0.0;
        return Math.round((totalAccuracy / moveCount) * 10.0) / 10.0;
    }

    private TacticalMotif parseMotif(String raw) {
        if (raw == null) return TacticalMotif.OTHER;
        try {
            return TacticalMotif.valueOf(raw.toUpperCase().replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            return TacticalMotif.OTHER;
        }
    }

    private record OllamaResult(String explanation, TacticalMotif motif, AnalysisState state) {}
}
