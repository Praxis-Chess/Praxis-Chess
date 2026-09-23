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
 * Names the shape of a tactic, from the board rather than from a label.
 *
 * <p>The baseline measured a model calling {@code Bg5} "a fork on f6". It is not
 * a fork, it never could be, and nothing in the system could tell. This class is
 * what makes that claim checkable: a motif is reported only when the geometry is
 * actually there, and a motif that is not reported cannot be cited.
 *
 * <p>The distinctions are deliberately mechanical, because they have to be
 * decidable:
 * <ul>
 *   <li><b>Fork</b> — one piece attacking two things worth taking at once.</li>
 *   <li><b>Pin</b> — a slider hits a piece with something <i>dearer</i> behind it
 *       on the same line, so the front piece cannot step aside.</li>
 *   <li><b>Skewer</b> — the same line, the other way round: the <i>dearer</i>
 *       piece is in front, and moving it loses what stands behind.</li>
 *   <li><b>Discovered attack</b> — a line opened by the move for a piece that
 *       did not move.</li>
 * </ul>
 *
 * <p>Pin and skewer are the same geometry with the values swapped, which is
 * exactly why a model guessing between them gets it wrong half the time.
 */
public final class TacticGeometry {

    private TacticGeometry() {}

    public enum Kind { FORK, PIN, SKEWER, DISCOVERED_ATTACK }

    /**
     * @param kind     what the geometry is
     * @param by       the square the attacking piece stands on afterwards
     * @param targets  what it bears on — for a pin or skewer, the front piece
     *                 first and the piece behind it second
     */
    public record Tactic(Kind kind, Square by, Piece piece, List<Square> targets) {

        public String describe() {
            return switch (kind) {
                case FORK -> "fork from " + by + " on " + targets;
                case PIN -> targets.get(0) + " is pinned to " + targets.get(1) + " by " + by;
                case SKEWER -> targets.get(0) + " is skewered to " + targets.get(1) + " by " + by;
                case DISCOVERED_ATTACK -> "discovered attack by " + by + " on " + targets;
            };
        }
    }

    /** Everything the position after {@code move} contains, in a stable order. */
    public static List<Tactic> after(Board before, Move move) {
        Board after = Attacks.at(before.getFen());
        Piece moved = after.getPiece(move.getFrom());
        if (moved == null || moved == Piece.NONE) return List.of();
        if (!after.doMove(move)) return List.of();
        Side mover = moved.getPieceSide();

        List<Tactic> out = new ArrayList<>();
        fork(after, move.getTo(), mover).ifPresent(out::add);

        // Only the pins and skewers this move CREATED. A pin that was already
        // standing is a fact about the position, not about the move — and on the
        // first real game this ran against, a knight reply was reported as
        // "pin" because of a line that had existed for several moves. Forks and
        // discovered attacks need no such filter: the first is by the piece that
        // just landed, the second is computed as a difference already.
        Set<List<Square>> existing = new LinkedHashSet<>();
        for (Tactic t : pinsAndSkewers(before, mover)) existing.add(key(t));
        for (Tactic t : pinsAndSkewers(after, mover)) {
            if (!existing.contains(key(t))) out.add(t);
        }

        out.addAll(discovered(before, after, move, mover));
        return out;
    }

    /** Pins and skewers standing in a position, whoever created them. */
    public static List<Tactic> pinsAndSkewers(Board board, Side mover) {
        List<Tactic> out = new ArrayList<>();
        for (Square from : Square.values()) {
            if (from == Square.NONE) continue;
            Piece piece = board.getPiece(from);
            if (piece == null || piece == Piece.NONE) continue;
            if (piece.getPieceSide() != mover || !isSlider(piece.getPieceType())) continue;

            for (Square target : Attacks.from(board, from)) {
                Piece front = board.getPiece(target);
                if (front == null || front == Piece.NONE) continue;
                if (front.getPieceSide() == mover) continue;

                Square behindSquare = Attacks.firstPieceBeyond(board, from, target);
                if (behindSquare == null) continue;
                Piece behind = board.getPiece(behindSquare);
                if (behind.getPieceSide() == mover) continue;

                int frontValue = See.value(front);
                int behindValue = See.value(behind);
                // A king behind is the strictest pin there is, and its sentinel
                // value already dominates every comparison.
                if (behindValue > frontValue) {
                    out.add(new Tactic(Kind.PIN, from, piece, List.of(target, behindSquare)));
                } else if (behindValue < frontValue) {
                    out.add(new Tactic(Kind.SKEWER, from, piece, List.of(target, behindSquare)));
                }
                // Equal values are a line attack with no name worth giving: the
                // defender loses nothing by trading one for the other.
            }
        }
        return out;
    }

    /**
     * A fork by the piece that just landed on {@code at}.
     *
     * <p>Two attacked pieces are not a fork if both are defended and both are
     * dearer than nothing to take — what makes a fork is that at least two of
     * the targets are actually <i>winnable</i>. A check counts as a target,
     * because the king must answer it and the other piece falls next move.
     */
    public static java.util.Optional<Tactic> fork(Board after, Square at, Side mover) {
        Piece piece = after.getPiece(at);
        if (piece == null || piece == Piece.NONE) return java.util.Optional.empty();

        int attackerValue = See.value(piece);
        List<Square> winnable = new ArrayList<>();
        boolean givesCheck = false;

        for (Square target : Attacks.from(after, at)) {
            Piece occupant = after.getPiece(target);
            if (occupant == null || occupant == Piece.NONE) continue;
            if (occupant.getPieceSide() == mover) continue;

            if (occupant.getPieceType() == PieceType.KING) {
                givesCheck = true;
                winnable.add(target);
                continue;
            }
            // Worth taking: either it is dearer than the piece attacking it, or
            // the exchange on that square comes out in the attacker's favour.
            if (See.value(occupant) > attackerValue || See.onSquare(after, target) < 0) {
                winnable.add(target);
            }
        }

        if (winnable.size() < 2) return java.util.Optional.empty();
        // A "fork" that only forks because of check on one side still needs a
        // second real target, which the size check above already guarantees.
        if (givesCheck && winnable.size() < 2) return java.util.Optional.empty();
        return java.util.Optional.of(new Tactic(Kind.FORK, at, piece, List.copyOf(winnable)));
    }

    /**
     * Attacks gained by pieces that did not move — the line the move uncovered.
     *
     * <p>Attribution is by difference rather than by geometry: exactly one move
     * separates the two positions, so any enemy piece a stationary slider attacks
     * now and did not before was uncovered by it.
     */
    private static List<Tactic> discovered(Board before, Board after, Move move, Side mover) {
        List<Tactic> out = new ArrayList<>();
        for (Square from : Square.values()) {
            if (from == Square.NONE || from == move.getFrom() || from == move.getTo()) continue;
            Piece piece = after.getPiece(from);
            if (piece == null || piece == Piece.NONE) continue;
            if (piece.getPieceSide() != mover || !isSlider(piece.getPieceType())) continue;
            // Only a piece that stood here before can have been uncovered.
            if (before.getPiece(from) != piece) continue;

            Set<Square> was = new LinkedHashSet<>(Attacks.from(before, from));
            List<Square> gained = new ArrayList<>();
            for (Square target : Attacks.from(after, from)) {
                if (was.contains(target)) continue;
                Piece occupant = after.getPiece(target);
                if (occupant == null || occupant == Piece.NONE) continue;
                if (occupant.getPieceSide() == mover) continue;
                gained.add(target);
            }
            if (!gained.isEmpty()) {
                out.add(new Tactic(Kind.DISCOVERED_ATTACK, from, piece, List.copyOf(gained)));
            }
        }
        return out;
    }

    /** Identity of a line motif: the attacker's square and both pieces on the line. */
    private static List<Square> key(Tactic t) {
        List<Square> k = new ArrayList<>();
        k.add(t.by());
        k.addAll(t.targets());
        return k;
    }

    private static boolean isSlider(PieceType type) {
        return type == PieceType.BISHOP || type == PieceType.ROOK || type == PieceType.QUEEN;
    }
}
