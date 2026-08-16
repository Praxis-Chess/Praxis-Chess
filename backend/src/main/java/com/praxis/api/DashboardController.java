package com.praxis.api;

import com.praxis.config.AppProperties;
import com.praxis.domain.Game;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.dto.*;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final GameRepository gameRepository;
    private final MoveErrorRepository moveErrorRepository;
    private final AppProperties appProperties;

    public DashboardController(GameRepository gameRepository,
                               MoveErrorRepository moveErrorRepository,
                               AppProperties appProperties) {
        this.gameRepository = gameRepository;
        this.moveErrorRepository = moveErrorRepository;
        this.appProperties = appProperties;
    }

    @GetMapping("/stats")
    public ResponseEntity<DashboardStatsDto> getStats() {
        String username = appProperties.chessCom().username();
        List<Game> games = gameRepository.findByUsernameOrderByPlayedAtDesc(username);

        int wins     = (int) games.stream().filter(g -> "win".equals(g.getResult())).count();
        int losses   = (int) games.stream().filter(g -> "loss".equals(g.getResult())).count();
        int draws    = (int) games.stream().filter(g -> "draw".equals(g.getResult())).count();
        int analyzed = (int) games.stream().filter(g -> g.getAnalysisStatus() == AnalysisStatus.ANALYZED).count();

        // White vs Black stats
        List<Game> whiteGames = games.stream().filter(g -> "white".equals(g.getPlayerColor())).toList();
        List<Game> blackGames = games.stream().filter(g -> "black".equals(g.getPlayerColor())).toList();
        int whiteWins = (int) whiteGames.stream().filter(g -> "win".equals(g.getResult())).count();
        int blackWins = (int) blackGames.stream().filter(g -> "win".equals(g.getResult())).count();
        double whiteWinPct = whiteGames.isEmpty() ? 0 : Math.round(whiteWins * 1000.0 / whiteGames.size()) / 10.0;
        double blackWinPct = blackGames.isEmpty() ? 0 : Math.round(blackWins * 1000.0 / blackGames.size()) / 10.0;

        // Form streak (games are DESC by playedAt)
        int formStreak = computeFormStreak(games);

        // Time control breakdown
        List<TimeControlStatDto> timeControlStats = games.stream()
                .filter(g -> g.getTimeClass() != null)
                .collect(Collectors.groupingBy(Game::getTimeClass))
                .entrySet().stream()
                .map(e -> {
                    List<Game> gs = e.getValue();
                    int w = (int) gs.stream().filter(g -> "win".equals(g.getResult())).count();
                    double pct = Math.round(w * 1000.0 / gs.size()) / 10.0;
                    return new TimeControlStatDto(e.getKey(), gs.size(), w, pct);
                })
                .sorted(Comparator.comparingInt(TimeControlStatDto::games).reversed())
                .toList();

        // Dominant time class (for rating history)
        String dominantTimeClass = timeControlStats.isEmpty() ? "rapid" : timeControlStats.get(0).timeClass();

        // Rating history — only dominant time class, chronological
        List<RatingPointDto> ratingHistory = games.stream()
                .filter(g -> dominantTimeClass.equals(g.getTimeClass()) && g.getPlayedAt() != null)
                .map(g -> new RatingPointDto(g.getPlayedAt(), playerRating(g)))
                .filter(rp -> rp.rating() > 0)
                .sorted(Comparator.comparing(RatingPointDto::date))
                .toList();

        int currentRating = ratingHistory.isEmpty() ? 0 : ratingHistory.get(ratingHistory.size() - 1).rating();
        int ratingDelta   = ratingHistory.size() < 2 ? 0 : currentRating - ratingHistory.get(0).rating();

        // Opening stats
        List<OpeningStatDto> openingStats = games.stream()
                .filter(g -> g.getOpeningEco() != null)
                .collect(Collectors.groupingBy(Game::getOpeningEco))
                .entrySet().stream()
                .map(e -> {
                    List<Game> gs = e.getValue();
                    int total = gs.size();
                    int w = (int) gs.stream().filter(g -> "win".equals(g.getResult())).count();
                    double pct = total > 0 ? Math.round(w * 1000.0 / total) / 10.0 : 0;
                    String name = gs.stream()
                            .map(Game::getOpeningName)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(e.getKey());
                    return new OpeningStatDto(e.getKey(), name, total, w, pct);
                })
                .sorted(Comparator.comparingInt(OpeningStatDto::games).reversed())
                .limit(10)
                .toList();

        // Accuracy stats
        DoubleSummaryStatistics accStats = games.stream()
                .filter(Game::hasAccuracy)
                .mapToDouble(Game::getAccuracy)
                .summaryStatistics();
        Double avgAccuracy  = accStats.getCount() > 0 ? Math.round(accStats.getAverage() * 10) / 10.0 : null;
        Double bestAccuracy = accStats.getCount() > 0 ? Math.round(accStats.getMax() * 10) / 10.0 : null;

        int blunderCount = moveErrorRepository.countBlundersByUsername(username);

        // Recent games
        List<RecentGameDto> recentGames = games.stream()
                .limit(6)
                .map(g -> new RecentGameDto(
                        g.getId().toString(),
                        g.getPlayedAt() != null ? g.getPlayedAt().toString() : null,
                        g.getTimeClass(),
                        g.getPlayerColor(),
                        g.getResult(),
                        g.getOpeningEco(),
                        g.getOpeningName(),
                        g.getAccuracy()))
                .toList();

        return ResponseEntity.ok(new DashboardStatsDto(
                games.size(), wins, losses, draws, analyzed,
                currentRating, ratingDelta,
                avgAccuracy, bestAccuracy, blunderCount,
                openingStats, ratingHistory, recentGames,
                whiteGames.size(), whiteWinPct,
                blackGames.size(), blackWinPct,
                formStreak, timeControlStats));
    }

    private int computeFormStreak(List<Game> gamesDesc) {
        if (gamesDesc.isEmpty()) return 0;
        String first = gamesDesc.get(0).getResult();
        if (!"win".equals(first) && !"loss".equals(first)) return 0;
        boolean winStreak = "win".equals(first);
        int streak = 0;
        for (Game g : gamesDesc) {
            if (winStreak && "win".equals(g.getResult())) streak++;
            else if (!winStreak && "loss".equals(g.getResult())) streak++;
            else break;
        }
        return winStreak ? streak : -streak;
    }

    private int playerRating(Game g) {
        return "white".equals(g.getPlayerColor())
                ? (g.getWhiteRating() != null ? g.getWhiteRating() : 0)
                : (g.getBlackRating() != null ? g.getBlackRating() : 0);
    }
}
