package com.praxis.play;

import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.prax.intelligence.ChessIntelligence.ColorStats;
import com.praxis.report.ReportFacts;
import com.praxis.report.ReportFactsBuilder;
import com.praxis.report.ReportFactsBuilder.GameRow;
import com.praxis.report.ReportFactsBuilder.Mistake;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Play's opponent, from the same source as the Pattern Report (PLAY_PLAN.md,
 * decisions P2–P4): the opening it steers toward is the report's first opening
 * above your average, what to watch for is the report's, and the strength says
 * when it is a cap rather than a calibration.
 */
@DisplayName("Opponent profile")
class OpponentProfileServiceTest {

    private final OpponentProfileService service = new OpponentProfileService(null, null, null);

    /** 5 Italian games with 15 serious mistakes, 5 Scandinavian with 5: 3.0 vs 2.0 per game. */
    private static ReportFacts facts() {
        List<GameRow> games = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            games.add(new GameRow(id, i < 5 ? "C50" : "B01", i < 5 ? "Italian Game" : "Scandinavian",
                    new int[]{10, 20, 5}, new int[4]));
        }
        List<Mistake> ms = new ArrayList<>();
        for (int i = 0; i < 15; i++) ms.add(mistake(ids.get(i % 5), 30 + i));
        for (int i = 5; i < 10; i++) ms.add(mistake(ids.get(i), 30));
        return ReportFactsBuilder.build(ms, games);
    }

    private static Mistake mistake(UUID game, int ply) {
        return new Mistake(game, ply, Severity.MISTAKE, GamePhase.MIDDLEGAME, null, 10.0, "fen", "Nf6", "e2e4",
                LocalDate.of(2026, 9, 1), "Italian Game", true, "POSITIONAL", "NONE");
    }

    @Test
    @DisplayName("the strength says when it is the cap, not a calibration")
    void skillCap() {
        assertThat(OpponentProfileService.rawSkill(83.0)).isEqualTo(15);
        assertThat(OpponentProfileService.calibrateSkill(83.0)).isEqualTo(14);
        assertThat(OpponentProfileService.calibrateSkill(68.0)).isEqualTo(9);
        assertThat(OpponentProfileService.calibrateSkill(null)).isEqualTo(5);
    }

    @Test
    @DisplayName("the opening is the report's first opening above your average, with its figures")
    void openingFromTheReport() {
        OpponentProfileService.Opening o = OpponentProfileService.opening(facts());

        assertThat(o.eco()).isEqualTo("C50");
        assertThat(o.name()).isEqualTo("Italian Game");
        assertThat(o.games()).isEqualTo(5);
        assertThat(o.seriousPerGame()).isEqualTo(3.0);
        assertThat(o.average()).isEqualTo(2.0);
        assertThat(OpponentProfileService.opening(null)).isNull();
    }

    @Test
    @DisplayName("watch for is the report's worst phase, against your overall rate")
    void watchForFromTheReport() {
        OpponentProfileService.WatchFor w = OpponentProfileService.watchFor(facts());

        assertThat(w.phase().key()).isEqualTo("MIDDLEGAME");
        assertThat(w.phase().perHundred()).isEqualTo(10.0);   // 20 in 200 middlegame moves
        assertThat(w.phase().overall()).isEqualTo(5.7);       // 20 in 350 moves
        assertThat(w.cause()).isNull();                       // no mechanism found: nothing ranked
        assertThat(OpponentProfileService.watchFor(null)).isNull();
    }

    @Test
    @DisplayName("the weaker colour needs five games each, then accuracy, then win rate")
    void weakerColour() {
        var white = new ColorStats("white", 10, 6, 0.6, 80.0);
        var black = new ColorStats("black", 12, 4, 0.33, 85.0);
        assertThat(service.colour(Map.of("white", white, "black", black)))
                .extracting(OpponentProfileService.Colour::weaker, OpponentProfileService.Colour::basis)
                .containsExactly("white", "accuracy");

        var close = new ColorStats("black", 12, 4, 0.33, 80.5);
        assertThat(service.colour(Map.of("white", white, "black", close)))
                .extracting(OpponentProfileService.Colour::weaker, OpponentProfileService.Colour::basis)
                .containsExactly("black", "win rate");

        var few = new ColorStats("black", 3, 1, 0.33, 60.0);
        OpponentProfileService.Colour c = service.colour(Map.of("white", white, "black", few));
        assertThat(c.weaker()).isEqualTo("white");
        assertThat(c.basis()).isEqualTo("too few games");
        assertThat(c.black().games()).isEqualTo(3);
        assertThat(c.white().winPct()).isEqualTo(60.0);
    }
}
