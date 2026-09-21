package com.praxis.play.patterns.detectors;

import com.praxis.play.patterns.AnalysedGame;
import com.praxis.play.patterns.Occurrence;
import com.praxis.play.patterns.PerGameDetector;
import com.praxis.service.analysis.ParsedMove;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Castled late, or not at all.
 *
 * The cleanest detector here: castling is a single unambiguous token in the
 * movetext, so there is no threshold to argue about beyond "how late is late"
 * and no engine in the loop.
 */
@Component
public class CastlingDelayDetector extends PerGameDetector {

    /** Ply 20 is move 10 — the conventional "should have castled by now" mark. */
    private static final int LATE_AFTER_PLY = 20;

    /**
     * A game shorter than this never reached the question. Judging it would make
     * every quick loss look like a castling problem.
     */
    private static final int MIN_PLY_TO_JUDGE = 20;

    @Override public String id()    { return "castling-delay"; }
    @Override public String title() { return "You castle late"; }

    @Override public String why() {
        return "An uncastled king stays on the file the opponent will open, and the rook "
             + "it should connect with never joins the game.";
    }

    @Override public String whatToDo() {
        return "Aim to castle inside the first ten moves unless there is a concrete "
             + "reason not to — a forcing line, or a king already safer where it stands.";
    }

    @Override
    protected boolean judgeable(AnalysedGame game) {
        return game.plyCount() >= MIN_PLY_TO_JUDGE;
    }

    @Override
    protected Optional<Occurrence> examine(AnalysedGame game) {
        Integer castledAt = castlingPly(game);

        if (castledAt == null) {
            return Optional.of(Occurrence.forGame(game.gameId(),
                    "Never castled (" + game.playedOn() + ")"));
        }
        if (castledAt > LATE_AFTER_PLY) {
            return Optional.of(Occurrence.atPly(game.gameId(), castledAt,
                    "Castled on move " + fullMove(castledAt) + " (" + game.playedOn() + ")"));
        }
        return Optional.empty();
    }

    @Override
    protected String finding(int affected, int considered, List<Occurrence> occurrences) {
        long never = occurrences.stream().filter(o -> o.ply() == null).count();
        String base = "You castled after move " + (LATE_AFTER_PLY / 2)
                + " in " + ratio(affected, considered);
        return never == 0
                ? base + "."
                : base + ", and never castled in " + never + " of them.";
    }

    /** The ply on which the player castled, or null if they never did. */
    private static Integer castlingPly(AnalysedGame game) {
        for (ParsedMove move : game.playerMoves()) {
            if (isCastle(move.san())) return move.moveNumber();
        }
        return null;
    }

    /** SAN carries check and mate marks: "O-O+", "O-O-O#". Both are still castling. */
    private static boolean isCastle(String san) {
        if (san == null) return false;
        String bare = san.replace("+", "").replace("#", "").replace("!", "").replace("?", "");
        return "O-O".equals(bare) || "O-O-O".equals(bare)
                || "0-0".equals(bare) || "0-0-0".equals(bare);
    }

    private static int fullMove(int ply) {
        return (ply + 1) / 2;
    }
}
