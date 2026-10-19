package com.praxis.report;

import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.report.ReportFactsBuilder.GameRow;
import com.praxis.report.ReportFactsBuilder.Mistake;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 12: every figure and ranking of the Pattern Report, computed in code. */
@DisplayName("Pattern Report facts")
class ReportFactsBuilderTest {

    private static final List<UUID> GAMES = new ArrayList<>();
    static {
        for (int i = 0; i < 30; i++) GAMES.add(UUID.randomUUID());
    }

    private static Mistake m(int game, int ply, Severity s, GamePhase phase, String motif, String mechanism,
                             Integer clock, double drop) {
        return new Mistake(GAMES.get(game), ply, s, phase, clock, drop, "fen" + ply, "Nf6", "e2e4",
                LocalDate.of(2026, 9, 1 + game % 28), "Italian Game", motif != null, motif, mechanism);
    }

    /** n games of one opening; each with 10 opening, 20 middlegame, 5 endgame moves by the player. */
    private static List<GameRow> games(int n, String opening) {
        List<GameRow> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new GameRow(GAMES.get(i), "C50", opening, new int[]{10, 20, 5}, new int[]{10, 10, 10, 5}));
        }
        return out;
    }

    private static List<Mistake> times(int n, int firstGame, int ply, Severity s, GamePhase phase, String motif,
                                       String mechanism, double drop) {
        List<Mistake> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(m(firstGame + i, ply, s, phase, motif, mechanism, null, drop));
        return out;
    }

    @Test
    void weaknessesAreRankedByWinningChancesLostNotByCount() {
        List<Mistake> ms = new ArrayList<>();
        // 14 ignored threats losing 10 points each (140) against 10 allowed tactics losing 30 each (300).
        ms.addAll(times(14, 0, 30, Severity.MISTAKE, GamePhase.MIDDLEGAME, "POSITIONAL", "IGNORED_THREAT", 10));
        ms.addAll(times(10, 0, 50, Severity.BLUNDER, GamePhase.MIDDLEGAME, "FORK", "CREATED_TACTIC", 30));

        ReportFacts f = ReportFactsBuilder.build(ms, games(20, "Italian Game"));

        // The rarer one costs more, so it comes first: "costliest" means what it says.
        assertThat(f.topCauses()).extracting(ReportFacts.Weakness::key).containsExactly("CREATED_TACTIC", "IGNORED_THREAT");
        assertThat(f.topCauses().get(0).lost()).isEqualTo(300);
        assertThat(f.topCauses().get(0).avgLost()).isEqualTo(30.0);
        assertThat(f.topTactics()).extracting(ReportFacts.Weakness::key).containsExactly("FORK");
        assertThat(f.topTactics().get(0).positions().get(0).move()).isEqualTo("25...Nf6");   // ply 50 is Black's 25th
        assertThat(f.noNamedTactic()).isEqualTo(14);
    }

    @Test
    void oneThresholdEverywhereFewerThanTenIsNeverRanked() {
        List<Mistake> ms = new ArrayList<>(times(9, 0, 30, Severity.BLUNDER, GamePhase.ENDGAME, "FORK", "IGNORED_THREAT", 40));
        ReportFacts f = ReportFactsBuilder.build(ms, games(9, "Italian Game"));

        assertThat(f.minSample()).isEqualTo(10);
        assertThat(f.topCauses()).isEmpty();
        assertThat(f.topTactics()).isEmpty();
        assertThat(f.byPhase().get(2).enough()).isFalse();   // 9 endgame mistakes: shown, marked too few
        assertThat(f.worstPhase()).isNull();                  // and never "the worst phase"
    }

    @Test
    void whereIsARatePerHundredMovesNotARawCount() {
        List<Mistake> ms = new ArrayList<>();
        // 20 games: 200 opening moves, 400 middlegame, 100 endgame.
        ms.addAll(times(12, 0, 30, Severity.MISTAKE, GamePhase.MIDDLEGAME, null, null, 10));
        ms.addAll(times(10, 0, 70, Severity.BLUNDER, GamePhase.ENDGAME, null, null, 10));
        ReportFacts f = ReportFactsBuilder.build(ms, games(20, "Italian Game"));

        assertThat(f.playerMoves()).isEqualTo(700);
        assertThat(f.byPhase().get(1).perHundred()).isEqualTo(3.0);    // 12 of 400
        assertThat(f.byPhase().get(2).perHundred()).isEqualTo(10.0);   // 10 of 100
        assertThat(f.worstPhase()).isEqualTo("ENDGAME");                // fewer in total, worse per move
    }

    @Test
    void everyCauseIsAccountedFor() {
        List<Mistake> ms = new ArrayList<>();
        ms.addAll(times(10, 0, 30, Severity.MISTAKE, GamePhase.MIDDLEGAME, "OTHER", "IGNORED_THREAT", 10));
        ms.addAll(times(4, 0, 32, Severity.MISTAKE, GamePhase.MIDDLEGAME, "OTHER", "UNCLEAR", 10));
        ms.addAll(times(3, 0, 34, Severity.MISTAKE, GamePhase.MIDDLEGAME, "OTHER", "NONE", 10));
        ReportFacts f = ReportFactsBuilder.build(ms, games(10, "Italian Game"));

        assertThat(f.causes()).extracting(ReportFacts.Count::key).containsExactly("IGNORED_THREAT", "UNCLEAR");
        assertThat(f.noCause()).isEqualTo(3);
    }

    @Test
    void openingsAreListedOnlyWellAboveTheOwnAverage() {
        List<GameRow> rows = new ArrayList<>(games(5, "Italian Game"));
        for (int i = 5; i < 10; i++) rows.add(new GameRow(GAMES.get(i), "B01", "Scandinavian", new int[]{10, 20, 5}, new int[4]));
        List<Mistake> ms = new ArrayList<>();
        for (int i = 0; i < 15; i++) ms.add(m(i % 5, 30 + i, Severity.MISTAKE, GamePhase.MIDDLEGAME, null, null, null, 10));
        for (int i = 5; i < 10; i++) ms.add(m(i, 30, Severity.MISTAKE, GamePhase.MIDDLEGAME, null, null, null, 10));

        ReportFacts f = ReportFactsBuilder.build(ms, rows);

        assertThat(f.perGame()).isEqualTo(2.0);                       // 20 in 10 games
        assertThat(f.openings()).extracting(ReportFacts.OpeningRow::name).containsExactly("Italian Game");
        assertThat(f.openings().get(0).vsAverage()).isEqualTo(1.5);   // 3.0 against 2.0
    }

    @Test
    void clockFiguresNeedTenMistakesBehindThem() {
        List<Mistake> few = new ArrayList<>();
        for (int i = 0; i < 4; i++) few.add(m(i, 60, Severity.BLUNDER, GamePhase.ENDGAME, "POSITIONAL", "NONE", 10, 30));
        assertThat(ReportFactsBuilder.build(few, games(4, "Italian Game")).timePressure().enough()).isFalse();

        List<Mistake> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(m(i, 60, Severity.BLUNDER, GamePhase.ENDGAME, "POSITIONAL", "NONE", 12, 30));
        for (int i = 0; i < 12; i++) many.add(m(i, 30, Severity.MISTAKE, GamePhase.MIDDLEGAME, "POSITIONAL", "NONE", 300, 10));
        ReportFacts f = ReportFactsBuilder.build(many, games(12, "Italian Game"));
        assertThat(f.timePressure().pct()).isEqualTo(50);
        assertThat(f.topCauses()).extracting(ReportFacts.Weakness::key).containsExactly("UNDER_30S");
    }

    @Test
    void moveRangesAreFullMovesAndLabelsReadLikeChess() {
        assertThat(ReportFactsBuilder.range(20)).isEqualTo("1-10");    // ply 20 is Black's 10th move
        assertThat(ReportFactsBuilder.range(21)).isEqualTo("11-20");
        assertThat(ReportFactsBuilder.range(61)).isEqualTo("31+");
        assertThat(ReportFactsBuilder.moveLabel(21, "Bc4")).isEqualTo("11.Bc4");
        assertThat(ReportFactsBuilder.moveLabel(22, "Nf6")).isEqualTo("11...Nf6");
    }

    @Test
    @DisplayName("every cause and tactic has a short drill of at most eight words")
    void shortDrills() {
        var keys = new ArrayList<String>(ReportFactsBuilder.CAUSES.keySet());
        keys.addAll(ReportFactsBuilder.TACTIC_DRILLS.keySet());
        assertThat(ReportFactsBuilder.shortDrills()).containsOnlyKeys(keys);
        ReportFactsBuilder.shortDrills().values()
                .forEach(d -> assertThat(d.split("\\s+")).as(d).hasSizeLessThanOrEqualTo(8));
    }
}
