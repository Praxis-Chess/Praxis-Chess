package com.praxis.play;

import com.praxis.play.domain.PracticeGame;
import com.praxis.service.analysis.PgnParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated PGN is the join between this feature and every measurement the
 * app already makes. If PgnParserService cannot read it, or reads the colours
 * the wrong way round, the analysis silently describes the wrong player — and
 * the improvement report would then be confidently backwards.
 *
 * So these tests parse the output with the REAL parser rather than asserting on
 * strings.
 */
@DisplayName("PracticeGameArchiver PGN Tests")
class PracticeGameArchiverTest {

    private PracticeGameArchiver archiver;
    private PgnParserService parser;

    @BeforeEach
    void setUp() {
        // Only toPgn is under test; it touches no collaborator.
        archiver = new PracticeGameArchiver(null, null, null, null, null, null);
        parser = new PgnParserService();
    }

    private static PracticeGame game(String colour, String result, String san) {
        return PracticeGame.builder()
                .username("latt1ce")
                .playerColor(colour)
                .result(result)
                .skillLevel(7)
                .sanMoves(san)
                .currentFen("startpos")
                .endReason("CHECKMATE")
                .finishedAt(OffsetDateTime.of(2026, 8, 17, 12, 0, 0, 0, ZoneOffset.UTC))
                .build();
    }

    @Nested
    @DisplayName("Round Trip Tests")
    class RoundTripTests {

        @Test
        @DisplayName("Should produce a PGN the real parser can read")
        void shouldProducePgnTheParserCanRead() {
            String pgn = archiver.toPgn(game("white", "win", "e4 e5 Nf3 Nc6 Bb5"));

            var parsed = parser.parse("id", pgn, "latt1ce");

            assertThat(parsed.moves()).hasSize(5);
            assertThat(parsed.moves().get(0).san()).isEqualTo("e4");
        }

        @Test
        @DisplayName("Should put the player on White when they had White")
        void shouldPlacePlayerOnWhite() {
            String pgn = archiver.toPgn(game("white", "win", "e4 e5"));

            assertThat(pgn).contains("[White \"latt1ce\"]");
            assertThat(pgn).contains("[Black \"Praxis Engine (skill 7)\"]");
            assertThat(parser.parse("id", pgn, "latt1ce").playerColor()).isEqualTo("white");
        }

        @Test
        @DisplayName("Should put the player on Black when they had Black")
        void shouldPlacePlayerOnBlack() {
            // The parser decides colour by matching the configured username
            // against the header. Get this wrong and every mistake is attributed
            // to the engine instead of the player.
            String pgn = archiver.toPgn(game("black", "loss", "e4 e5"));

            assertThat(pgn).contains("[Black \"latt1ce\"]");
            assertThat(parser.parse("id", pgn, "latt1ce").playerColor()).isEqualTo("black");
        }
    }

    @Nested
    @DisplayName("Result Encoding Tests")
    class ResultEncodingTests {

        @Test
        @DisplayName("Should encode a win as 1-0 for White and 0-1 for Black")
        void shouldEncodeWinFromPlayersSide() {
            assertThat(archiver.toPgn(game("white", "win", "e4"))).contains("[Result \"1-0\"]");
            assertThat(archiver.toPgn(game("black", "win", "e4 e5"))).contains("[Result \"0-1\"]");
        }

        @Test
        @DisplayName("Should encode a loss from the player's side")
        void shouldEncodeLossFromPlayersSide() {
            assertThat(archiver.toPgn(game("white", "loss", "e4"))).contains("[Result \"0-1\"]");
            assertThat(archiver.toPgn(game("black", "loss", "e4 e5"))).contains("[Result \"1-0\"]");
        }

        @Test
        @DisplayName("Should encode a draw the same either way")
        void shouldEncodeDraw() {
            assertThat(archiver.toPgn(game("white", "draw", "e4"))).contains("[Result \"1/2-1/2\"]");
            assertThat(archiver.toPgn(game("black", "draw", "e4 e5"))).contains("[Result \"1/2-1/2\"]");
        }
    }

    @Nested
    @DisplayName("Movetext Tests")
    class MovetextTests {

        @Test
        @DisplayName("Should number moves in pairs")
        void shouldNumberMovesInPairs() {
            String pgn = archiver.toPgn(game("white", "win", "e4 e5 Nf3 Nc6"));

            assertThat(pgn).contains("1. e4 e5 2. Nf3 Nc6");
        }

        @Test
        @DisplayName("Should handle an odd number of plies")
        void shouldHandleOddPlyCount() {
            String pgn = archiver.toPgn(game("white", "win", "e4 e5 Nf3"));

            assertThat(parser.parse("id", pgn, "latt1ce").moves()).hasSize(3);
        }

        @Test
        @DisplayName("Should not fabricate clock or eval annotations")
        void shouldNotFabricateAnnotations() {
            // The parser treats both as optional. Inventing them would feed
            // made-up data straight into the analyser.
            String pgn = archiver.toPgn(game("white", "win", "e4 e5"));

            assertThat(pgn).doesNotContain("%clk");
            assertThat(pgn).doesNotContain("%eval");
        }
    }
}
