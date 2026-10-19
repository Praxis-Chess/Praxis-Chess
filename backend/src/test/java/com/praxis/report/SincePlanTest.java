package com.praxis.report;

import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.report.ReportFactsBuilder.GameRow;
import com.praxis.report.ReportFactsBuilder.Mistake;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** F10: a plan's focus, in games since the plan against before it, per 100 moves; silent on too little play. */
@DisplayName("Since this plan")
class SincePlanTest {

    private static final LocalDate PLAN = LocalDate.of(2026, 10, 16);
    private static final PlanFacts.Priority THREAT =
            new PlanFacts.Priority(1, "CAUSE", "IGNORED_THREAT", "Ignoring your opponent's threat", "", "", 0, 0, 0, 0, 0, 0);
    private static final PlanFacts.Priority FORK =
            new PlanFacts.Priority(0, "TACTIC", "FORK", "Forks", "", "", 0, 0, 0, 0, 0, 0);

    private final List<GameRow> games = new ArrayList<>();
    private final Map<UUID, LocalDate> played = new HashMap<>();
    private final List<Mistake> mistakes = new ArrayList<>();

    /** A game of 50 player moves on a day, with mistakes of the given mechanisms. */
    private void game(LocalDate day, Severity severity, String... mechanisms) {
        UUID id = UUID.randomUUID();
        games.add(new GameRow(id, "C50", "Italian Game", new int[]{20, 25, 5}, new int[4]));
        played.put(id, day);
        int ply = 10;
        for (String mech : mechanisms) {
            mistakes.add(new Mistake(id, ply++, severity, GamePhase.MIDDLEGAME, null, 20.0, "fen", "Nf6", "e2e4",
                    day, "Italian Game", true, "FORK", mech));
        }
    }

    @Test
    @DisplayName("rates are per 100 of the player's moves, before and after the plan's day")
    void rates() {
        game(PLAN.minusDays(3), Severity.BLUNDER, "IGNORED_THREAT", "IGNORED_THREAT", "MISSED_OPPORTUNITY");
        game(PLAN.minusDays(2), Severity.MISTAKE, "IGNORED_THREAT");
        for (int i = 0; i < 5; i++) game(PLAN.plusDays(i), Severity.BLUNDER, "MISSED_OPPORTUNITY", "MISSED_OPPORTUNITY");

        SincePlan.Result r = SincePlan.compute(mistakes, games, played, PLAN, List.of(THREAT, FORK));

        SincePlan.Change threat = r.foci().get(0);
        assertThat(threat.before()).isEqualTo(new SincePlan.Window(2, 100, 3, 3.0));
        assertThat(threat.after()).isEqualTo(new SincePlan.Window(5, 250, 0, 0.0));
        // Every fixture mistake is also a fork: tactics are matched on the motif.
        assertThat(r.foci().get(1).after().count()).isEqualTo(10);
        assertThat(r.seriousAfter()).isEqualTo(10);
        assertThat(r.enough()).isTrue();
    }

    @Test
    @DisplayName("with fewer than ten serious mistakes since the plan, it is not enough to compare")
    void silentOnTooLittlePlay() {
        game(PLAN.minusDays(1), Severity.BLUNDER, "IGNORED_THREAT");
        game(PLAN, Severity.BLUNDER, "IGNORED_THREAT", "IGNORED_THREAT");
        // An inaccuracy is not a serious mistake: it does not count toward the sample.
        game(PLAN.plusDays(1), Severity.INACCURACY, "IGNORED_THREAT", "IGNORED_THREAT", "IGNORED_THREAT");

        SincePlan.Result r = SincePlan.compute(mistakes, games, played, PLAN, List.of(THREAT));

        assertThat(r.seriousAfter()).isEqualTo(2);
        assertThat(r.minSample()).isEqualTo(10);
        assertThat(r.enough()).isFalse();
    }
}
