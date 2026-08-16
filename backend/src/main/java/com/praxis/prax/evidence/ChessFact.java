package com.praxis.prax.evidence;

/**
 * One chess claim that has been established by computation, not by a model.
 *
 * The model never authors chess facts. It receives this closed set, chooses
 * which matter, and phrases them — the same discipline the numeric evidence
 * citations already enforce. A claim with no fact behind it has no id to cite,
 * and Prax is instructed to say it cannot establish the reason instead.
 */
public record ChessFact(String id, Kind kind, String statement) {

    /**
     * How much this fact answers "why was the move bad", lowest first.
     *
     * The facts are built in board order — evaluation, best move, geometry, then
     * the played move last — which buries the decisive one at the bottom of the
     * list. A small model leads with what it reads first, so the cost of the
     * move is sorted to the top and the scenery to the bottom.
     */
    public int priority() {
        return switch (kind) {
            case WALKS_INTO_MATE -> 0;
            case MISSED_MATE -> 1;
            case EVAL_LOSS -> 2;
            case NO_LOSS -> 3;
            case PLAYED_MOVE -> 4;
            case BEST_MOVE -> 5;
            case MATE, EVAL_SWING -> 6;
            case CHECK, CAPTURE -> 7;
            case HANGS, ESCAPES_ATTACK -> 8;
            case DEFENDED, ATTACKS -> 9;
            case PRINCIPAL_VARIATION -> 10;
            case ALTERNATIVE -> 11;
        };
    }

    public enum Kind {
        /** The move is the engine's first choice. */
        BEST_MOVE,
        /** Evaluation before vs after, in pawns. */
        EVAL_SWING,
        /** Forced mate is present. */
        MATE,
        /** The move captures a piece. */
        CAPTURE,
        /** The move gives check. */
        CHECK,
        /** After the move, the moved piece attacks an enemy piece. */
        ATTACKS,
        /** The moved piece was itself attacked before moving. */
        ESCAPES_ATTACK,
        /** The destination square is defended by a friendly piece. */
        DEFENDED,
        /** The destination square is attacked by the opponent. */
        HANGS,
        /** The engine's main line, in SAN. */
        PRINCIPAL_VARIATION,
        /** A rival candidate and how much worse it is. */
        ALTERNATIVE,
        /** What the move actually played evaluates to. */
        PLAYED_MOVE,
        /** How much the played move cost against the engine's choice. */
        EVAL_LOSS,
        /** Mate was available and was not played. */
        MISSED_MATE,
        /** The engine sees no cost in the move played. */
        NO_LOSS,
        /** The move hands the opponent a forced mate. */
        WALKS_INTO_MATE
    }
}
