package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders the evidence a model is allowed to cite, and nothing else.
 *
 * <p>This is the backend taking over prompt rendering, which §17.3 requires and
 * Phase 1 had to do in Python because there was no graph to render. The format
 * is fixed by the adapter trained in Phase 1: it learned to continue from these
 * exact field names, and a block that drifts from them is a silently worse
 * prompt rather than an error.
 *
 * <p><b>The field that is easy to get wrong.</b> {@code Reply} is the
 * opponent's refutation <i>after</i> the blunder — not the engine's preferred
 * move instead of it. Those are different positions. The analysis pipeline
 * stores the second and the model expects the first, and feeding one where the
 * other belongs produces confident, fluent, wrong sentences with nothing
 * anywhere reporting a problem.
 */
public final class EvidenceBlock {

    private EvidenceBlock() {}

    public static final String INSTRUCTION =
            "State in one sentence what the move allows. "
                    + "Name the squares. Use only the evidence given.";

    /**
     * @param fenBefore      position before the played move
     * @param playedSan      the move being explained
     * @param player         who played it
     * @param phase          OPENING / MIDDLEGAME / ENDGAME
     * @param consequence    the engine line and what it costs
     * @param tactics        geometry verified on the board, not a label
     */
    public static String render(String fenBefore,
                                String playedSan,
                                Side player,
                                String phase,
                                ConsequenceWalk.Consequence consequence,
                                List<TacticGeometry.Tactic> tactics) {
        List<String> lines = new ArrayList<>();
        lines.add("FEN: " + fenBefore);
        lines.add("Player: " + (player == Side.WHITE ? "White" : "Black"));
        lines.add("Played: " + playedSan);

        if (consequence.replySan() != null) {
            lines.add("Reply: " + consequence.replySan()
                    + (consequence.replyGivesCheck() ? " (check)" : ""));
        }
        if (!consequence.lineSan().isEmpty()) {
            lines.add("Line: " + String.join(" ", consequence.lineSan()));
        }

        List<String> attacked = attacked(fenBefore, playedSan, consequence, player);
        if (!attacked.isEmpty()) {
            lines.add("Attacked: " + String.join(", ", attacked));
        }

        // Material is stated only when the NET walk shows it. Listing the pieces a
        // player lost in what was really a trade is how a real game produced
        // "White loses 13 points" for a sequence that cost one pawn — and the
        // model repeated it faithfully, because it was in the evidence.
        String side = player == Side.WHITE ? "White" : "Black";
        if (consequence.costsSomething() && !consequence.losses().isEmpty()) {
            lines.add("Lost in the line: " + describe(consequence.losses()));
            // "Won", not "Won back": the first piece taken may be the played
            // move's own capture, which was won first rather than recovered.
            if (!consequence.gains().isEmpty()) {
                lines.add("Won: " + describe(consequence.gains()));
            }
        }

        if (consequence.mate()) {
            lines.add("Outcome: mate in " + consequence.mateIn());
        } else if (consequence.materialSwing() > 0) {
            int swing = consequence.materialSwing();
            lines.add("Outcome: " + side + " loses " + swing
                    + (swing == 1 ? " point" : " points") + " of material, net");
        } else {
            // Said outright rather than left blank: an absent Outcome invites the
            // model to supply one, and it will.
            lines.add("Outcome: no material lost within " + ConsequenceWalk.HORIZON + " plies");
        }
        if (consequence.costsSomething() && consequence.consequencePly() > 0) {
            lines.add("Consequence ply: " + consequence.consequencePly());
        }

        // Verified geometry only. Phase 1's blocks carried a Lichess theme tag
        // here, labelled "Tagged" precisely because it was unverified; these are
        // computed from the board, so they are stated as findings.
        if (!tactics.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (var tactic : tactics) {
                String name = tactic.kind().name().toLowerCase(Locale.ROOT).replace('_', ' ');
                if (!names.contains(name)) names.add(name);
            }
            lines.add("Tagged: " + String.join(", ", names));
        }

        lines.add("Phase: " + phase.toLowerCase(Locale.ROOT));
        return String.join("\n", lines) + "\n\n" + INSTRUCTION;
    }

    /**
     * The blunderer's pieces the refuting move bears on, with whether each is
     * defended — the same shape the adapter was trained on.
     */
    private static List<String> attacked(String fenBefore, String playedSan,
                                         ConsequenceWalk.Consequence consequence, Side player) {
        if (consequence.replySan() == null || consequence.lineSan().isEmpty()) return List.of();

        Board board = Attacks.at(fenBefore);
        try {
            board.doMove(playedSan);
        } catch (Exception e) {
            return List.of();
        }

        // The square the refuting piece lands on, taken from the move itself.
        Square to = null;
        for (var move : board.legalMoves()) {
            try {
                if (consequence.replySan().equals(Attacks.san(board, move))) {
                    to = move.getTo();
                    break;
                }
            } catch (Exception ignored) {
                // A move whose SAN cannot be produced cannot be the one named.
            }
        }
        if (to == null) return List.of();

        try {
            board.doMove(consequence.replySan());
        } catch (Exception e) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        for (Square target : Attacks.enemyTargetsOf(board, to)) {
            Piece piece = board.getPiece(target);
            boolean defended = !Attacks.defenders(board, target).isEmpty();
            out.add(pieceName(piece) + " on " + square(target)
                    + (defended ? " (defended)" : " (undefended)"));
        }
        return out;
    }

    private static String describe(List<ConsequenceWalk.Loss> taken) {
        List<String> out = new ArrayList<>();
        for (var loss : taken) {
            out.add(pieceName(loss.piece()) + " on " + square(loss.square()) + " (" + loss.san() + ")");
        }
        return String.join(", ", out);
    }

    private static String pieceName(Piece piece) {
        return piece.getPieceType().name().toLowerCase(Locale.ROOT);
    }

    private static String square(Square square) {
        return square.name().toLowerCase(Locale.ROOT);
    }
}
