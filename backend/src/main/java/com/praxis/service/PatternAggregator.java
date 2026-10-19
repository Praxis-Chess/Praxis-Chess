package com.praxis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import com.praxis.domain.PlayerPattern;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.domain.enums.GamePhase;
import com.praxis.config.ReportProperties;
import com.praxis.service.analysis.MistakeCandidateFilter;
import com.praxis.service.analysis.ParsedGame;
import com.praxis.service.analysis.ParsedMove;
import com.praxis.service.analysis.PgnParserService;
import com.praxis.report.ReportFacts;
import com.praxis.report.ReportFactsBuilder;
import com.praxis.report.ReportWriter;
import com.praxis.repository.GameRepository;
import com.praxis.repository.PlayerPatternRepository;
import com.praxis.service.diagnosis.VerifiedMistakes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The Pattern Report (Phase 12).
 *
 * <p>Code decides the content: every figure, the ranked weaknesses and their
 * example positions come from {@link ReportFactsBuilder}, over every current
 * mistake with its verified diagnosis. A model writes short paragraphs around
 * them, held to the facts by the number checker ({@link ReportWriter}); when it
 * fails, the report is stored without prose and the page shows the facts alone.
 *
 * <p>Every recompute adds a row; earlier reports stay as history.
 */
@Service
public class PatternAggregator {

    private static final Logger log = LoggerFactory.getLogger(PatternAggregator.class);

    private final GameRepository gameRepository;
    private final PlayerPatternRepository playerPatternRepository;
    private final VerifiedMistakes verified;
    private final ReportWriter writer;
    private final EcoTable ecoTable;
    private final ObjectMapper objectMapper;
    private final PgnParserService pgn;
    private final ReportProperties reports;

    public PatternAggregator(GameRepository gameRepository,
                             PlayerPatternRepository playerPatternRepository,
                             VerifiedMistakes verified,
                             ReportWriter writer,
                             EcoTable ecoTable,
                             ObjectMapper objectMapper,
                             PgnParserService pgn,
                             ReportProperties reports) {
        this.gameRepository = gameRepository;
        this.playerPatternRepository = playerPatternRepository;
        this.verified = verified;
        this.writer = writer;
        this.ecoTable = ecoTable;
        this.objectMapper = objectMapper;
        this.pgn = pgn;
        this.reports = reports;
    }

    /** The facts for this player now: shared by the report and the training plan. */
    public ReportFacts facts(String username) {
        return facts(username, verified.forUser(username));
    }

    private ReportFacts facts(String username, List<VerifiedMistakes.Mistake> all) {
        List<Game> analysed = gameRepository.findByUsernameAndAnalysisStatus(username, AnalysisStatus.ANALYZED);
        List<ReportFactsBuilder.GameRow> games = analysed.stream()
                .map(g -> gameRow(g, username))
                .toList();
        List<ReportFactsBuilder.Mistake> mistakes = all.stream()
                .map(this::toInput)
                .toList();
        return ReportFactsBuilder.build(mistakes, games);
    }

    /**
     * F10: each plan focus in games played since {@code since}, against games
     * before it, per 100 of the player's moves. Read-only, computed on request.
     */
    public com.praxis.report.SincePlan.Result sincePlan(String username, java.time.LocalDate since,
                                                        List<com.praxis.report.PlanFacts.Priority> foci) {
        List<Game> analysed = gameRepository.findByUsernameAndAnalysisStatus(username, AnalysisStatus.ANALYZED);
        Map<java.util.UUID, java.time.LocalDate> played = new java.util.HashMap<>();
        for (Game g : analysed) if (g.getPlayedAt() != null) played.put(g.getId(), g.getPlayedAt().toLocalDate());
        List<ReportFactsBuilder.GameRow> games = analysed.stream().map(g -> gameRow(g, username)).toList();
        List<ReportFactsBuilder.Mistake> mistakes = verified.forUser(username).stream().map(this::toInput).toList();
        return com.praxis.report.SincePlan.compute(mistakes, games, played, since, foci);
    }

    /** Not transactional: the model call can take a minute and must not hold a connection. */
    public void recompute(String username) {
        log.info("Recomputing patterns for {}", username);
        List<VerifiedMistakes.Mistake> all = verified.forUser(username);
        if (all.isEmpty()) {
            log.info("No move errors found for {}, skipping pattern aggregation", username);
            return;
        }

        ReportFacts facts = facts(username, all);
        ReportWriter.Prose prose = reports.proseOn() ? writer.writeReport(facts) : ReportWriter.Prose.off();

        // The legacy columns, now filled by code, keep older readers (the
        // Dashboard) working. Move ranges are FULL moves: they used to be plies.
        List<MoveError> errors = all.stream().map(VerifiedMistakes.Mistake::error).toList();
        Map<String, Long> byRange = errors.stream()
                .collect(Collectors.groupingBy(e -> rangeOf(e.getMoveNumber()), Collectors.counting()));
        List<ReportFacts.Weakness> ws = facts.topCauses();

        PlayerPattern pattern = PlayerPattern.builder()
                .username(username)
                .gamesAnalyzed(facts.gamesAnalysed())
                .computedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .mistakesMoves1to10(byRange.getOrDefault("1-10", 0L).intValue())
                .mistakesMoves11to20(byRange.getOrDefault("11-20", 0L).intValue())
                .mistakesMoves21to30(byRange.getOrDefault("21-30", 0L).intValue())
                .mistakesMoves31Plus(byRange.getOrDefault("31+", 0L).intValue())
                .mistakesOpening(countPhase(errors, GamePhase.OPENING))
                .mistakesMiddlegame(countPhase(errors, GamePhase.MIDDLEGAME))
                .mistakesEndgame(countPhase(errors, GamePhase.ENDGAME))
                .motifFrequency(toJson(facts.tactics().stream()
                        .collect(Collectors.toMap(ReportFacts.Count::key, ReportFacts.Count::count,
                                (a, b) -> a, LinkedHashMap::new))))
                .openingAccuracy(toJson(facts.openings().stream()
                        .collect(Collectors.toMap(ReportFacts.OpeningRow::name, ReportFacts.OpeningRow::serious,
                                (a, b) -> a, LinkedHashMap::new))))
                .primaryWeakness(ws.size() > 0 ? ws.get(0).title() : null)
                .secondaryWeakness(ws.size() > 1 ? ws.get(1).title() : null)
                .tertiaryWeakness(ws.size() > 2 ? ws.get(2).title() : null)
                .criticalMoveRange(facts.worstRange() == null ? null : "Moves " + facts.worstRange())
                .dominantMotif(facts.topTactics().isEmpty() ? null : facts.topTactics().get(0).key())
                .openingAssessment(prose.text() == null ? null : (String) prose.text().get("openings"))
                .factsJson(toJson(facts))
                .proseJson(prose.text() == null ? null : toJson(prose.text()))
                .proseStatus(prose.status())
                .proseModel(prose.model())
                .proseViolations(prose.violations().isEmpty() ? null : toJson(prose.violations()))
                .proseAttempts(prose.attempts())
                .proseMillis(prose.millis())
                .build();

        playerPatternRepository.save(pattern);
        log.info("Pattern report for {}: {} mistakes, {} diagnosed, {} weaknesses; prose {} by {} ({} attempt(s), {} ms)",
                username, facts.mistakes(), facts.diagnosed(), ws.size(), prose.status(), prose.model(),
                prose.attempts(), prose.millis());
    }

    /**
     * The player's own moves per phase and per move range in one game, by the
     * same phase rule as a flagged move: the denominators of every rate.
     */
    private ReportFactsBuilder.GameRow gameRow(Game g, String username) {
        int[] byPhase = new int[3];
        int[] byRange = new int[4];
        try {
            ParsedGame parsed = pgn.parse(g.getId().toString(), g.getRawPgn(), username);
            boolean white = "white".equals(parsed.playerColor());
            for (ParsedMove m : parsed.moves()) {
                if ((m.moveNumber() % 2 == 1) != white) continue;
                byPhase[MistakeCandidateFilter.phaseOf(m.moveNumber(), m.fenBefore()).ordinal()]++;
                byRange[ReportFactsBuilder.rangeIndex(m.moveNumber())]++;
            }
        } catch (Exception e) {
            log.debug("[report] could not count moves of game {}: {}", g.getId(), e.getMessage());
        }
        return new ReportFactsBuilder.GameRow(g.getId(), g.getOpeningEco(), openingName(g), byPhase, byRange);
    }

    private ReportFactsBuilder.Mistake toInput(VerifiedMistakes.Mistake m) {
        MoveError e = m.error();
        Game g = e.getGame();
        return new ReportFactsBuilder.Mistake(g.getId(), e.getMoveNumber(), e.getSeverity(), e.getGamePhase(),
                e.getClockRemaining(), e.getWinPctDrop(), e.getFenPosition(), e.getMovePlayed(), e.getBetterMove(),
                g.getPlayedAt() == null ? null : g.getPlayedAt().toLocalDate(), openingName(g),
                m.diagnosed(), m.motif(), m.mechanism());
    }

    /** The opening's name, from the ECO table when the game has none (most Chess.com games). */
    private String openingName(Game g) {
        if (g.getOpeningName() != null && !g.getOpeningName().isBlank()) return g.getOpeningName();
        if (g.getOpeningEco() == null || g.getOpeningEco().isBlank()) return null;
        String looked = ecoTable.lookup(g.getOpeningEco());
        return looked != null && !looked.isBlank() ? looked : g.getOpeningEco();
    }

    private static int countPhase(List<MoveError> errors, GamePhase phase) {
        return (int) errors.stream().filter(e -> e.getGamePhase() == phase).count();
    }

    private static String rangeOf(int ply) {
        int move = (ply + 1) / 2;
        return move <= 10 ? "1-10" : move <= 20 ? "11-20" : move <= 30 ? "21-30" : "31+";
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
