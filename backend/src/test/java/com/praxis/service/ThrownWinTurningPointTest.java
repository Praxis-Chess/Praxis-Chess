package com.praxis.service;

import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Insights' "Thrown-Away Wins" link opens Game Analysis on the move that let the win go. */
@DisplayName("Thrown-away win: turning point")
class ThrownWinTurningPointTest {

    private static MoveError mistake(int ply, double evalBeforeWhite, double winPctDrop) {
        return MoveError.builder().moveNumber(ply).movePlayed("x" + ply)
                .evalBefore(evalBeforeWhite).winPctDrop(winPctDrop).build();
    }

    @Test
    @DisplayName("the costliest mistake made while still winning, not the costliest overall")
    void picksTheCostliestMistakeFromAWinningPosition() {
        Game white = Game.builder().playerColor("white").build();
        var opening = mistake(9, 0.2, 40);      // costliest, but the game was level
        var slip = mistake(47, 4.5, 30);        // made at +4.5: this threw the win
        var minor = mistake(51, 3.0, 5);
        assertThat(InsightsService.turningPoint(white, List.of(opening, slip, minor))).isSameAs(slip);
    }

    @Test
    @DisplayName("evaluations are White's, so Black's winning positions are negative")
    void readsEvaluationsFromThePlayersSide() {
        Game black = Game.builder().playerColor("black").build();
        var whiteWinning = mistake(20, 3.0, 50);  // +3.0 for White: Black was losing
        var blackWinning = mistake(30, -3.5, 20); // −3.5 for White: Black was winning
        assertThat(InsightsService.turningPoint(black, List.of(whiteWinning, blackWinning))).isSameAs(blackWinning);
    }

    @Test
    @DisplayName("no mistake from a winning position: the costliest of the game; none at all: null")
    void fallsBack() {
        Game white = Game.builder().playerColor("white").build();
        var a = mistake(11, 0.5, 12);
        var b = mistake(15, 1.0, 35);
        assertThat(InsightsService.turningPoint(white, List.of(a, b))).isSameAs(b);
        assertThat(InsightsService.turningPoint(white, List.of())).isNull();
    }
}
