package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the engine's line to the point where the damage actually lands.
 *
 * <p>"You lose a knight" and "you lose a knight four moves from now" are
 * different facts, and the second one is the one a club player can act on. The
 * <b>consequence ply</b> is where material changes hands or mate arrives; until
 * then the line is just moves.
 *
 * <p>No search happens here. The principal variation comes from the engine; this
 * replays it on a board and records what it costs, which makes every number
 * below checkable against the position rather than asserted.
 *
 * <p><b>Two rules, both learned from real games rather than puzzles.</b> A puzzle
 * line is one-sided — the solver takes and nothing comes back — so the first
 * version counted only what the blunderer lost. A real engine line trades: on
 * the first game it was run against, it reported "White loses 13 points" for a
 * sequence where White lost a queen, a knight and a pawn and took back a queen
 * and a bishop, net one pawn. So:
 * <ul>
 *   <li>the swing is <b>net</b> — what the blunderer lost minus what they won
 *       back — and</li>
 *   <li>the walk stops at the plan's <b>horizon of {@value #HORIZON} plies</b>
 *       (§6.3), running on only while an exchange is still unfinished. A depth-14
 *       PV is sixteen plies of best guesses, and a trade at ply thirteen is not a
 *       consequence of the move being explained.</li>
 * </ul>
 */
public final class ConsequenceWalk {

    private ConsequenceWalk() {}

    /** Plies walked before the line stops counting as a consequence (§6.3). */
    public static final int HORIZON = 8;

    /** Hard stop when an exchange is still running at the horizon. */
    private static final int EXCHANGE_CAP = 12;

    /** A piece that changed hands, and which ply it went on. */
    public record Loss(Square square, Piece piece, int value, String san, int ply) {}

    /**
     * @param lineSan         the line in SAN, as far as it was walked
     * @param losses          the blunderer's material taken, dearest first
     * @param gains           the opponent's material the blunderer took back
     * @param mate            true when the line ends in checkmate
     * @param mateIn          moves to mate, when it does
     * @param materialSwing   net pawns the blunderer is down at the end of the
     *                        walk: lost minus won back. Zero or negative means
     *                        the move cost no material within the horizon.
     * @param consequencePly  ply at which the blunderer first loses material,
     *                        or mate lands; {@code 0} when neither happens
     */
    public record Consequence(
            String replySan,
            boolean replyGivesCheck,
            List<String> lineSan,
            List<Loss> losses,
            List<Loss> gains,
            boolean mate,
            Integer mateIn,
            int materialSwing,
            int consequencePly
    ) {
        public boolean isEmpty() {
            return lineSan.isEmpty();
        }

        /** True when the walk shows the move costing something concrete. */
        public boolean costsSomething() {
            return mate || materialSwing > 0;
        }
    }

    /**
     * @param afterBlunder the position with the played move already on the board
     * @param pvUci        the engine's line from that position
     * @param blunderer    the side that played the move being explained
     */
    public static Consequence of(Board afterBlunder, List<String> pvUci, Side blunderer) {
        return of(afterBlunder, pvUci, blunderer, null, null, null);
    }

    /**
     * As above, counting what the played move itself captured.
     *
     * <p>The walk starts after the move, so a move that captures — {@code Bxf3},
     * then {@code Nxf3} back — looked like a bishop lost for nothing. On a real
     * game that turned an even trade into "Black loses 3 points", and the model
     * said so. What the move took is material won, at ply 0.
     */
    public static Consequence of(Board afterBlunder, List<String> pvUci, Side blunderer,
                                 Piece capturedByMove, Square capturedOn, String playedSan) {
        if (pvUci == null || pvUci.isEmpty()) {
            return new Consequence(null, false, List.of(), List.of(), List.of(), false, null, 0, 0);
        }

        Board board = Attacks.at(afterBlunder.getFen());

        List<String> lineSan = new ArrayList<>();
        List<Loss> losses = new ArrayList<>();
        List<Loss> gains = new ArrayList<>();
        if (capturedByMove != null && capturedByMove != Piece.NONE && capturedOn != null) {
            gains.add(new Loss(capturedOn, capturedByMove, See.value(capturedByMove), playedSan, 0));
        }
        String replySan = null;
        boolean replyCheck = false;
        int consequencePly = 0;
        int ply = 0;
        boolean lastWasCapture = false;

        for (String uci : pvUci) {
            // Past the horizon, keep going only to finish an exchange already
            // under way — stopping between a capture and its recapture would
            // report half a trade as a loss.
            if (ply >= HORIZON && (!lastWasCapture || ply >= EXCHANGE_CAP)) break;

            Move move = toMove(board, uci);
            if (move == null) break;

            Piece victim = board.getPiece(move.getTo());
            boolean capture = victim != null && victim != Piece.NONE;
            String san = Attacks.san(board, move);
            if (!board.doMove(move)) break;

            ply++;
            lineSan.add(san);
            lastWasCapture = capture;
            if (ply == 1) {
                replySan = san;
                replyCheck = board.isKingAttacked();
            }
            if (capture) {
                Loss taken = new Loss(move.getTo(), victim, See.value(victim), san, ply);
                if (victim.getPieceSide() == blunderer) {
                    losses.add(taken);
                    if (consequencePly == 0) consequencePly = ply;
                } else {
                    gains.add(taken);
                }
            }
            if (board.isMated()) {
                if (consequencePly == 0) consequencePly = ply;
                break;
            }
        }

        boolean mate = board.isMated();
        Integer mateIn = mate ? (lineSan.size() + 1) / 2 : null;
        int lost = losses.stream().mapToInt(Loss::value).sum();
        int wonBack = gains.stream().mapToInt(Loss::value).sum();
        losses.sort((a, b) -> Integer.compare(b.value(), a.value()));
        gains.sort((a, b) -> Integer.compare(b.value(), a.value()));

        return new Consequence(replySan, replyCheck, List.copyOf(lineSan),
                List.copyOf(losses), List.copyOf(gains),
                mate, mateIn, lost - wonBack, consequencePly);
    }

    /**
     * UCI to a legal move on this board.
     *
     * <p>A promotion arrives as five characters ({@code e7e8q}) and the library's
     * own parser needs the promoting side to build the right piece, so the move
     * is matched against the legal list instead of constructed.
     */
    private static Move toMove(Board board, String uci) {
        if (uci == null || uci.length() < 4) return null;
        for (Move legal : board.legalMoves()) {
            if (legal.toString().equalsIgnoreCase(uci)) return legal;
        }
        return null;
    }
}
