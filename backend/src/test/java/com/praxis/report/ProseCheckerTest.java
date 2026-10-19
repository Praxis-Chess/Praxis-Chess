package com.praxis.report;

import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 12, step 4: report prose may say only what the facts say. */
@DisplayName("Report number checker")
class ProseCheckerTest {

    /** 51 serious middlegame mistakes of 68 (75%), 6 forks among them. */
    private static ReportFacts facts() {
        List<ReportFactsBuilder.Mistake> ms = new ArrayList<>();
        List<ReportFactsBuilder.GameRow> games = new ArrayList<>();
        for (int i = 0; i < 68; i++) {
            UUID g = UUID.randomUUID();
            games.add(new ReportFactsBuilder.GameRow(g, "C50", "Italian Game"));
            GamePhase phase = i < 51 ? GamePhase.MIDDLEGAME : i < 62 ? GamePhase.OPENING : GamePhase.ENDGAME;
            String motif = i < 6 ? "FORK" : "POSITIONAL";
            ms.add(new ReportFactsBuilder.Mistake(g, 30, Severity.BLUNDER, phase, null, 20.0, "fen", "Nf6", "e2e4",
                    LocalDate.of(2026, 9, 1), "Italian Game", true, motif, i < 6 ? "CREATED_TACTIC" : "NONE"));
        }
        return ReportFactsBuilder.build(ms, games);
    }

    @Test
    void quotedFiguresPass() {
        var r = ProseChecker.check("51 of your 68 serious mistakes came in the middlegame, "
                + "and 6 of them were forks.", facts());
        assertThat(r.violations()).isEmpty();
        assertThat(r.passed()).isTrue();
    }

    @Test
    void anInventedNumberFailsAndIsNamed() {
        var r = ProseChecker.check("Most of your mistakes, 80%, come in the middlegame.", facts());
        assertThat(r.passed()).isFalse();
        assertThat(r.violations()).containsExactly("the number 80 is not in the facts");
    }

    @Test
    void aTacticTheFactsDoNotContainFails() {
        var r = ProseChecker.check("You keep walking into pins.", facts());
        assertThat(r.passed()).isFalse();
        assertThat(r.violations()).containsExactly("it names pin, which the facts do not contain");
    }

    /** Step 7 found "You make forks": right number, right tactic, backwards. */
    @Test
    void aTacticIsNeverOneThePlayerMade() {
        var r = ProseChecker.check("You make forks in 6 of your serious mistakes.", facts());
        assertThat(r.violations())
                .containsExactly("it says you made the tactic; these are tactics that caught you or that you missed");
        assertThat(ProseChecker.check("Forks caught you 6 times.", facts()).passed()).isTrue();
    }

    /** Step 8 found "it leads to the most losses": the facts hold no results at all. */
    @Test
    void noClaimsAboutResultsOrRating() {
        var r = ProseChecker.check("It comes first because it leads to the most losses.", facts());
        assertThat(r.violations()).containsExactly("it talks about results or rating, which the facts do not contain");
        assertThat(ProseChecker.check("Captures that lose material come next.", facts()).passed()).isTrue();
    }

    @Test
    void timeTroubleNeedsAClockInTheFacts() {
        var r = ProseChecker.check("Watch your clock.", facts());
        assertThat(r.violations()).containsExactly("it mentions time trouble, which the facts do not show");
    }

    @Test
    void chessNotationAndWordsAreNotNumbers() {
        var r = ProseChecker.check("After Nf6 and Qxf7, look at the e4 square first.", facts());
        assertThat(r.passed()).isTrue();
    }

    @Test
    void decimalsAreComparedAsNumbers() {
        assertThat(ProseChecker.normalise("2.0")).isEqualTo("2");
        assertThat(ProseChecker.normalise("0.70")).isEqualTo("0.7");
        assertThat(ProseChecker.normalise("10")).isEqualTo("10");
    }

    @Test
    void emptyProsePasses() {
        assertThat(ProseChecker.check("", facts()).passed()).isTrue();
    }
}
