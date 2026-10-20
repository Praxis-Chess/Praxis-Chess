package com.praxis.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** U5: the stored eval curve is handed to Game Analysis only when it lines up with the moves. */
class GameReviewCurveTest {

    @Test
    @DisplayName("One value per half-move, a blank where nothing was measured")
    void parses() {
        assertThat(GameReviewDto.curve("0.3,-0.12,,100", 4)).isEqualTo(Arrays.asList(0.3, -0.12, null, 100.0));
    }

    @Test
    @DisplayName("No curve, or one that does not line up with the moves: none, never a misaligned graph")
    void absentOrMisaligned() {
        assertThat(GameReviewDto.curve(null, 4)).isNull();
        assertThat(GameReviewDto.curve("", 4)).isNull();
        assertThat(GameReviewDto.curve("0.3,0.4", 4)).isNull();
    }
}
