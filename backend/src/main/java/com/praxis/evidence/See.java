package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;

/**
 * Static Exchange Evaluation — what a capture sequence on one square is worth.
 *
 * <p>This is the primitive that turns "the knight is attacked" into "the knight
 * is <i>lost</i>". A piece attacked twice and defended twice is perfectly safe;
 * a piece attacked once and defended once is lost if the attacker is cheaper.
 * Counting attackers cannot tell those apart, and an explanation built on the
 * count is wrong in exactly the cases a player most wants explained.
 *
 * <p><b>Method.</b> The textbook swap-off: repeatedly capture on the target with
 * the least valuable attacker available, then fold the sequence back allowing
 * either side to stop when continuing would lose material. X-rays are handled by
 * recomputing attackers against a shrinking occupancy, so a rook behind a rook
 * joins the exchange when the piece in front of it is removed.
 *
 * <p><b>Two known limits</b>, both shared with the engine's own SEE and both
 * deliberate:
 * <ul>
 *   <li><b>Pins are ignored.</b> A defender pinned against its king is counted
 *       as a defender. Treating SEE as exact around pins would make it wrong in
 *       a different set of positions; the graph carries pins separately, as
 *       geometry ({@link TacticGeometry}), so the two facts stay distinct
 *       instead of being silently merged into one unreliable number.</li>
 *   <li><b>It is static.</b> It answers "if both sides only capture here, what
 *       happens", not "what does the engine play". Intermediate checks, zwischenzugs
 *       and promotions are outside it. The engine's PV walk covers those.</li>
 * </ul>
 *
 * <p>Values are in pawns. A king is given a value no exchange can profit from,
 * and cannot be the piece that loses a sequence: if a king would have to be the
 * next capturer while the other side still has an attacker, the sequence stops,
 * because that capture is illegal.
 */
public final class See {

    private See() {}

    /** Pawns. The king's entry is a sentinel — a king is never actually captured. */
    public static int value(PieceType type) {
        if (type == null) return 0;
        return switch (type) {
            case PAWN -> 1;
            case KNIGHT, BISHOP -> 3;
            case ROOK -> 5;
            case QUEEN -> 9;
            case KING -> 1_000;
            default -> 0;
        };
    }

    public static int value(Piece piece) {
        return piece == null || piece == Piece.NONE ? 0 : value(piece.getPieceType());
    }

    /**
     * Material the owner of {@code target} ends up with, in pawns, if the other
     * side starts an exchange there and both sides play the exchange out
     * sensibly. Zero when the square is safe; negative when the piece is
     * effectively lost.
     *
     * <p>This is the sign convention used in the evidence graph's Δ items, where
     * {@code SEE(c3) 0 → −3} reads "the knight on c3 was safe before the move and
     * is lost after it".
     *
     * @return {@code 0} for an empty square, or for a piece no enemy attacks
     */
    public static int onSquare(Board board, Square target) {
        Piece victim = board.getPiece(target);
        if (victim == null || victim == Piece.NONE) return 0;
        Side owner = victim.getPieceSide();
        int gain = exchange(board, target, owner.flip());
        // `exchange` is from the capturing side's view; flip it to the owner's,
        // and never report a "gain" for being captured.
        return Math.min(0, -gain);
    }

    /**
     * Material the given side gains, in pawns, by starting a capture sequence on
     * {@code target}. Zero when it has no attacker, or when starting would lose
     * material and it is better to leave the square alone.
     */
    public static int exchange(Board board, Square target, Side attacker) {
        Piece victim = board.getPiece(target);
        if (victim == null || victim == Piece.NONE) return 0;
        if (victim.getPieceSide() == attacker) return 0;
        return swap(board, target, attacker, board.getBitboard(), value(victim));
    }

    /**
     * Material the mover gains by playing {@code from → target} as a capture,
     * counting the recapture sequence that follows.
     *
     * <p>Unlike {@link #exchange}, the first capturer is fixed rather than chosen
     * as the cheapest — which is the question a specific candidate move asks.
     */
    public static int capture(Board board, Square from, Square target) {
        Piece moving = board.getPiece(from);
        Piece victim = board.getPiece(target);
        if (moving == null || moving == Piece.NONE) return 0;
        int captured = (victim == null || victim == Piece.NONE) ? 0 : value(victim);

        long occupancy = board.getBitboard() & ~bit(from);
        int theirBest = swap(board, target, moving.getPieceSide().flip(), occupancy, value(moving));
        return captured - Math.max(0, theirBest);
    }

    /**
     * The swap-off itself.
     *
     * @param onTarget value of the piece standing on {@code target} right now,
     *                 which is what the next capturer wins
     */
    private static int swap(Board board, Square target, Side sideToCapture,
                            long occupancy, int onTarget) {
        // gain[d] is the material balance after d captures, from the point of view
        // of the side that captured at ply d.
        Side side = sideToCapture;
        Square from = leastValuableAttacker(board, target, side, occupancy);
        // Nobody can capture, so nothing is on offer. Without this the fold below
        // returns the value of the piece standing on the square, and an
        // undefended pawn looks as if it costs its captor a rook.
        if (from == null) return 0;

        int[] gain = new int[32];
        int depth = 0;
        gain[0] = onTarget;

        while (from != null && depth < gain.length - 1) {
            Piece capturing = board.getPiece(from);
            depth++;
            gain[depth] = value(capturing) - gain[depth - 1];

            // The usual early exit — break when neither side could profit from
            // continuing — is deliberately absent. It is described as not
            // affecting the folded result, but it does when the first capture is
            // a cheap piece taking a dear one: a pawn taking a defended rook cuts
            // the sequence at ply 1 and the recapture never gets counted, so SEE
            // reports a whole rook instead of a rook for a pawn. An exchange is
            // at most a couple of dozen plies, so playing it out costs nothing
            // worth having.

            occupancy &= ~bit(from);
            side = side.flip();

            Square next = leastValuableAttacker(board, target, side, occupancy);
            // A king may only take when nothing can recapture. If the other side
            // still attacks the square, this capture is illegal and the sequence
            // ends here.
            if (capturing.getPieceType() == PieceType.KING && next != null) {
                depth--;
                break;
            }
            from = next;
        }

        // Fold back: at each step the side to move may stand pat instead of
        // recapturing, so it takes whichever is better for it.
        //
        // The fold starts at depth - 1, not depth. The last speculative entry is
        // the balance for a side that has already been given the option to stop
        // one ply below, so folding it in again double-counts the final capture —
        // which shows up as SEE reporting a won pawn in an exchange that is dead
        // level.
        while (--depth > 0) {
            gain[depth - 1] = -Math.max(-gain[depth - 1], gain[depth]);
        }
        return gain[0];
    }

    /** The cheapest piece of {@code side} attacking {@code target}, or null. */
    private static Square leastValuableAttacker(Board board, Square target, Side side, long occupancy) {
        // The occupancy mask matters twice: it hides pieces already spent in the
        // sequence from the sliding-attack calculation (so x-rays appear), and it
        // has to be applied to the result as well, because a spent knight is still
        // sitting on the board's own bitboard.
        long attackers = board.squareAttackedBy(target, side, occupancy) & occupancy;
        Square best = null;
        int bestValue = Integer.MAX_VALUE;
        while (attackers != 0L) {
            int index = Long.numberOfTrailingZeros(attackers);
            attackers &= attackers - 1;
            Square square = Square.squareAt(index);
            Piece piece = board.getPiece(square);
            if (piece == null || piece == Piece.NONE || piece.getPieceSide() != side) continue;
            int v = value(piece.getPieceType());
            if (v < bestValue) {
                bestValue = v;
                best = square;
            }
        }
        return best;
    }

    private static long bit(Square square) {
        return 1L << square.ordinal();
    }
}
