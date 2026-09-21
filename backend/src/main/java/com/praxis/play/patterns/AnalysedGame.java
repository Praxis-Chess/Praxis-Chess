package com.praxis.play.patterns;

import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import com.praxis.play.domain.PracticeGame;
import com.praxis.service.analysis.ParsedGame;
import com.praxis.service.analysis.ParsedMove;

import java.util.List;
import java.util.UUID;

/**
 * One practice game with everything the detectors need, assembled once.
 *
 * The PGN is parsed and the mistakes fetched here rather than inside each
 * detector, so seven detectors over a twenty-game window cost one parse per
 * game instead of seven.
 */
public record AnalysedGame(
        PracticeGame practice,
        Game game,
        ParsedGame parsed,
        List<MoveError> errors
) {

    public UUID gameId() {
        return game.getId();
    }

    public boolean playerIsWhite() {
        return "white".equals(parsed.playerColor());
    }

    /** ParsedMove.moveNumber is 1-indexed ply; odd plies are White's. */
    public boolean isPlayerPly(int ply) {
        return playerIsWhite() == (ply % 2 == 1);
    }

    /** The player's own half-moves, in order. */
    public List<ParsedMove> playerMoves() {
        return parsed.moves().stream()
                .filter(m -> isPlayerPly(m.moveNumber()))
                .toList();
    }

    public int playerMoveCount() {
        return playerMoves().size();
    }

    /** Total half-moves. Used to skip games too short to have an opening phase. */
    public int plyCount() {
        return parsed.moves().size();
    }

    /**
     * Position after the given ply, or the last position if the game ended first.
     * Empty when there are no moves at all.
     */
    public String fenAfterPly(int ply) {
        List<ParsedMove> moves = parsed.moves();
        if (moves.isEmpty()) return null;
        int index = Math.min(ply, moves.size()) - 1;
        if (index < 0) return null;
        return moves.get(index).fenAfter();
    }

    /** Human-facing date for evidence lines. */
    public String playedOn() {
        return game.getPlayedAt() == null ? "" : game.getPlayedAt().toLocalDate().toString();
    }
}
