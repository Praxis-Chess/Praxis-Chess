package com.praxis.prax.artifact;

import java.util.List;

/**
 * The board, plus what the move actually cost.
 *
 * A CHESS_POSITION draws the two moves. This adds the arithmetic beside them:
 *
 *     Your move        g6
 *     Engine           Nb4
 *     Cost        8.48 pawns
 *
 * Emitted INSTEAD of a plain CHESS_POSITION when the played move is known, never
 * alongside it — two artifacts for one position would render the same board
 * twice.
 *
 * Every number here was computed by Stockfish and carried through
 * PositionEvidenceBuilder. The model is not asked for any of them, which is why
 * there is no "the model got the evaluation wrong" failure mode to guard.
 *
 * @param evalBefore evaluation of the position, in pawns, White-positive
 * @param evalAfter  evaluation after the played move, same convention
 * @param lossPawns  what the move cost against the engine's choice, or null when
 *                   the second search did not complete
 */
public record MoveComparisonArtifact(
        String id,
        String title,
        String fen,
        String orientation,
        List<String> highlights,
        List<ChessPositionArtifact.Arrow> arrows,
        String playedMove,
        String bestMove,
        Double evalBefore,
        Double evalAfter,
        Double lossPawns,
        String caption
) implements PraxArtifact {

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("type")
    public Type type() {
        return Type.MOVE_COMPARISON;
    }

    /**
     * The board half, so the validator and renderer can reuse one code path.
     *
     * NOT serialised: Jackson treats any public no-arg getter as a property, so
     * without this the JSON carries a whole second copy of the board nested
     * under "position" — every field duplicated, for a helper that exists only
     * for internal reuse.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public ChessPositionArtifact position() {
        return new ChessPositionArtifact(id, title, fen, orientation, highlights, arrows, caption);
    }
}
