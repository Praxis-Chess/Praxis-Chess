package com.praxis.dto;

import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import com.praxis.service.analysis.ParsedGame;
import com.praxis.service.analysis.ParsedMove;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A whole game in order, with the engine's findings attached to the moves they
 * belong to.
 *
 * {@code /api/analysis/{id}} returns only the flagged moves, which is the right
 * shape for a mistake list but cannot show a game — it has no notion of the moves
 * in between, and the caller cannot reconstruct them without the PGN. Rather than
 * ship the PGN and re-parse it in the browser, the move list is built here by the
 * same parser the analysis pipeline used, so the plies line up with the stored
 * MoveError rows by construction rather than by the client's parser agreeing with
 * ours.
 */
public record GameReviewDto(
        UUID gameId,
        String playerColor,
        String openingEco,
        String openingName,
        String result,
        Double accuracy,
        String analysisStatus,
        List<ReviewMove> moves
) {

    /**
     * One half-move.
     *
     * @param ply        1-indexed half-move — the same numbering MoveError uses
     * @param moveNumber full move number, for display ("12." )
     * @param byPlayer   false for the opponent's moves, which are shown for
     *                   continuity but never annotated: the engine only ever
     *                   examined the player's side
     * @param mistake    null unless this move was flagged. Absent means the move
     *                   cost less than the inaccuracy threshold — examined and
     *                   found unremarkable, NOT unexamined.
     */
    public record ReviewMove(
            int ply,
            int moveNumber,
            String san,
            String color,
            boolean byPlayer,
            String fenBefore,
            String fenAfter,
            MoveErrorDto mistake
    ) {}

    public static GameReviewDto from(Game game, ParsedGame parsed, List<MoveError> errors) {
        // MoveError.moveNumber and ParsedMove.moveNumber are both 1-indexed ply,
        // so they key against each other directly.
        Map<Integer, MoveError> byPly = errors.stream()
                .collect(Collectors.toMap(MoveError::getMoveNumber, Function.identity(),
                        // A ply cannot legitimately be flagged twice; if a repeat
                        // analysis ever left a duplicate, prefer the newer row over
                        // failing the whole request.
                        (a, b) -> b));

        boolean playerIsWhite = "white".equals(parsed.playerColor());

        List<ReviewMove> moves = parsed.moves().stream()
                .map(m -> toReviewMove(m, playerIsWhite, byPly.get(m.moveNumber())))
                .toList();

        return new GameReviewDto(
                game.getId(), parsed.playerColor(), parsed.openingEco(), parsed.openingName(),
                parsed.result(), game.getAccuracy(), game.getAnalysisStatus().name(), moves);
    }

    private static ReviewMove toReviewMove(ParsedMove m, boolean playerIsWhite, MoveError error) {
        boolean isWhiteMove = m.moveNumber() % 2 == 1;
        return new ReviewMove(
                m.moveNumber(),
                (m.moveNumber() + 1) / 2,
                m.san(),
                isWhiteMove ? "white" : "black",
                playerIsWhite == isWhiteMove,
                m.fenBefore(),
                m.fenAfter(),
                error == null ? null : MoveErrorDto.from(error));
    }
}
