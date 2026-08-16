package com.praxis.prax.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Turns a raw engine result into a set of verified chess facts.
 *
 * This is the piece that stops Prax inventing chess. Previously the model got a
 * FEN and an evaluation and filled the gap with plausible narrative — "attacks
 * the knight and prepares a strong central presence" — none of which the engine
 * had said. Everything here is computed from the board with chesslib, so a
 * statement either exists as a fact or Prax cannot make it.
 */
@Service
public class PositionEvidenceBuilder {

    private static final Logger log = LoggerFactory.getLogger(PositionEvidenceBuilder.class);

    public record PositionEvidence(
            String sideToMove,
            String bestMoveSan,
            String bestMoveUci,
            String evaluation,
            boolean mate,
            List<ChessFact> facts
    ) {}

    /**
     * Facts about the move the player actually chose, set against the engine's.
     *
     * Without this the model had facts about the BEST move only, while the
     * question is always "why was MINE bad" — so it filled the gap from its own
     * chess knowledge. Right or wrong, that is the unverified category this
     * whole layer exists to remove.
     */
    public List<ChessFact> comparePlayed(String fen, double bestScore, Move played,
                                         double playedScore, int startIdx) {
        List<ChessFact> out = new ArrayList<>();
        int n = startIdx;
        try {
            Board b = new Board();
            b.loadFromFen(fen);
            Side mover = b.getSideToMove();
            String san = toSan(fen, List.of(played));

            // Scores here are White's perspective. Flip into the mover's frame so
            // "+" always means good for whoever played, and a mate score keeps
            // its owner. Math.abs() lost that: it made "I have mate" and "I get
            // mated" the same value, so a move that walked into mate in 2 was
            // reported as costing nothing.
            double flip = mover == Side.WHITE ? 1.0 : -1.0;
            double bestForMover = bestScore * flip;
            double playedForMover = playedScore * flip;

            boolean bestWasMate = bestForMover >= 90.0;    // mover had a mate
            boolean playedIsMate = playedForMover >= 90.0;  // mover still has it
            boolean playedGetsMated = playedForMover <= -90.0;

            String moveName = san != null ? san : played.toString();

            // Both endpoints in ONE sentence. Stated as two adjacent facts, the
            // model read "Black is ahead by 7.03" and "White has a forced mate"
            // as simultaneous claims about one position, found them
            // irreconcilable, and concluded the engine was broken — "a severe
            // flaw in the position assessment". Nothing was wrong except that
            // the word "then" was missing. A before/after pair has no gap for
            // that reading to open in.
            String before = bestWasMate
                    ? (mover == Side.WHITE ? "White" : "Black") + " had a forced mate"
                    : standing(bestScore);

            if (!playedGetsMated) {
                String after = playedIsMate
                        ? (mover == Side.WHITE ? "White" : "Black") + " still has a forced mate"
                        : standing(playedScore);
                out.add(new ChessFact("f" + (++n), ChessFact.Kind.PLAYED_MOVE,
                        "Before " + moveName + ", " + before + ". After it, " + after + "."));
            }

            // Every branch must produce a cost fact, including "none". Leaving
            // the mate-to-mate and mate-found cases silent gave the model a
            // question with no answer in the facts — and it filled the silence
            // with "the move loses the material advantage" while the same
            // answer reported a forced mate still on the board.
            if (playedGetsMated) {
                // Checked first: nothing else that could be said about this move
                // matters beside it.
                out.add(new ChessFact("f" + (++n), ChessFact.Kind.WALKS_INTO_MATE,
                        "Before " + moveName + ", " + before + ". After it, "
                                + opponentOf(mover) + " has a forced mate — the move throws the "
                                + "game away in one step."));
            } else if (bestWasMate && !playedIsMate) {
                out.add(new ChessFact("f" + (++n), ChessFact.Kind.MISSED_MATE,
                        "Before " + moveName + ", " + before + ". After it, the mate is gone."));
            } else if (playedIsMate) {
                out.add(new ChessFact("f" + (++n), ChessFact.Kind.NO_LOSS,
                        "The engine sees no loss here: this move is at least as good as its own "
                                + "first choice. Whatever this move is recorded as, the engine "
                                + "does not consider it a mistake."));
            } else {
                double loss = bestForMover - playedForMover;
                if (loss > 0.05) {
                    out.add(new ChessFact("f" + (++n), ChessFact.Kind.EVAL_LOSS,
                            "Compared with the engine's choice, this move costs "
                                    + round(loss) + " pawns."));
                } else {
                    out.add(new ChessFact("f" + (++n), ChessFact.Kind.NO_LOSS,
                            "The engine sees no meaningful loss here: this move is within "
                                    + "0.05 pawns of its own first choice."));
                }
            }
        } catch (Exception e) {
            log.debug("[prax] could not compare played move {}: {}", played, e.getMessage());
        }
        return out;
    }

    /**
     * Turn whatever the model wrote into a legal move, or null.
     *
     * The tool schema asks for UCI and the model sends SAN — it thinks in the
     * notation it reads. `new Move("Qg5", side)` then throws, the comparison
     * facts vanish, and the model fills the gap with invented chess: precisely
     * the failure this class exists to prevent. So accept both, and resolve
     * against the legal move list rather than trusting the string.
     */
    public Move resolveMove(String fen, String token) {
        if (token == null || token.isBlank()) return null;
        try {
            Board b = new Board();
            b.loadFromFen(fen);
            List<Move> legal = b.legalMoves();

            String t = token.trim();
            for (Move m : legal) {
                if (m.toString().equalsIgnoreCase(t)) return m;
            }
            // SAN, ignoring check/annotation decoration ("Bb4+", "Qg5!?").
            String want = stripDecor(t);
            for (Move m : legal) {
                String san = toSan(fen, List.of(m));
                if (san != null && stripDecor(san).equals(want)) return m;
            }
            log.debug("[prax] '{}' is not a legal move in {}", token, fen);
        } catch (Exception e) {
            log.debug("[prax] could not resolve move '{}': {}", token, e.getMessage());
        }
        return null;
    }

    private static String stripDecor(String s) {
        return s.replaceAll("[+#!?]", "").trim();
    }

    public PositionEvidence build(String fen, double score, String bestUci, List<String> pvLines) {
        List<ChessFact> facts = new ArrayList<>();
        int n = 0;

        Board board = new Board();
        board.loadFromFen(fen);
        Side mover = board.getSideToMove();
        boolean mate = Math.abs(score) >= 90.0;

        String evalText = mate
                ? (score > 0 ? "forced mate for White" : "forced mate for Black")
                : standing(score);

        if (mate) {
            facts.add(new ChessFact("f" + (++n), ChessFact.Kind.MATE,
                    (score > 0 ? "White" : "Black") + " has a forced mate in this position."));
        } else {
            facts.add(new ChessFact("f" + (++n), ChessFact.Kind.EVAL_SWING,
                    "Before this move, " + standing(score) + "."));
        }

        String bestSan = null;
        if (bestUci != null && !bestUci.isBlank()) {
            try {
                Move best = new Move(bestUci, mover);
                bestSan = toSan(fen, List.of(best));  // bare token, e.g. "Qb6"

                facts.add(new ChessFact("f" + (++n), ChessFact.Kind.BEST_MOVE,
                        "The engine's preferred move is " + (bestSan != null ? bestSan : bestUci) + "."));

                List<ChessFact> mf = moveFacts(board, best, mover,
                        bestSan != null ? bestSan : bestUci, n);
                facts.addAll(mf);
                n += mf.size();
            } catch (Exception e) {
                log.debug("[prax] could not analyse best move {}: {}", bestUci, e.getMessage());
            }
        }

        // The main line, in notation a player can read.
        if (pvLines != null && !pvLines.isEmpty()) {
            String pvSan = pvToSan(fen, pvLines.get(0), 6);
            if (pvSan != null) {
                facts.add(new ChessFact("f" + (++n), ChessFact.Kind.PRINCIPAL_VARIATION,
                        "The engine's main line continues: " + pvSan + "."));
            }
            for (int i = 1; i < Math.min(pvLines.size(), 3); i++) {
                String altSan = pvToSan(fen, pvLines.get(i), 2);
                if (altSan != null) {
                    facts.add(new ChessFact("f" + (++n), ChessFact.Kind.ALTERNATIVE,
                            "A rival continuation the engine considered: " + altSan + "."));
                }
            }
        }

        return new PositionEvidence(mover.name().toLowerCase(), bestSan, bestUci, evalText, mate, facts);
    }

    /**
     * Facts about the best move itself, all derived from the board.
     *
     * Each names the move rather than saying "It". These are shown to the player
     * as separate lines, where a pronoun has lost its antecedent.
     */
    private List<ChessFact> moveFacts(Board original, Move move, Side mover,
                                      String moveName, int startIdx) {
        List<ChessFact> out = new ArrayList<>();
        int n = startIdx;

        Board b = new Board();
        b.loadFromFen(original.getFen());

        Piece moving = b.getPiece(move.getFrom());
        Piece captured = b.getPiece(move.getTo());
        Side them = mover == Side.WHITE ? Side.BLACK : Side.WHITE;

        if (captured != null && captured != Piece.NONE) {
            out.add(new ChessFact("f" + (++n), ChessFact.Kind.CAPTURE,
                    moveName + " captures the " + name(captured) + " on " + sq(move.getTo()) + "."));
        }

        // Was the moving piece under attack before it moved?
        if (b.squareAttackedBy(move.getFrom(), them) != 0L) {
            out.add(new ChessFact("f" + (++n), ChessFact.Kind.ESCAPES_ATTACK,
                    "The " + name(moving) + " on " + sq(move.getFrom())
                            + " was attacked before this move."));
        }

        try {
            b.doMove(move);
        } catch (Exception e) {
            return out;
        }

        if (b.isKingAttacked()) {
            out.add(new ChessFact("f" + (++n), ChessFact.Kind.CHECK, moveName + " gives check."));
        }

        // Is the arriving piece defended, or hanging? Name the pieces involved:
        // "the rook on b8 is defended" came back out of the model as "Rb8
        // defends the rook on b8", which a rook cannot do. Saying which piece
        // defends it leaves nothing to garble.
        List<String> defenders = piecesOn(b, b.squareAttackedBy(move.getTo(), mover));
        List<String> attackers = piecesOn(b, b.squareAttackedBy(move.getTo(), them));
        if (!attackers.isEmpty() && defenders.isEmpty()) {
            out.add(new ChessFact("f" + (++n), ChessFact.Kind.HANGS,
                    "The " + name(moving) + " on " + sq(move.getTo())
                            + " is undefended after " + moveName + ", and attacked by the "
                            + join(attackers) + "."));
        } else if (!defenders.isEmpty()) {
            out.add(new ChessFact("f" + (++n), ChessFact.Kind.DEFENDED,
                    "The " + name(moving) + " on " + sq(move.getTo())
                            + " is defended by the " + join(defenders) + " after " + moveName + "."));
        }

        // Which enemy pieces does the moved piece now attack? Computed by asking
        // the board, not asserted — this is the claim the model kept inventing.
        List<String> attacksList = new ArrayList<>();
        for (Square target : Square.values()) {
            if (target == Square.NONE) continue;
            Piece p = b.getPiece(target);
            if (p == null || p == Piece.NONE || p.getPieceSide() != them) continue;
            // The king is covered by CHECK; listing it here just restates that
            // in clumsier words ("attacks the king on e1").
            if (p.getPieceType() == PieceType.KING) continue;
            // Only counts if the piece that just moved is one of the attackers.
            if (attackedFrom(b, target, mover, move.getTo())) {
                attacksList.add(name(p) + " on " + sq(target));
            }
        }
        if (!attacksList.isEmpty()) {
            out.add(new ChessFact("f" + (++n), ChessFact.Kind.ATTACKS,
                    "After " + moveName + ", the " + name(moving) + " attacks the "
                            + join(attacksList) + "."));
        }
        return out;
    }

    /** Names the pieces standing on the squares of an attacker bitboard. */
    private List<String> piecesOn(Board b, long bitboard) {
        List<String> out = new ArrayList<>();
        for (Square s : Square.values()) {
            if (s == Square.NONE) continue;
            if ((bitboard & (1L << s.ordinal())) == 0L) continue;
            Piece p = b.getPiece(s);
            if (p == null || p == Piece.NONE) continue;
            out.add(name(p) + " on " + sq(s));
        }
        return out;
    }

    private static String join(List<String> parts) {
        return String.join(" and the ", parts);
    }

    /** True when `from` is among the squares attacking `target`. */
    private boolean attackedFrom(Board b, Square target, Side by, Square from) {
        long attackers = b.squareAttackedBy(target, by);
        return (attackers & (1L << from.ordinal())) != 0L;
    }

    private String pvToSan(String fen, String pvUci, int maxPlies) {
        if (pvUci == null || pvUci.isBlank()) return null;
        try {
            Board b = new Board();
            b.loadFromFen(fen);
            List<Move> moves = new ArrayList<>();
            for (String uci : pvUci.trim().split("\\s+")) {
                if (moves.size() >= maxPlies) break;
                Move m = new Move(uci, b.getSideToMove());
                moves.add(m);
                if (!b.doMove(m)) break;
            }
            return moves.isEmpty() ? null : numberedSan(fen, moves);
        } catch (Exception e) {
            return null;
        }
    }

    /** Bare SAN tokens, no numbering. */
    private String toSan(String fen, List<Move> moves) {
        try {
            MoveList list = new MoveList(fen);
            list.addAll(moves);
            return list.toSan().trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SAN with numbers taken from the FEN, not restarted at 1.
     * chesslib's toSanWithMoveNumbers() always begins at "1." and gives no hint
     * that Black is to move, so a line at move 8 rendered as "1. Qb6 Nf3" —
     * the same ply-versus-move confusion fixed in get_game.
     */
    private String numberedSan(String fen, List<Move> moves) {
        String bare = toSan(fen, moves);
        if (bare == null || bare.isBlank()) return null;

        String[] parts = fen.trim().split("\s+");
        boolean blackToMove = parts.length > 1 && "b".equalsIgnoreCase(parts[1]);
        int moveNo = 1;
        if (parts.length > 5) {
            try { moveNo = Integer.parseInt(parts[5]); } catch (NumberFormatException ignored) {}
        }

        StringBuilder sb = new StringBuilder();
        boolean black = blackToMove;
        for (String san : bare.split("\s+")) {
            if (san.isBlank()) continue;
            if (black) {
                if (sb.isEmpty()) sb.append(moveNo).append("...");
                sb.append(san).append(' ');
                moveNo++;
            } else {
                sb.append(moveNo).append('.').append(san).append(' ');
            }
            black = !black;
        }
        return sb.toString().trim();
    }

    private static String name(Piece p) {
        if (p == null || p == Piece.NONE) return "piece";
        return p.getPieceType().name().toLowerCase();
    }

    private static String sq(Square s) {
        return s.name().toLowerCase();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * "Black is ahead by 6.92 pawns" — never "-6.92 from White's perspective".
     *
     * Signed-perspective phrasing is correct and unreadable, and the model
     * inverted it: it read -6.77 as "Black was deeply behind" when Black was
     * winning by seven pawns. Naming the side leaves nothing to invert.
     *
     * @param whiteScore evaluation in pawns, White's perspective
     */
    private static String standing(double whiteScore) {
        double v = round(Math.abs(whiteScore));
        if (v < 0.15) return "the position is level";
        return (whiteScore > 0 ? "White is ahead by " : "Black is ahead by ") + v + " pawns";
    }

    /** The side that is NOT to move in this position. */
    private static String opponentOf(Side mover) {
        return mover == Side.WHITE ? "Black" : "White";
    }
}
