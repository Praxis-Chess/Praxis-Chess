package com.praxis.prax.artifact;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.praxis.prax.artifact.ChessPositionArtifact.Arrow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns an analyze_position result into a board.
 *
 * DETERMINISTIC, and that is the whole design. The model is not asked whether a
 * board would help, nor what should be on it. Any answer whose run included
 * analyze_position gets one, built in Java from data the backend already
 * computed for the evidence table.
 *
 * Which means Phase 0 has no model involvement at all: no new contract fields
 * for a 4B model to get wrong, no retry path, no fabricated-FEN failure mode.
 * The established lesson again — structural beats prompted.
 */
@Component
public class ChessPositionArtifactBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChessPositionArtifactBuilder.class);

    /**
     * @param id       unique within the answer
     * @param fen      the position, as passed to the engine
     * @param bestUci  engine's preferred move, UCI
     * @param playedUci what the player actually did, UCI, or null
     * @param attacked squares the fact builder flagged as under attack
     * @return a validated artifact, or null if anything did not check out
     */
    public PraxArtifact build(String id, String fen, String bestUci, String playedUci,
                              List<String> attacked, String caption) {
        if (fen == null || fen.isBlank()) return null;

        String orientation = orientationOf(fen);

        List<Arrow> arrows = new ArrayList<>();
        // PLAYED first so that when both moves start from the same square the
        // engine's suggestion draws on top — the point of the diagram is the
        // alternative, not the mistake.
        addArrow(arrows, playedUci, Arrow.Role.PLAYED);
        addArrow(arrows, bestUci, Arrow.Role.BEST);

        // Squares worth looking at: the endpoints of both moves, plus anything
        // the engine said was under attack. A set, because the played and best
        // moves frequently share a target and a doubled highlight renders twice.
        Set<String> highlights = new LinkedHashSet<>();
        for (Arrow a : arrows) {
            highlights.add(a.from());
            highlights.add(a.to());
        }
        if (attacked != null) {
            for (String sq : attacked) {
                if (ArtifactValidator.isSquare(sq)) highlights.add(sq.toLowerCase(Locale.ROOT));
            }
        }

        var artifact = new ChessPositionArtifact(
                id,
                playedUci != null ? "The position when it went wrong" : "The position",
                fen,
                orientation,
                List.copyOf(highlights),
                List.copyOf(arrows),
                caption);

        // Validated before it leaves, not on arrival at the UI. A board that
        // cannot be trusted must never reach a renderer.
        PraxArtifact valid = ArtifactValidator.validate(artifact);
        if (valid == null) {
            log.warn("[prax] position artifact failed validation for fen {}", fen);
        }
        return valid;
    }

    /**
     * The board, plus the arithmetic.
     *
     * Emitted INSTEAD of a plain position when the played move is known — never
     * as well, or one position would render two boards.
     *
     * @param evalBefore White-positive pawns, as the engine reported
     * @param evalAfter  same convention, after the played move
     */
    public PraxArtifact buildComparison(String id, String fen, String bestUci, String playedUci,
                                        String playedSan, String bestSan,
                                        Double evalBefore, Double evalAfter, String caption) {
        PraxArtifact board = build(id, fen, bestUci, playedUci, null, caption);
        if (!(board instanceof ChessPositionArtifact p)) return board;

        // The cost, only when both endpoints actually exist. A missing second
        // search means "not measured", and 0.0 would read as "cost nothing" —
        // the sentinel confusion that made 61 games report zero accuracy.
        Double loss = (evalBefore != null && evalAfter != null)
                ? round(Math.abs(evalBefore - evalAfter))
                : null;

        var comparison = new MoveComparisonArtifact(
                p.id(), p.title(), p.fen(), p.orientation(), p.highlights(), p.arrows(),
                playedSan != null ? playedSan : playedUci,
                bestSan != null ? bestSan : bestUci,
                round(evalBefore), round(evalAfter), loss,
                caption);

        return ArtifactValidator.validate(comparison);
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 100.0) / 100.0;
    }

    /**
     * Show the board from the perspective of whoever is to move.
     *
     * They are the one who blundered, and a diagram of their mistake seen from
     * the opponent's side is measurably harder to read.
     */
    static String orientationOf(String fen) {
        try {
            Board b = new Board();
            b.loadFromFen(fen);
            return b.getSideToMove() == Side.BLACK ? "black" : "white";
        } catch (Exception e) {
            return "white";
        }
    }

    /** UCI is from+to plus an optional promotion letter: e2e4, e7e8q. */
    private static void addArrow(List<Arrow> out, String uci, Arrow.Role role) {
        if (uci == null || uci.length() < 4) return;
        String from = uci.substring(0, 2).toLowerCase(Locale.ROOT);
        String to = uci.substring(2, 4).toLowerCase(Locale.ROOT);
        if (!ArtifactValidator.isSquare(from) || !ArtifactValidator.isSquare(to)) return;
        if (from.equals(to)) return;
        out.add(new Arrow(from, to, role));
    }
}
