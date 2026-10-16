package com.praxis.service.ai;

import com.praxis.domain.MoveError;
import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.dto.TodayInsightDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Today's focus states real counts. No model, no motif labels: severity and
 * phase are engine-measured.
 */
@DisplayName("Today's focus")
class TodayInsightServiceTest {

    private static List<MoveError> errors(Severity s, int opening, int middlegame, int endgame) {
        List<MoveError> out = new ArrayList<>();
        for (int i = 0; i < opening; i++) out.add(MoveError.builder().severity(s).gamePhase(GamePhase.OPENING).build());
        for (int i = 0; i < middlegame; i++) out.add(MoveError.builder().severity(s).gamePhase(GamePhase.MIDDLEGAME).build());
        for (int i = 0; i < endgame; i++) out.add(MoveError.builder().severity(s).gamePhase(GamePhase.ENDGAME).build());
        return out;
    }

    @Test
    void namesThePhaseWithTheMostBlundersAndShowsTheCounts() {
        List<MoveError> e = errors(Severity.BLUNDER, 11, 51, 6);
        e.addAll(errors(Severity.MISTAKE, 29, 46, 3));

        TodayInsightDto card = TodayInsightService.build(e, 42);

        assertThat(card.isValid()).isTrue();
        assertThat(card.title()).isEqualTo("Cut your middlegame blunders");
        assertThat(card.evidence().metric()).isEqualTo("Blunders by game phase");
        assertThat(card.evidence().value()).isEqualTo("51 of 68 blunders in the middlegame (75%) · opening 11 · endgame 6");
        assertThat(card.evidence().sampleSize()).isEqualTo(42);
        assertThat(card.action()).contains("middlegame").doesNotContainIgnoringCase("tactic");
    }

    @Test
    void aTieNamesEveryTiedPhaseInsteadOfDefaultingToMiddlegame() {
        TodayInsightDto card = TodayInsightService.build(errors(Severity.BLUNDER, 0, 4, 4), 10);
        assertThat(card.title()).isEqualTo("Cut your middlegame and endgame blunders");
        assertThat(card.evidence().value()).isEqualTo("8 of 8 blunders in the middlegame and endgame (100%) · opening 0");
    }

    @Test
    void withoutBlundersItUsesMistakes() {
        TodayInsightDto card = TodayInsightService.build(errors(Severity.MISTAKE, 3, 1, 0), 5);
        assertThat(card.title()).isEqualTo("Cut your opening mistakes");
        assertThat(card.evidence().metric()).isEqualTo("Mistakes by game phase");
    }

    @Test
    void noAnalysedGamesNeverClaimsAPhase() {
        TodayInsightDto card = TodayInsightService.build(List.of(), 0);
        assertThat(card.isValid()).isTrue();
        assertThat(card.title()).isEqualTo("Analyse your games first");
        assertThat(card.title()).doesNotContain("middlegame");
    }

    @Test
    void analysedGamesWithoutMistakesSaySo() {
        TodayInsightDto card = TodayInsightService.build(errors(Severity.INACCURACY, 2, 2, 2), 3);
        assertThat(card.isValid()).isTrue();
        assertThat(card.title()).isEqualTo("No blunders or mistakes found");
    }
}
