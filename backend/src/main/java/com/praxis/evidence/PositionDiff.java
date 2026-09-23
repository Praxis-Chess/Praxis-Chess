package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one move changed — the Δ items of the evidence graph.
 *
 * <p>This is the primitive behind the most common thing a club player needs told:
 * not "your move was bad" but "your move was the only thing defending that
 * knight". The shape of that sentence is a <i>difference</i> between two
 * positions, and nothing in the pipeline could express one before this.
 *
 * <p>Everything here is attributed by comparison rather than by reasoning about
 * the move. Exactly one move separates the two positions, so any change in the
 * board's attack relations was caused by it, and the attribution cannot be wrong
 * in the way a rule about "the moved piece" can be — a capture that removes a
 * blocker changes lines nowhere near either of the move's squares.
 */
public final class PositionDiff {

    private PositionDiff() {}

    /**
     * A piece whose safety changed. Squares are its own square before and after,
     * which differ only for the piece that moved.
     *
     * @param seeBefore static exchange value on its square before the move
     * @param seeAfter  the same afterwards; negative means it is now losing material
     */
    public record PieceChange(
            Square square,
            Piece piece,
            int seeBefore,
            int seeAfter,
            List<Square> defendersBefore,
            List<Square> defendersAfter,
            List<Square> attackersAfter
    ) {
        /** Safe before, losing material now — the classic "you left it hanging". */
        public boolean nowLoose() {
            return seeAfter < 0 && seeBefore >= 0;
        }

        public List<Square> defendersLost() {
            List<Square> out = new ArrayList<>(defendersBefore);
            out.removeAll(defendersAfter);
            return out;
        }

        public List<Square> defendersGained() {
            List<Square> out = new ArrayList<>(defendersAfter);
            out.removeAll(defendersBefore);
            return out;
        }
    }

    /**
     * A line the move opened or closed.
     *
     * @param opened true when {@code slider} attacks {@code target} now and did
     *               not before; false for the reverse
     */
    public record LineChange(Square slider, Piece piece, Square target, boolean opened) {}

    /**
     * @param pieces     the mover's pieces whose defenders or exchange value changed
     * @param lines      lines opened and blocked, for either side
     * @param newAttacks enemy pieces the moved piece attacks now and did not before
     */
    public record Delta(
            Square from,
            Square to,
            Piece moved,
            Piece captured,
            boolean givesCheck,
            List<PieceChange> pieces,
            List<LineChange> lines,
            List<Square> newAttacks
    ) {
        /** The mover's pieces that the move left losing material. */
        public List<PieceChange> nowLoose() {
            return pieces.stream().filter(PieceChange::nowLoose).toList();
        }
    }

    public static Delta of(Board before, Move move) {
        Piece moved = before.getPiece(move.getFrom());
        if (moved == null || moved == Piece.NONE) {
            throw new IllegalArgumentException("no piece on " + move.getFrom() + " in " + before.getFen());
        }
        Piece captured = before.getPiece(move.getTo());
        Side mover = moved.getPieceSide();

        Board after = Attacks.at(before.getFen());
        if (!after.doMove(move)) {
            throw new IllegalArgumentException("illegal move " + move + " in " + before.getFen());
        }

        List<PieceChange> pieces = new ArrayList<>();

        // The piece that moved: compare its old square with its new one. Tracking
        // it by square would report it as "lost" and something newly appearing,
        // which is the opposite of the fact wanted.
        pieces.add(new PieceChange(
                move.getTo(), moved,
                See.onSquare(before, move.getFrom()),
                See.onSquare(after, move.getTo()),
                Attacks.defenders(before, move.getFrom()),
                Attacks.defenders(after, move.getTo()),
                Attacks.attackers(after, move.getTo(), mover.flip())));

        // Everything else that stayed put.
        for (Square square : Square.values()) {
            if (square == Square.NONE || square == move.getFrom() || square == move.getTo()) continue;
            Piece piece = before.getPiece(square);
            if (piece == null || piece == Piece.NONE || piece.getPieceSide() != mover) continue;
            if (after.getPiece(square) != piece) continue;
            if (piece.getPieceType() == PieceType.KING) continue;

            int seeBefore = See.onSquare(before, square);
            int seeAfter = See.onSquare(after, square);
            List<Square> defendersBefore = Attacks.defenders(before, square);
            List<Square> defendersAfter = Attacks.defenders(after, square);
            if (seeBefore == seeAfter && defendersBefore.equals(defendersAfter)) continue;

            pieces.add(new PieceChange(square, piece, seeBefore, seeAfter,
                    defendersBefore, defendersAfter,
                    Attacks.attackers(after, square, mover.flip())));
        }

        return new Delta(move.getFrom(), move.getTo(), moved, captured,
                after.isKingAttacked(),
                List.copyOf(pieces),
                lines(before, after, move),
                newAttacks(before, after, move));
    }

    /**
     * Lines opened and blocked, by comparing each stationary slider's reach.
     *
     * <p>Only sliders can have a line opened or blocked, and only pieces that did
     * not move can have it done <i>to</i> them — the moved piece's own reach
     * changed because it is somewhere else, which is a different fact.
     */
    private static List<LineChange> lines(Board before, Board after, Move move) {
        List<LineChange> out = new ArrayList<>();
        for (Square square : Square.values()) {
            if (square == Square.NONE || square == move.getFrom() || square == move.getTo()) continue;
            Piece piece = before.getPiece(square);
            if (piece == null || piece == Piece.NONE) continue;
            if (after.getPiece(square) != piece) continue;   // captured, or displaced by castling
            PieceType type = piece.getPieceType();
            if (type != PieceType.BISHOP && type != PieceType.ROOK && type != PieceType.QUEEN) continue;

            Set<Square> was = new LinkedHashSet<>(Attacks.from(before, square));
            Set<Square> now = new LinkedHashSet<>(Attacks.from(after, square));

            for (Square target : now) {
                if (!was.contains(target) && occupied(after, target)) {
                    out.add(new LineChange(square, piece, target, true));
                }
            }
            for (Square target : was) {
                if (!now.contains(target) && occupied(before, target)) {
                    out.add(new LineChange(square, piece, target, false));
                }
            }
        }
        return out;
    }

    /** Enemy pieces the moved piece attacks now and did not attack before. */
    private static List<Square> newAttacks(Board before, Board after, Move move) {
        Set<Square> was = new LinkedHashSet<>(Attacks.enemyTargetsOf(before, move.getFrom()));
        List<Square> out = new ArrayList<>();
        for (Square target : Attacks.enemyTargetsOf(after, move.getTo())) {
            if (!was.contains(target)) out.add(target);
        }
        return out;
    }

    private static boolean occupied(Board board, Square square) {
        Piece piece = board.getPiece(square);
        return piece != null && piece != Piece.NONE;
    }
}
