package com.praxis.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** N11: a game's opening is read from its PGN header at sync, not only after analysis. */
class OpeningFromPgnTest {

    @Test
    @DisplayName("Reads the ECO header of a Chess.com PGN")
    void readsEco() {
        String pgn = "[Event \"Live Chess\"]\n[Site \"Chess.com\"]\n[ECO \"C41\"]\n"
                + "[ECOUrl \"https://www.chess.com/openings/Philidor-Defense\"]\n\n1. e4 e5 2. Nf3 d6 *";
        assertThat(OpeningFromPgn.eco(pgn)).isEqualTo("C41");
    }

    @Test
    @DisplayName("No header, a malformed code, or no PGN: null, never a guess")
    void absent() {
        assertThat(OpeningFromPgn.eco("[Event \"x\"]\n\n1. e4 *")).isNull();
        assertThat(OpeningFromPgn.eco("[ECO \"?\"]")).isNull();
        assertThat(OpeningFromPgn.eco(null)).isNull();
    }
}
