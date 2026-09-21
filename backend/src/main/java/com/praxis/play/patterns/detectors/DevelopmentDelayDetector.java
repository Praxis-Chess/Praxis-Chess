package com.praxis.play.patterns.detectors;

import com.praxis.play.patterns.AnalysedGame;
import com.praxis.play.patterns.Occurrence;
import com.praxis.play.patterns.PerGameDetector;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Minor pieces still at home when the middlegame arrives.
 *
 * Read from the board rather than the movetext: a knight that went out and came
 * back is undeveloped, and counting moves would score it as developed.
 */
@Component
public class DevelopmentDelayDetector extends PerGameDetector {

    private static final int CHECK_AT_PLY = 20;          // move 10
    private static final int MIN_UNDEVELOPED = 2;
    private static final int MIN_PLY_TO_JUDGE = 20;

    // Home squares, as file/rank indices into the FEN placement field.
    private static final String WHITE_HOME_RANK = "1";
    private static final String BLACK_HOME_RANK = "8";

    @Override public String id()    { return "development-delay"; }
    @Override public String title() { return "Your pieces come out slowly"; }

    @Override public String why() {
        return "Pieces still on their starting squares are not defending or attacking "
             + "anything, so the middlegame starts a move or two down on force.";
    }

    @Override public String whatToDo() {
        return "Get both knights and both bishops off the back rank before starting "
             + "an attack or moving the same piece twice.";
    }

    @Override
    protected boolean judgeable(AnalysedGame game) {
        return game.plyCount() >= MIN_PLY_TO_JUDGE;
    }

    @Override
    protected Optional<Occurrence> examine(AnalysedGame game) {
        String fen = game.fenAfterPly(CHECK_AT_PLY);
        if (fen == null) return Optional.empty();

        int undeveloped = undevelopedMinors(fen, game.playerIsWhite());
        if (undeveloped < MIN_UNDEVELOPED) return Optional.empty();

        return Optional.of(Occurrence.atPly(game.gameId(), CHECK_AT_PLY,
                undeveloped + " minor pieces still at home on move " + (CHECK_AT_PLY / 2)
                        + " (" + game.playedOn() + ")"));
    }

    @Override
    protected String finding(int affected, int considered, List<Occurrence> occurrences) {
        return "Two or more minor pieces were still on their starting squares at move "
                + (CHECK_AT_PLY / 2) + " in " + ratio(affected, considered) + ".";
    }

    /**
     * Knights and bishops still on their own home squares.
     *
     * Walks the FEN placement field, which lists ranks 8 down to 1. Only the
     * player's own back rank matters, and only the four minor-piece squares on it.
     */
    static int undevelopedMinors(String fen, boolean playerIsWhite) {
        String placement = fen.split(" ")[0];
        String[] ranks = placement.split("/");
        if (ranks.length != 8) return 0;

        // ranks[0] is rank 8 (Black's home), ranks[7] is rank 1 (White's).
        String homeRank = playerIsWhite ? ranks[7] : ranks[0];
        char knight = playerIsWhite ? 'N' : 'n';
        char bishop = playerIsWhite ? 'B' : 'b';

        char[] squares = expand(homeRank);
        int count = 0;
        // b1/g1 knights, c1/f1 bishops — files b,c,f,g are indices 1,2,5,6.
        if (squares[1] == knight) count++;
        if (squares[6] == knight) count++;
        if (squares[2] == bishop) count++;
        if (squares[5] == bishop) count++;
        return count;
    }

    /** "r1bqkb1r" -> eight characters, digits expanded to spaces. */
    private static char[] expand(String rank) {
        char[] out = new char[8];
        int i = 0;
        for (char c : rank.toCharArray()) {
            if (Character.isDigit(c)) {
                int empty = c - '0';
                for (int k = 0; k < empty && i < 8; k++) out[i++] = ' ';
            } else if (i < 8) {
                out[i++] = c;
            }
        }
        while (i < 8) out[i++] = ' ';
        return out;
    }
}
