package com.praxis.service.analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PositionEvaluator {

    private final StockfishService stockfish;

    public PositionEvaluator(StockfishService stockfish) {
        this.stockfish = stockfish;
    }

    /**
     * @param moveTimeMs per-position search budget — see {@link AnalysisProfile}.
     *                   A practice game is one game and can afford more than a
     *                   hundred-game library sweep can.
     */
    public List<Double> evaluateAll(List<ParsedMove> moves, int moveTimeMs) {
        return evaluateAll(moves, moveTimeMs, AnalysisProgressSink.NOOP);
    }

    /** As above, reporting after each position — this is the long stage. */
    public List<Double> evaluateAll(List<ParsedMove> moves, int moveTimeMs, AnalysisProgressSink progress) {
        List<Double> scores = new ArrayList<>();
        int total = moves.size();
        progress.sweeping(0, total);
        for (ParsedMove move : moves) {
            if (move.evalScore() != null) {
                // Highest priority: Chess.com %eval annotation
                scores.add(move.evalScore());
            } else if (stockfish.isAvailable()) {
                // Second: Stockfish engine eval (accurate, catches all mistake types)
                Double sfScore = stockfish.evaluate(move.fenAfter(), moveTimeMs);
                scores.add(sfScore != null ? sfScore : material(move.fenAfter()));
            } else {
                // Fallback: material count (only catches immediate piece drops)
                scores.add(material(move.fenAfter()));
            }
            progress.sweeping(scores.size(), total);
        }
        return scores;
    }

    // Used as fallback when Stockfish is not available
    public double material(String fen) {
        Board board = new Board();
        board.loadFromFen(fen);
        return materialScore(board, Side.WHITE) - materialScore(board, Side.BLACK);
    }

    public double swing(List<Double> scores, int index, boolean playerIsWhite) {
        if (index == 0) return 0;
        double delta = scores.get(index) - scores.get(index - 1);
        return playerIsWhite ? delta : -delta;
    }

    private double materialScore(Board board, Side side) {
        double score = 0;
        score += Long.bitCount(board.getBitboard(side == Side.WHITE ? Piece.WHITE_QUEEN  : Piece.BLACK_QUEEN))  * 9;
        score += Long.bitCount(board.getBitboard(side == Side.WHITE ? Piece.WHITE_ROOK   : Piece.BLACK_ROOK))   * 5;
        score += Long.bitCount(board.getBitboard(side == Side.WHITE ? Piece.WHITE_BISHOP : Piece.BLACK_BISHOP)) * 3;
        score += Long.bitCount(board.getBitboard(side == Side.WHITE ? Piece.WHITE_KNIGHT : Piece.BLACK_KNIGHT)) * 3;
        score += Long.bitCount(board.getBitboard(side == Side.WHITE ? Piece.WHITE_PAWN   : Piece.BLACK_PAWN))   * 1;
        return score;
    }
}
