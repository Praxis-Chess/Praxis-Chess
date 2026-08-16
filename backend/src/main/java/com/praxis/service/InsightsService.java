package com.praxis.service;

import com.praxis.config.AppProperties;
import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.dto.InsightsDto;
import com.praxis.dto.InsightsDto.*;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes practical improvement analytics from persisted games + move errors.
 * Single-user scale (hundreds of games) — everything is loaded and aggregated in memory.
 */
@Service
public class InsightsService {

    private static final int    OPP_BUCKET_THRESHOLD = 50;   // rating diff for stronger/weaker
    private static final int    TIME_PRESSURE_CUTOFF = 30;   // seconds
    private static final double WINNING_THRESHOLD    = 2.0;  // pawns to count as a winning position
    private static final int    MOVING_AVG_WINDOW    = 10;

    private final GameRepository gameRepository;
    private final MoveErrorRepository moveErrorRepository;
    private final AppProperties appProperties;

    public InsightsService(GameRepository gameRepository,
                           MoveErrorRepository moveErrorRepository,
                           AppProperties appProperties) {
        this.gameRepository = gameRepository;
        this.moveErrorRepository = moveErrorRepository;
        this.appProperties = appProperties;
    }

    public InsightsDto compute() {
        String username = appProperties.chessCom().username();
        List<Game> gamesDesc = gameRepository.findByUsernameOrderByPlayedAtDesc(username);
        List<MoveError> errors = moveErrorRepository.findAllByUsername(username);

        return new InsightsDto(
                opponentStrength(gamesDesc),
                accuracyTrend(gamesDesc),
                timeOfDay(gamesDesc),
                dayOfWeek(gamesDesc),
                phaseAccuracy(errors),
                timeManagement(gamesDesc, errors),
                conversion(gamesDesc),
                missedTactics(errors),
                tilt(gamesDesc),
                openings(gamesDesc));
    }

    // --- Opponent strength ---

    private List<OpponentBucket> opponentStrength(List<Game> games) {
        Map<String, List<Game>> byBucket = new LinkedHashMap<>();
        byBucket.put("Stronger", new ArrayList<>());
        byBucket.put("Even", new ArrayList<>());
        byBucket.put("Weaker", new ArrayList<>());

        for (Game g : games) {
            Integer player = playerRating(g);
            Integer opp = opponentRating(g);
            if (player == null || opp == null || player == 0 || opp == 0) continue;
            int diff = opp - player;
            String bucket = diff >= OPP_BUCKET_THRESHOLD ? "Stronger"
                    : diff <= -OPP_BUCKET_THRESHOLD ? "Weaker" : "Even";
            byBucket.get(bucket).add(g);
        }

        return byBucket.entrySet().stream()
                .map(e -> {
                    List<Game> gs = e.getValue();
                    int wins = (int) gs.stream().filter(g -> "win".equals(g.getResult())).count();
                    return new OpponentBucket(e.getKey(), gs.size(), wins,
                            pct(wins, gs.size()), avgAccuracy(gs));
                })
                .toList();
    }

    // --- Accuracy trend ---

    private List<AccuracyTrendPoint> accuracyTrend(List<Game> gamesDesc) {
        List<Game> withAcc = gamesDesc.stream()
                .filter(g -> g.hasAccuracy() && g.getPlayedAt() != null)
                .sorted(Comparator.comparing(Game::getPlayedAt))
                .toList();

        List<AccuracyTrendPoint> out = new ArrayList<>(withAcc.size());
        for (int i = 0; i < withAcc.size(); i++) {
            double acc = withAcc.get(i).getAccuracy();
            int start = Math.max(0, i - MOVING_AVG_WINDOW + 1);
            double sum = 0;
            for (int j = start; j <= i; j++) sum += withAcc.get(j).getAccuracy();
            double avg = sum / (i - start + 1);
            out.add(new AccuracyTrendPoint(
                    withAcc.get(i).getPlayedAt().toLocalDate().toString(),
                    round1(acc), round1(avg)));
        }
        return out;
    }

    // --- Time of day / day of week ---

    private List<TimeBucket> timeOfDay(List<Game> games) {
        String[] labels = {"Night", "Morning", "Afternoon", "Evening"};
        Map<String, List<Game>> map = new LinkedHashMap<>();
        for (String l : labels) map.put(l, new ArrayList<>());
        for (Game g : games) {
            if (g.getPlayedAt() == null) continue;
            int h = g.getPlayedAt().getHour();
            String label = h < 6 ? "Night" : h < 12 ? "Morning" : h < 18 ? "Afternoon" : "Evening";
            map.get(label).add(g);
        }
        return toTimeBuckets(map);
    }

    private List<TimeBucket> dayOfWeek(List<Game> games) {
        Map<String, List<Game>> map = new LinkedHashMap<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            map.put(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), new ArrayList<>());
        }
        for (Game g : games) {
            if (g.getPlayedAt() == null) continue;
            String label = g.getPlayedAt().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            map.get(label).add(g);
        }
        return toTimeBuckets(map);
    }

    private List<TimeBucket> toTimeBuckets(Map<String, List<Game>> map) {
        return map.entrySet().stream()
                .map(e -> {
                    List<Game> gs = e.getValue();
                    int wins = (int) gs.stream().filter(g -> "win".equals(g.getResult())).count();
                    return new TimeBucket(e.getKey(), gs.size(), wins, pct(wins, gs.size()));
                })
                .toList();
    }

    // --- Phase accuracy (mistake counts) ---

    private PhaseAccuracy phaseAccuracy(List<MoveError> errors) {
        int opening = 0, middlegame = 0, endgame = 0;
        for (MoveError e : errors) {
            if (e.getGamePhase() == GamePhase.OPENING) opening++;
            else if (e.getGamePhase() == GamePhase.MIDDLEGAME) middlegame++;
            else if (e.getGamePhase() == GamePhase.ENDGAME) endgame++;
        }
        return new PhaseAccuracy(opening, middlegame, endgame);
    }

    // --- Time management ---

    private TimeManagement timeManagement(List<Game> games, List<MoveError> errors) {
        Double avgMove = games.stream()
                .map(Game::getAvgMoveSeconds)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average().stream().boxed().findFirst().orElse(null);

        List<MoveError> blunders = errors.stream()
                .filter(e -> e.getSeverity() == Severity.BLUNDER)
                .toList();
        List<MoveError> withClock = blunders.stream()
                .filter(e -> e.getClockRemaining() != null)
                .toList();
        int inPressure = (int) withClock.stream()
                .filter(e -> e.getClockRemaining() <= TIME_PRESSURE_CUTOFF)
                .count();
        double rate = pct(inPressure, withClock.size());

        return new TimeManagement(
                avgMove == null ? null : round1(avgMove),
                blunders.size(), inPressure, rate);
    }

    // --- Winning-position conversion ---

    private Conversion conversion(List<Game> games) {
        List<Game> winning = games.stream()
                .filter(g -> g.getMaxAdvantage() != null && g.getMaxAdvantage() >= WINNING_THRESHOLD)
                .toList();
        int converted = (int) winning.stream().filter(g -> "win".equals(g.getResult())).count();

        List<BlownGame> blown = winning.stream()
                .filter(g -> !"win".equals(g.getResult()))
                .sorted(Comparator.comparingDouble(Game::getMaxAdvantage).reversed())
                .limit(8)
                .map(g -> new BlownGame(
                        g.getId().toString(),
                        g.getOpeningName() != null ? g.getOpeningName() : g.getOpeningEco(),
                        round1(g.getMaxAdvantage()),
                        g.getResult(),
                        g.getPlayedAt() != null ? g.getPlayedAt().toLocalDate().toString() : null))
                .toList();

        return new Conversion(winning.size(), converted, pct(converted, winning.size()), blown);
    }

    // --- Missed tactics ---

    private List<MotifCount> missedTactics(List<MoveError> errors) {
        Map<String, Long> counts = errors.stream()
                .filter(e -> e.getTacticalMotif() != null)
                .collect(Collectors.groupingBy(e -> e.getTacticalMotif().name(), Collectors.counting()));
        return counts.entrySet().stream()
                .map(e -> new MotifCount(e.getKey(), e.getValue().intValue()))
                .sorted(Comparator.comparingInt(MotifCount::count).reversed())
                .toList();
    }

    // --- Tilt / resilience ---

    private Tilt tilt(List<Game> gamesDesc) {
        List<Game> asc = new ArrayList<>(gamesDesc);
        Collections.reverse(asc);
        int afterWinGames = 0, afterWinWins = 0, afterLossGames = 0, afterLossWins = 0;
        for (int i = 1; i < asc.size(); i++) {
            String prev = asc.get(i - 1).getResult();
            String curr = asc.get(i).getResult();
            if ("win".equals(prev)) {
                afterWinGames++;
                if ("win".equals(curr)) afterWinWins++;
            } else if ("loss".equals(prev)) {
                afterLossGames++;
                if ("win".equals(curr)) afterLossWins++;
            }
        }
        return new Tilt(afterWinGames, pct(afterWinWins, afterWinGames),
                afterLossGames, pct(afterLossWins, afterLossGames));
    }

    // --- Openings ---

    private List<OpeningInsight> openings(List<Game> games) {
        return games.stream()
                .filter(g -> g.getOpeningEco() != null)
                .collect(Collectors.groupingBy(Game::getOpeningEco))
                .entrySet().stream()
                .map(e -> {
                    List<Game> gs = e.getValue();
                    int wins = (int) gs.stream().filter(g -> "win".equals(g.getResult())).count();
                    String name = gs.stream().map(Game::getOpeningName)
                            .filter(Objects::nonNull).findFirst().orElse(e.getKey());
                    return new OpeningInsight(e.getKey(), name, gs.size(), pct(wins, gs.size()), avgAccuracy(gs));
                })
                .sorted(Comparator.comparingInt(OpeningInsight::games).reversed())
                .limit(12)
                .toList();
    }

    // --- Helpers ---

    private Integer playerRating(Game g) {
        return "white".equals(g.getPlayerColor()) ? g.getWhiteRating() : g.getBlackRating();
    }

    private Integer opponentRating(Game g) {
        return "white".equals(g.getPlayerColor()) ? g.getBlackRating() : g.getWhiteRating();
    }

    private Double avgAccuracy(List<Game> games) {
        double[] accs = games.stream()
                .filter(Game::hasAccuracy)
                .mapToDouble(Game::getAccuracy)
                .toArray();
        if (accs.length == 0) return null;
        return round1(Arrays.stream(accs).average().orElse(0));
    }

    private double pct(int part, int total) {
        return total == 0 ? 0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
