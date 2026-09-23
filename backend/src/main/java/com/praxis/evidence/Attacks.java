package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;

import java.util.ArrayList;
import java.util.List;

/**
 * Board queries the rest of the evidence package is built from.
 *
 * <p>The library answers "who attacks this square". Most evidence questions run
 * the other way — "what does this piece attack", "what lies behind that piece on
 * the same line" — so those are assembled here once rather than re-derived, in
 * slightly different and slightly wrong ways, at each call site.
 */
public final class Attacks {

    private Attacks() {}

    /** Squares the piece standing on {@code origin} attacks right now. */
    public static List<Square> from(Board board, Square origin) {
        Piece piece = board.getPiece(origin);
        if (piece == null || piece == Piece.NONE) return List.of();
        Side side = piece.getPieceSide();
        long occupancy = board.getBitboard();
        long originBit = 1L << origin.ordinal();

        List<Square> out = new ArrayList<>();
        for (Square target : Square.values()) {
            if (target == Square.NONE || target == origin) continue;
            if ((board.squareAttackedBy(target, side, occupancy) & originBit) != 0L) {
                out.add(target);
            }
        }
        return out;
    }

    /** Enemy pieces the piece on {@code origin} attacks, kings excluded. */
    public static List<Square> enemyTargetsOf(Board board, Square origin) {
        Piece piece = board.getPiece(origin);
        if (piece == null || piece == Piece.NONE) return List.of();
        Side them = piece.getPieceSide().flip();
        List<Square> out = new ArrayList<>();
        for (Square target : from(board, origin)) {
            Piece occupant = board.getPiece(target);
            if (occupant == null || occupant == Piece.NONE) continue;
            if (occupant.getPieceSide() != them) continue;
            if (occupant.getPieceType() == PieceType.KING) continue;
            out.add(target);
        }
        return out;
    }

    /** Squares holding pieces of {@code side} that attack {@code target}. */
    public static List<Square> attackers(Board board, Square target, Side side) {
        return squares(board.squareAttackedBy(target, side, board.getBitboard()));
    }

    /** Squares of the pieces defending whatever stands on {@code target}. */
    public static List<Square> defenders(Board board, Square target) {
        Piece piece = board.getPiece(target);
        if (piece == null || piece == Piece.NONE) return List.of();
        List<Square> out = new ArrayList<>(attackers(board, target, piece.getPieceSide()));
        out.remove(target);   // a piece does not defend itself
        return out;
    }

    /** True when the two squares share a rank, file or diagonal. */
    public static boolean aligned(Square a, Square b) {
        return direction(a, b) != null;
    }

    /**
     * Squares continuing past {@code through}, on the line from {@code origin},
     * out to the edge of the board. Empty when the two are not on a line.
     *
     * <p>This is what "the piece behind it" means — the difference between a pin
     * and a skewer is only which of the two is worth more.
     */
    public static List<Square> beyond(Square origin, Square through) {
        int[] step = direction(origin, through);
        if (step == null) return List.of();
        List<Square> out = new ArrayList<>();
        int file = through.getFile().ordinal() + step[0];
        int rank = through.getRank().ordinal() + step[1];
        while (file >= 0 && file < 8 && rank >= 0 && rank < 8) {
            out.add(Square.squareAt(rank * 8 + file));
            file += step[0];
            rank += step[1];
        }
        return out;
    }

    /**
     * The first piece met continuing past {@code through}, or null if the line
     * runs to the edge empty or the squares are not aligned.
     */
    public static Square firstPieceBeyond(Board board, Square origin, Square through) {
        for (Square square : beyond(origin, through)) {
            Piece piece = board.getPiece(square);
            if (piece != null && piece != Piece.NONE) return square;
        }
        return null;
    }

    /** Unit file/rank step from a to b, or null when they are not on a line. */
    static int[] direction(Square a, Square b) {
        if (a == null || b == null || a == Square.NONE || b == Square.NONE || a == b) return null;
        int df = b.getFile().ordinal() - a.getFile().ordinal();
        int dr = b.getRank().ordinal() - a.getRank().ordinal();
        if (df != 0 && dr != 0 && Math.abs(df) != Math.abs(dr)) return null;
        return new int[]{Integer.signum(df), Integer.signum(dr)};
    }

    /** The squares set in a bitboard. */
    public static List<Square> squares(long bitboard) {
        List<Square> out = new ArrayList<>();
        while (bitboard != 0L) {
            int index = Long.numberOfTrailingZeros(bitboard);
            bitboard &= bitboard - 1;
            out.add(Square.squareAt(index));
        }
        return out;
    }

    /**
     * SAN for one move in a position.
     *
     * <p>The library exposes SAN only through a move list, not on the board, so
     * every caller that wants a readable move name has to build one. Doing it
     * here keeps three classes from each inventing a slightly different version.
     *
     * @return SAN, or the UCI form when the move cannot be rendered
     */
    public static String san(Board board, com.github.bhlangonijr.chesslib.move.Move move) {
        try {
            var list = new com.github.bhlangonijr.chesslib.move.MoveList(board.getFen());
            list.add(move);
            return list.toSan().trim();
        } catch (Exception e) {
            return move.toString();
        }
    }

    /** A board holding the position after {@code fen}, for hypothetical queries. */
    public static Board at(String fen) {
        Board board = new Board();
        board.loadFromFen(fen);
        return board;
    }
}
