package com.praxis.play;

import com.praxis.config.PraxisClock;
import com.praxis.domain.Game;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.domain.enums.GameSource;
import com.praxis.domain.enums.MeaningfulActivity;
import com.praxis.pipeline.GameAnalysisTransactionService;
import com.praxis.play.domain.PracticeGame;
import com.praxis.play.domain.enums.PracticeStatus;
import com.praxis.play.repository.PracticeGameRepository;
import com.praxis.repository.GameRepository;
import com.praxis.service.analysis.AnalysisProfile;
import com.praxis.service.practice.PracticeLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

/**
 * Turns a finished practice game into a standard PGN and pushes it through the
 * EXISTING analysis pipeline.
 * This is the whole reason the feature can measure anything. {@code PgnParserService}
 * needs nothing Chess.com-specific — standard headers plus SAN, with clock and
 * eval annotations optional — so a locally-played game runs through
 * {@code analyzeOne} unchanged: same {@code MistakeCandidateFilter} thresholds,
 * same severity rules, same accuracy formula as every Chess.com game already
 * analysed.
 * Two games measured with two different rulers cannot be compared. This keeps
 * one ruler.
 */
@Service
public class PracticeGameArchiver {

    private static final Logger log = LoggerFactory.getLogger(PracticeGameArchiver.class);
    private static final DateTimeFormatter PGN_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final PracticeGameRepository practiceGames;
    private final GameRepository games;
    private final GameAnalysisTransactionService analysis;
    private final PracticeLedgerService ledger;
    private final PraxisClock clock;
    private final PracticeAnalysisProgress progress;

    public PracticeGameArchiver(PracticeGameRepository practiceGames, GameRepository games,
                                GameAnalysisTransactionService analysis,
                                PracticeLedgerService ledger, PraxisClock clock,
                                PracticeAnalysisProgress progress) {
        this.practiceGames = practiceGames;
        this.games = games;
        this.analysis = analysis;
        this.ledger = ledger;
        this.clock = clock;
        this.progress = progress;
    }

    /**
     * Archive and analyse, off the request thread.
     * Uses {@code practiceExecutor}, NOT the single-threaded {@code analysisExecutor}:
     * a post-game report must not sit behind a hundred-game Re-analyze All run,
     * and it must not corrupt that run's shared progress tracker either.
     */
    @Async("practiceExecutor")
    public void archiveAndAnalyse(java.util.UUID practiceGameId) {
        try {
            Game archived = archive(practiceGameId);
            if (archived == null) return;
            // The deeper profile: one game, its own executor, someone watching.
            // That someone is the reason progress is reported at all.
            analysis.analyzeOne(archived, AnalysisProfile.PRACTICE,
                    progress.sinkFor(archived.getId()));
            log.info("[play] analysed practice game {}", archived.getId());
        } catch (Exception e) {
            // The game is already stored; only its analysis failed. The report
            // endpoint reports what it has rather than pretending the game
            // never happened.
            log.warn("[play] could not analyse practice game {}: {}", practiceGameId, e.getMessage(), e);
        }
    }

    @Transactional
    protected Game archive(java.util.UUID practiceGameId) {
        PracticeGame pg = practiceGames.findById(practiceGameId).orElse(null);
        if (pg == null) return null;
        if (pg.getStatus() != PracticeStatus.FINISHED) {
            // Abandoned games are kept for honesty but never analysed or counted.
            return null;
        }
        if (pg.getGameId() != null) {
            return games.findById(pg.getGameId()).orElse(null);   // already archived
        }
        if (pg.getSanMoves() == null || pg.getSanMoves().isBlank()) return null;

        Game game = Game.builder()
                .chessComId(null)                 // nullable for practice games
                .source(GameSource.PRACTICE)      // keeps it out of every real-play statistic
                .username(pg.getUsername())
                .playedAt(pg.getFinishedAt() != null ? pg.getFinishedAt() : clock.now())
                .timeControl("unlimited")
                .timeClass("practice")
                .playerColor(pg.getPlayerColor())
                .result(pg.getResult() == null ? "draw" : pg.getResult())
                .openingEco(pg.getTargetEco())
                .openingName(pg.getTargetOpening())
                .rawPgn(toPgn(pg))
                .analysisStatus(AnalysisStatus.PENDING)
                .build();
        games.save(game);

        pg.setGameId(game.getId());
        practiceGames.save(pg);

        ledger.record(pg.getUsername(), MeaningfulActivity.PRACTICE_GAME_PLAYED);
        return game;
    }

    /**
     * Standard PGN. The header names matter: PgnParserService decides which
     * colour the player had by matching the configured username against White
     * and Black, so the player's own name must appear on the correct side.
     */
    String toPgn(PracticeGame pg) {
        boolean playerIsWhite = "white".equals(pg.getPlayerColor());
        String player = pg.getUsername();
        String engine = "Praxis Engine (skill " + pg.getSkillLevel() + ")";

        String result = switch (pg.getResult() == null ? "draw" : pg.getResult()) {
            case "win"  -> playerIsWhite ? "1-0" : "0-1";
            case "loss" -> playerIsWhite ? "0-1" : "1-0";
            default     -> "1/2-1/2";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("[Event \"Praxis Practice\"]\n");
        sb.append("[Site \"Praxis\"]\n");
        sb.append("[Date \"")
          .append((pg.getFinishedAt() != null ? pg.getFinishedAt() : clock.now()).format(PGN_DATE))
          .append("\"]\n");
        sb.append("[White \"").append(playerIsWhite ? player : engine).append("\"]\n");
        sb.append("[Black \"").append(playerIsWhite ? engine : player).append("\"]\n");
        sb.append("[Result \"").append(result).append("\"]\n");
        if (pg.getTargetEco() != null)     sb.append("[ECO \"").append(pg.getTargetEco()).append("\"]\n");
        if (pg.getTargetOpening() != null) sb.append("[Opening \"").append(pg.getTargetOpening()).append("\"]\n");
        sb.append("[Termination \"").append(pg.getEndReason() == null ? "NORMAL" : pg.getEndReason()).append("\"]\n");
        sb.append('\n');

        // Movetext with numbering. No [%clk]/[%eval]: the parser treats both as
        // optional, and inventing them would put fabricated data in front of the
        // analyser.
        String[] san = pg.getSanMoves().trim().split("\\s+");
        for (int i = 0; i < san.length; i++) {
            if (i % 2 == 0) sb.append(i / 2 + 1).append(". ");
            sb.append(san[i]).append(' ');
        }
        sb.append(result).append('\n');
        return sb.toString();
    }
}
