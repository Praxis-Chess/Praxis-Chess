package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.praxis.service.analysis.StockfishService;

/**
 * What the opponent was threatening <i>before</i> the move — the null-move probe.
 *
 * <p>This single fact separates the two commonest club-level mistakes, and the
 * pipeline has never had it:
 *
 * <ul>
 *   <li>The danger <b>already existed</b>, and the move failed to deal with it.
 *       The lesson is "you did not look at what your opponent wanted".</li>
 *   <li>The danger <b>did not exist</b> until the move created it. The lesson is
 *       "this move loosened something".</li>
 * </ul>
 *
 * <p>Those need opposite advice, and without this probe a model has no way to
 * tell them apart — so it guesses, fluently. Saying "your move allowed Qh4" when
 * Qh4 was already winning before the move is not a small error; it is coaching
 * the player to avoid a move that was not the problem.
 *
 * <p><b>How.</b> Give the opponent a free move: take the position, flip whose
 * turn it is, and search. The search <i>always</i> returns a move — a free tempo
 * is worth something to anyone — so the move alone is not a threat. The threat
 * is what passing would <b>cost</b>: this probe's score set against the position's
 * real value at the same depth, which {@link EvidenceExplainService} computes.
 * Treating "the probe found a move" as "a threat existed" called every mistake
 * of the first real game an ignored threat.
 * The one case where probing is impossible is when the player is in check — you
 * cannot pass when you must answer a check — and that is reported rather than
 * guessed around, because a probe that silently returns "no threat" in the one
 * position where the threat is loudest would be worse than none.
 *
 * <p>Deliberately not a Spring bean. A bean would be wired to the shared
 * engine, which runs multi-threaded for the analysis pipeline and so gives a
 * different answer on each run; evidence has to come from the deterministic
 * engine {@link EvidenceExplainService} owns, and constructing the probe there
 * makes that impossible to get wrong by injection.
 */
public class ThreatProbe {

    private final StockfishService stockfish;

    public ThreatProbe(StockfishService stockfish) {
        this.stockfish = stockfish;
    }

    /**
     * @param probed     false when no probe was possible; the other fields are then empty
     * @param skipReason why, when {@code probed} is false
     * @param moveUci    the opponent's best move given a free tempo
     * @param score      what that leaves, in pawns from White's point of view
     */
    public record Threat(boolean probed, String skipReason, String moveUci, Double score) {

        public static Threat skipped(String reason) {
            return new Threat(false, reason, null, null);
        }

        /**
         * The probe returned a move. NOT the same as "a threat existed" — see the
         * class comment; whether it is a threat depends on what it costs.
         */
        public boolean found() {
            return probed && moveUci != null;
        }
    }

    public Threat probe(String fen, int depth) {
        if (fen == null || fen.isBlank()) return Threat.skipped("no position");

        Board board = new Board();
        try {
            board.loadFromFen(fen);
        } catch (Exception e) {
            return Threat.skipped("unreadable position");
        }
        if (board.isKingAttacked()) {
            return Threat.skipped("player is in check — passing is not a legal option");
        }

        String nullMoveFen = pass(fen);
        if (nullMoveFen == null) return Threat.skipped("unreadable position");

        StockfishService.Search search = stockfish.search(nullMoveFen, depth);
        if (search.bestMoveUci() == null) return Threat.skipped("engine returned no move");
        return new Threat(true, null, search.bestMoveUci(), search.score());
    }

    /**
     * The same position with the other side to move.
     *
     * <p>The en-passant square must go with the turn. It describes a capture the
     * side to move could make this instant; handing it to the opponent offers
     * them a capture that is not available to them, and the engine will happily
     * search it and report a threat that does not exist.
     */
    public static String pass(String fen) {
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) return null;
        parts[1] = "w".equalsIgnoreCase(parts[1]) ? "b" : "w";
        parts[3] = "-";
        return String.join(" ", parts);
    }
}
