package com.praxis.play;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.praxis.play.OpponentProfileService.OpponentProfile;
import com.praxis.play.domain.PracticeGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Chooses the opponent's move.
 *
 * Two mechanisms only, and both are grounded in measurements:
 *
 *   1. an opening book, so the game reaches the line the player is weakest in
 *   2. Stockfish at a calibrated skill for everything after that
 *
 * A third mechanism — steering mid-game toward positions where the player's
 * top blunder motif tends to appear — is deliberately NOT here. Biasing an
 * engine toward "positions where you hang pieces" is far harder than it sounds,
 * and done crudely it produces moves that feel artificial while weakening the
 * opponent in ways that corrupt the very measurement this feature exists for.
 * It belongs behind a flag, after the honest version works.
 */
@Service
public class OpponentService {

    private static final Logger log = LoggerFactory.getLogger(OpponentService.class);

    /**
     * Opening lines by ECO family, in UCI, from the OPPONENT's side of the board.
     *
     * Small on purpose. The book exists to reach a recognisable opening, not to
     * play theory — a handful of plies is enough to put the player in the
     * position they lose in, after which the engine takes over.
     */
    private static final Map<String, String[]> BOOK_AS_WHITE = Map.of(
            "C4", new String[]{"e2e4"},          // player answers e4 with e5/e6/c5…
            "C0", new String[]{"e2e4"},          // French family
            "B0", new String[]{"e2e4"},          // Sicilian / Caro / Scandi family
            "D0", new String[]{"d2d4"},
            "E0", new String[]{"d2d4", "c2c4"},
            "A0", new String[]{"c2c4"}
    );

    private static final Map<String, String[]> BOOK_AS_BLACK = Map.of(
            "B2", new String[]{"c7c5"},          // Sicilian
            "B0", new String[]{"c7c5"},
            "C0", new String[]{"e7e6"},          // French
            "C4", new String[]{"e7e5"},
            "D0", new String[]{"d7d5"},
            "E0", new String[]{"g8f6"},
            "A0", new String[]{"g8f6"}
    );

    private final StockfishPlayService engine;

    public OpponentService(StockfishPlayService engine) {
        this.engine = engine;
    }

    /**
     * @return the opponent's move in UCI, or null if it cannot move
     */
    public String chooseMove(Board board, PracticeGame game, OpponentProfile profile, int moveMs) {
        String booked = bookMove(board, game);
        if (booked != null) return booked;

        String uci = engine.bestMove(board.getFen(), game.getSkillLevel(), moveMs);
        if (uci != null) return uci;

        // The engine is unavailable or died. Rather than stranding the player
        // mid-game, play a legal move — the game stays completable, and the
        // analysis afterwards is honest about what was played.
        List<Move> legal = board.legalMoves();
        if (legal.isEmpty()) return null;
        log.warn("[play] engine unavailable — falling back to a legal move");
        return legal.get(0).toString();
    }

    /**
     * A book move if we are still inside the steered opening, else null.
     *
     * Only consulted for the first few plies and only when the profile actually
     * named a target — with no target, the opening is simply left open rather
     * than steered somewhere arbitrary.
     */
    private String bookMove(Board board, PracticeGame game) {
        if (game.getTargetEco() == null || game.getTargetEco().isBlank()) return null;

        int ply = plyCount(game);
        if (ply > 4) return null;

        boolean opponentIsWhite = !"white".equals(game.getPlayerColor());
        Map<String, String[]> book = opponentIsWhite ? BOOK_AS_WHITE : BOOK_AS_BLACK;

        String family = game.getTargetEco().length() >= 2
                ? game.getTargetEco().substring(0, 2).toUpperCase()
                : game.getTargetEco().toUpperCase();

        String[] line = book.get(family);
        if (line == null) {
            // Fall back to the letter alone, so C41 finds a C-family line.
            line = book.get(family.charAt(0) + "0");
        }
        if (line == null) return null;

        int index = opponentIsWhite ? ply / 2 : (ply - 1) / 2;
        if (index < 0 || index >= line.length) return null;

        String candidate = line[index];
        for (Move m : board.legalMoves()) {
            if (m.toString().equalsIgnoreCase(candidate)) return candidate;
        }
        return null;   // transposed out of book; the engine takes over
    }

    private static int plyCount(PracticeGame game) {
        String s = game.getSanMoves();
        if (s == null || s.isBlank()) return 0;
        return s.trim().split("\\s+").length;
    }
}
