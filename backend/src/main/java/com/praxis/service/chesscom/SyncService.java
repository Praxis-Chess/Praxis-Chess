package com.praxis.service.chesscom;

import com.praxis.config.AppProperties;
import com.praxis.domain.Game;
import com.praxis.domain.SyncHistory;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.pipeline.AnalysisPipelineOrchestrator;
import com.praxis.pipeline.AnalysisProgressTracker;
import com.praxis.repository.GameRepository;
import com.praxis.repository.SyncHistoryRepository;
import com.praxis.service.chesscom.dto.ChessComGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final ChessComApiClient apiClient;
    private final GameRepository gameRepository;
    private final SyncHistoryRepository syncHistoryRepository;
    private final AnalysisPipelineOrchestrator pipelineOrchestrator;
    private final AnalysisProgressTracker progressTracker;
    private final AppProperties appProperties;

    // In-memory sync state
    private volatile boolean syncing = false;
    private volatile boolean syncQueued = false;

    // Cached "new games available on Chess.com" check — avoids hammering the API
    private volatile int cachedNewGamesCount = 0;
    private volatile long newGamesCacheTimestamp = 0L;
    private static final long NEW_GAMES_CACHE_TTL_MS = 10 * 60 * 60 * 1000L; // 10 hours
    private final AtomicInteger gamesFetched  = new AtomicInteger(0);
    private final AtomicInteger gamesQueued   = new AtomicInteger(0);
    private final AtomicReference<String> lastSyncedAt = new AtomicReference<>("Never");

    public SyncService(ChessComApiClient apiClient,
                       GameRepository gameRepository,
                       SyncHistoryRepository syncHistoryRepository,
                       AnalysisPipelineOrchestrator pipelineOrchestrator,
                       AnalysisProgressTracker progressTracker,
                       AppProperties appProperties) {
        this.apiClient = apiClient;
        this.gameRepository = gameRepository;
        this.syncHistoryRepository = syncHistoryRepository;
        this.pipelineOrchestrator = pipelineOrchestrator;
        this.progressTracker = progressTracker;
        this.appProperties = appProperties;
    }

    @Transactional
    public int sync(String username, int months) {
        if (syncing) {
            log.info("Sync already in progress, skipping");
            return 0;
        }

        String effectiveUsername = username != null ? username : appProperties.chessCom().username();
        syncQueued = false;
        syncing = true;
        gamesFetched.set(0);
        gamesQueued.set(0);

        try {
            List<Game> newGames = new ArrayList<>();
            YearMonth current = YearMonth.now();

            for (int i = 0; i < months; i++) {
                YearMonth ym = current.minusMonths(i);

                // A completed month is immutable — once synced it can never gain
                // games, so the history record is a valid cache. The CURRENT month
                // is still accumulating, so it must be re-fetched every time.
                // Skipping it made "Sync Now" a no-op for exactly the games the
                // user just played, which is the only reason to press it.
                boolean isCurrentMonth = ym.equals(current);
                if (!isCurrentMonth
                        && syncHistoryRepository.existsByUsernameAndYearAndMonth(
                                effectiveUsername, ym.getYear(), ym.getMonthValue())) {
                    log.debug("Month {}/{} already synced, skipping", ym.getYear(), ym.getMonthValue());
                    continue;
                }

                List<ChessComGame> fetched = apiClient.fetchGames(effectiveUsername, ym.getYear(), ym.getMonthValue());
                int persisted = 0;

                for (ChessComGame cg : fetched) {
                    if (cg.uuid() == null) continue;
                    if (gameRepository.existsByChessComId(cg.uuid())) {
                        // Backfill accuracy for games synced before the accuracy field was added
                        gameRepository.findByChessComId(cg.uuid()).ifPresent(existing -> {
                            if (existing.getAccuracy() == null) {
                                boolean piw = effectiveUsername.equalsIgnoreCase(cg.white().username());
                                Double acc = piw ? cg.white().accuracy() : cg.black().accuracy();
                                if (acc != null) {
                                    existing.setAccuracy(acc);
                                    gameRepository.save(existing);
                                }
                            }
                        });
                        continue;
                    }
                    Game game = toEntity(cg, effectiveUsername);
                    gameRepository.save(game);
                    newGames.add(game);
                    persisted++;
                }

                // Upsert, not insert. The current month is now re-synced on every
                // press, so a blind insert would add a duplicate row each time.
                final int persistedCount = persisted;
                syncHistoryRepository
                        .findByUsernameAndYearAndMonth(effectiveUsername, ym.getYear(), ym.getMonthValue())
                        .ifPresentOrElse(
                                existing -> {
                                    existing.setGamesFetched(existing.getGamesFetched() + persistedCount);
                                    syncHistoryRepository.save(existing);
                                },
                                () -> syncHistoryRepository.save(SyncHistory.builder()
                                        .username(effectiveUsername)
                                        .year(ym.getYear())
                                        .month(ym.getMonthValue())
                                        .gamesFetched(persistedCount)
                                        .build()));

                gamesFetched.addAndGet(persisted);
                log.info("Synced {}/{}: {} new games", ym.getYear(), ym.getMonthValue(), persisted);
            }

            lastSyncedAt.set(OffsetDateTime.now(ZoneOffset.UTC).toString());
            gamesQueued.set(newGames.size());

            if (!newGames.isEmpty()) {
                progressTracker.setQueued(true);
                pipelineOrchestrator.analyzeGames(newGames, effectiveUsername);
            }

            return newGames.size();
        } finally {
            syncing = false;
        }
    }

    public SyncStatus getStatus() {
        String username = appProperties.chessCom().username();
        long pending  = gameRepository.countByUsernameAndAnalysisStatus(username, AnalysisStatus.PENDING);
        long analyzed = gameRepository.countByUsernameAndAnalysisStatus(username, AnalysisStatus.ANALYZED);
        // Always read from DB so it survives server restarts
        String lastSync = syncHistoryRepository
                .findTopByUsernameOrderBySyncedAtDesc(username)
                .map(sh -> sh.getSyncedAt().toString())
                .orElse("Never");
        return new SyncStatus((syncing || syncQueued) ? "SYNCING" : (pending > 0 ? "ANALYZING" : "IDLE"),
                gamesFetched.get(), (int) analyzed, (int) pending, lastSync);
    }

    public void enqueueSyncFlag() {
        this.syncQueued = true;
        // Invalidate the cache so next check sees fresh data after a sync
        this.newGamesCacheTimestamp = 0L;
    }

    /**
     * Checks Chess.com for games in the current month that aren't in our DB yet.
     * Result is cached for {@link #NEW_GAMES_CACHE_TTL_MS} to stay well within rate limits.
     */
    public int countNewGamesAvailable() {
        long now = System.currentTimeMillis();
        if (now - newGamesCacheTimestamp < NEW_GAMES_CACHE_TTL_MS) return cachedNewGamesCount;

        String username = appProperties.chessCom().username();
        LocalDate today = LocalDate.now();
        try {
            List<ChessComGame> chessComGames = apiClient.fetchGames(username, today.getYear(), today.getMonthValue());
            Set<String> knownIds = new HashSet<>(gameRepository.findAllChessComIdsByUsername(username));
            int newCount = (int) chessComGames.stream()
                    .filter(g -> g.uuid() != null && !knownIds.contains(g.uuid()))
                    .count();
            cachedNewGamesCount = newCount;
            newGamesCacheTimestamp = now;
            return newCount;
        } catch (Exception e) {
            log.warn("Failed to check for new Chess.com games: {}", e.getMessage());
            return 0;
        }
    }

    public void clearSyncHistory(String username, int months) {
        YearMonth current = YearMonth.now();
        for (int i = 0; i < months; i++) {
            YearMonth ym = current.minusMonths(i);
            syncHistoryRepository.deleteByUsernameAndYearAndMonth(username, ym.getYear(), ym.getMonthValue());
        }
    }

    public int forceResync(String username, int months) {
        String effectiveUsername = username != null ? username : appProperties.chessCom().username();
        clearSyncHistory(effectiveUsername, months);
        return sync(username, months);
    }

    private Game toEntity(ChessComGame cg, String username) {
        boolean playerIsWhite = username.equalsIgnoreCase(cg.white().username());
        String playerColor = playerIsWhite ? "white" : "black";
        String rawResult = playerIsWhite ? cg.white().result() : cg.black().result();
        String result = normalizeResult(rawResult);
        OffsetDateTime playedAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(cg.endTime()), ZoneOffset.UTC);

        Double accuracy = playerIsWhite ? cg.white().accuracy() : cg.black().accuracy();

        return Game.builder()
                .chessComId(cg.uuid())
                .username(username)
                .playedAt(playedAt)
                .timeControl(cg.timeControl())
                .timeClass(cg.timeClass())
                .playerColor(playerColor)
                .result(result)
                .rawPgn(cg.pgn())
                .whiteRating(cg.white().rating())
                .blackRating(cg.black().rating())
                .accuracy(accuracy)
                .analysisStatus(AnalysisStatus.PENDING)
                .build();
    }

    private String normalizeResult(String chessComResult) {
        if (chessComResult == null) return "draw";
        return switch (chessComResult) {
            case "win" -> "win";
            case "resigned", "checkmated", "timeout", "abandoned", "lose" -> "loss";
            default -> "draw";
        };
    }

    public record SyncStatus(String state, int gamesFetched, int gamesAnalyzed, int gamesPending, String lastSyncedAt) {}
}
