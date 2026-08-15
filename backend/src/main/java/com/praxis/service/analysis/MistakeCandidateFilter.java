package com.praxis.service.analysis;

import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * First-pass candidate selection from position-evaluation scores.
 *
 * Changes vs old version:
 * - Removed MAX_CANDIDATES cap (all mistakes pass through; orchestrator limits Ollama calls separately)
 * - Removed BOOK_MOVES_PLY analysis skip — even opening mistakes are worth storing
 * - Phase detection uses material count from FEN for endgame; ply threshold for opening
 * - Severity uses Chess.com win-percentage formula, matching accuracy computation
 * - Mate sentinels (±99.xx pawns) are handled correctly by the win% math
 */
@Component
public class MistakeCandidateFilter {

    // Win-% severity thresholds (Chess.com-style)
    private static final double BLUNDER_WIN_PCT_DROP    = 20.0;
    private static final double MISTAKE_WIN_PCT_DROP    = 10.0;
    private static final double INACCURACY_WIN_PCT_DROP  = 5.0;

    // Phase boundaries (in ply, 1-indexed half-moves)
    private static final int OPENING_PLY_MAX    = 24;   // ≤ 24 ply = first 12 full moves
    // Endgame: piece count ≤ ENDGAME_PIECE_THRESHOLD (kings excluded)
    private static final int ENDGAME_PIECE_THRESHOLD = 10;

    private static final int TIME_PRESSURE_CUTOFF = 30; // seconds remaining
    private static final int MIN_PLY = 2;               // skip ply 1 (no before-eval to compare)

    /**
     * Fast first pass: score-based candidate selection, no Stockfish calls.
     * Callers are responsible for enriching candidates with MultiPV bestmove + engine lines.
     */
    public List<CandidateMove> filterCandidates(ParsedGame game, List<Double> scores) {
        boolean playerIsWhite = "white".equals(game.playerColor());
        List<CandidateMove> candidates = new ArrayList<>();

        for (int i = 0; i < game.moves().size(); i++) {
            ParsedMove move = game.moves().get(i);

            if (move.moveNumber() < MIN_PLY) continue;

            boolean isWhiteMove = (move.moveNumber() % 2 == 1);
            if (playerIsWhite != isWhiteMove) continue;

            int afterIdx  = Math.min(i, scores.size() - 1);
            int beforeIdx = Math.max(0, i - 1);
            double evalAfterRaw  = scores.get(afterIdx);
            double evalBeforeRaw = scores.get(beforeIdx);

            // Win% before and after the move (always White's win% perspective first)
            double wBefore = winPct(evalBeforeRaw);
            double wAfter  = winPct(evalAfterRaw);

            // From the player's perspective: positive = improvement, negative = drop
            double winPctDrop = playerIsWhite
                    ? (wBefore - wAfter)          // white wants high win%
                    : ((100 - wBefore) - (100 - wAfter)); // black wants low white win%

            if (winPctDrop < INACCURACY_WIN_PCT_DROP) continue;

            Severity severity = winPctDrop >= BLUNDER_WIN_PCT_DROP  ? Severity.BLUNDER
                    : winPctDrop >= MISTAKE_WIN_PCT_DROP  ? Severity.MISTAKE
                    : Severity.INACCURACY;

            boolean timePressure = move.clockRemainingSeconds() != null
                    && move.clockRemainingSeconds() <= TIME_PRESSURE_CUTOFF;

            GamePhase phase = detectPhase(move.moveNumber(), move.fenBefore());

            candidates.add(new CandidateMove(
                    move, evalBeforeRaw - evalAfterRaw, phase, timePressure,
                    severity, null, evalBeforeRaw, evalAfterRaw, List.of()));
        }

        candidates.sort(Comparator.comparingDouble(CandidateMove::materialSwing));
        return candidates; // no cap — orchestrator decides how many go to Ollama
    }

    /**
     * Chess.com win-percentage from centipawn evaluation (pawn units).
     * wp = 100 / (1 + exp(-0.00368208 * cp)) where cp = eval * 100 (centipawns).
     */
    public static double winPct(double evalPawns) {
        double cp = evalPawns * 100.0;
        return 100.0 / (1.0 + Math.exp(-0.00368208 * cp));
    }

    /**
     * Move accuracy from win% drop (Chess.com formula).
     * accuracy = clamp(103.1668 × exp(−0.04354 × drop) − 3.1669, 0, 100)
     */
    public static double moveAccuracy(double winPctDrop) {
        double raw = 103.1668 * Math.exp(-0.04354 * winPctDrop) - 3.1669;
        return Math.max(0.0, Math.min(100.0, raw));
    }

    private GamePhase detectPhase(int ply, String fenBefore) {
        if (ply <= OPENING_PLY_MAX) return GamePhase.OPENING;
        if (countNonKingPieces(fenBefore) <= ENDGAME_PIECE_THRESHOLD) return GamePhase.ENDGAME;
        return GamePhase.MIDDLEGAME;
    }

    /**
     * Counts non-king pieces on the board from FEN (piece placement field only).
     * Kings are always present so excluding them gives a cleaner endgame signal.
     */
    private int countNonKingPieces(String fen) {
        if (fen == null || fen.isBlank()) return 16; // conservative — not endgame
        String placement = fen.split(" ")[0];
        int count = 0;
        for (char c : placement.toCharArray()) {
            switch (c) {
                case 'P','p','N','n','B','b','R','r','Q','q' -> count++;
                default -> { /* digit, slash, or king — ignore */ }
            }
        }
        return count;
    }
}
