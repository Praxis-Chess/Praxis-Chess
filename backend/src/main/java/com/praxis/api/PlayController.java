package com.praxis.api;

import com.praxis.config.AppProperties;
import com.praxis.domain.Game;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.play.ImprovementService;
import com.praxis.play.OpponentProfileService;
import com.praxis.play.PlaySessionService;
import com.praxis.play.PracticeGameArchiver;
import com.praxis.play.StockfishPlayService;
import com.praxis.play.domain.PracticeGame;
import com.praxis.play.domain.enums.PracticeStatus;
import com.praxis.play.patterns.PracticePatternReport;
import com.praxis.play.patterns.PracticePatternService;
import com.praxis.play.repository.PracticeGameRepository;
import com.praxis.repository.GameRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/play")
public class PlayController {

    private final PlaySessionService sessions;
    private final OpponentProfileService profiles;
    private final PracticeGameArchiver archiver;
    private final ImprovementService improvement;
    private final PracticeGameRepository practiceGames;
    private final StockfishPlayService engine;
    private final AppProperties props;
    private final GameRepository games;
    private final PracticePatternService patternService;

    public PlayController(PlaySessionService sessions, OpponentProfileService profiles,
                          PracticeGameArchiver archiver, ImprovementService improvement,
                          PracticeGameRepository practiceGames, StockfishPlayService engine,
                          AppProperties props, GameRepository games,
                          PracticePatternService patternService) {
        this.sessions = sessions;
        this.profiles = profiles;
        this.archiver = archiver;
        this.improvement = improvement;
        this.practiceGames = practiceGames;
        this.engine = engine;
        this.props = props;
        this.games = games;
        this.patternService = patternService;
    }

    public record StartRequest(String color, Integer skill) {}
    public record MoveRequest(String uci) {}

    /**
     * Records, not Maps, on purpose.
     *
     * Jackson's SNAKE_CASE naming strategy applies to bean properties only — Map
     * keys are serialised verbatim. Returning a Map here shipped `sessionId` to a
     * client reading `session_id`, so every move went to /session/undefined/move.
     * A record cannot drift from the wire contract that way.
     */
    public record SessionState(
            UUID sessionId,
            String fen,
            String sanMoves,
            String playerColor,
            Integer skillLevel,
            String targetEco,
            String targetOpening,
            PracticeStatus status,
            String result,
            String endReason,
            boolean rated
    ) {}

    /**
     * @param gameId   the archived Game — the id the review and analysis endpoints
     *                 take. Null until the game is archived.
     * @param analysed measured, not merely archived. The Game row is created at
     *                 archive time and the engine runs afterwards, so keying this
     *                 off {@code gameId != null} advertised finished analysis for
     *                 games nobody had measured. Same distinction ImprovementService
     *                 draws, for the same reason.
     */
    public record GameSummary(
            UUID id,
            UUID gameId,
            java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime finishedAt,
            PracticeStatus status,
            String result,
            Integer skillLevel,
            String targetOpening,
            boolean rated,
            boolean analysed
    ) {}

    /** Probed before showing the tab — no engine, no feature. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("available", engine.isAvailable()));
    }

    /** The opponent Praxis WOULD build, so the player can see why before committing. */
    @GetMapping("/preview")
    public ResponseEntity<OpponentProfileService.OpponentProfile> preview(
            @RequestParam(required = false) Integer skill) {
        return ResponseEntity.ok(profiles.build(props.chessCom().username(), skill));
    }

    @PostMapping("/session")
    public ResponseEntity<?> start(@RequestBody(required = false) StartRequest req) {
        if (!engine.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Chess engine is not running."));
        }
        String colour = req == null ? null : req.color();
        Integer skill = req == null ? null : req.skill();
        PracticeGame game = sessions.start(colour, skill);
        return ResponseEntity.ok(state(game));
    }

    @GetMapping("/session/{id}")
    public ResponseEntity<SessionState> get(@PathVariable UUID id) {
        return sessions.find(id).map(g -> ResponseEntity.ok(state(g)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/session/{id}/move")
    public ResponseEntity<?> move(@PathVariable UUID id, @RequestBody MoveRequest req) {
        PracticeGame game = sessions.find(id).orElse(null);
        if (game == null) return ResponseEntity.notFound().build();
        try {
            PlaySessionService.MoveResult r = sessions.move(game, req.uci());
            maybeArchive(game);
            return ResponseEntity.ok(r);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // An illegal or out-of-turn move is the client's problem, not a server fault.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/session/{id}/undo")
    public ResponseEntity<?> undo(@PathVariable UUID id) {
        return sessions.find(id)
                .map(g -> ResponseEntity.ok((Object) sessions.undo(g)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/session/{id}/resign")
    public ResponseEntity<?> resign(@PathVariable UUID id) {
        PracticeGame game = sessions.find(id).orElse(null);
        if (game == null) return ResponseEntity.notFound().build();
        PlaySessionService.MoveResult r = sessions.resign(game);
        maybeArchive(game);
        return ResponseEntity.ok(r);
    }

    @PostMapping("/session/{id}/abandon")
    public ResponseEntity<Void> abandon(@PathVariable UUID id) {
        sessions.find(id).ifPresent(sessions::abandon);
        return ResponseEntity.noContent().build();
    }

    /**
     * 202 while the engine is still analysing — the report is worth waiting for,
     * and inventing one from an unanalysed game would defeat the point.
     */
    @GetMapping("/report/{id}")
    public ResponseEntity<ImprovementService.ImprovementReport> report(@PathVariable UUID id) {
        PracticeGame game = practiceGames.findById(id).orElse(null);
        if (game == null) return ResponseEntity.notFound().build();

        ImprovementService.ImprovementReport report = improvement.report(game);
        return report.analysed()
                ? ResponseEntity.ok(report)
                : ResponseEntity.accepted().body(report);
    }

    /**
     * Recurring tendencies across recent practice games.
     *
     * Always 200, never 202: this is a synchronous read over a bounded window,
     * unlike the improvement report, which waits on the engine.
     */
    @GetMapping("/patterns")
    public ResponseEntity<PracticePatternReport> patterns() {
        return ResponseEntity.ok(patternService.report());
    }

    @GetMapping("/history")
    public ResponseEntity<List<GameSummary>> history() {
        List<PracticeGame> all = practiceGames
                .findByUsernameOrderByStartedAtDesc(props.chessCom().username());

        // Which archived games actually finished analysis. Fetched in one query
        // rather than per row — the alternative is an N+1 over the whole history.
        Set<UUID> archivedIds = all.stream()
                .map(PracticeGame::getGameId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<UUID> measured = archivedIds.isEmpty() ? Set.of()
                : StreamSupport.stream(games.findAllById(archivedIds).spliterator(), false)
                        .filter(g -> g.getAnalysisStatus() == AnalysisStatus.ANALYZED)
                        .map(Game::getId)
                        .collect(Collectors.toSet());

        return ResponseEntity.ok(all.stream().map(g -> summary(g, measured)).toList());
    }

    // --- helpers ---

    /** Archiving and analysis start the moment a game reaches a real conclusion. */
    private void maybeArchive(PracticeGame game) {
        if (game.getStatus() == PracticeStatus.FINISHED && game.getGameId() == null) {
            archiver.archiveAndAnalyse(game.getId());
        }
    }

    private SessionState state(PracticeGame g) {
        return new SessionState(
                g.getId(),
                g.getCurrentFen(),
                g.getSanMoves(),
                g.getPlayerColor(),
                g.getSkillLevel(),
                g.getTargetEco(),
                g.getTargetOpening(),
                g.getStatus(),
                g.getResult(),
                g.getEndReason(),
                g.isRated());
    }

    private GameSummary summary(PracticeGame g, Set<UUID> measured) {
        return new GameSummary(
                g.getId(),
                g.getGameId(),
                g.getStartedAt(),
                g.getFinishedAt(),
                g.getStatus(),
                g.getResult(),
                g.getSkillLevel(),
                g.getTargetOpening(),
                g.isRated(),
                g.getGameId() != null && measured.contains(g.getGameId()));
    }
}
