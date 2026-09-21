package com.praxis.prax.artifact;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Refuses to render a board that is not a board.
 *
 * In Phase 0 every artifact is built in Java from a replayed game, so nothing
 * here should ever fail. That is exactly why it exists: the day someone lets the
 * model choose a FEN — or an off-by-one creeps into ply indexing — this is what
 * stands between that and a confidently wrong diagram.
 *
 * POLICY: a failing artifact is DROPPED and the prose is KEPT. Same rule the
 * evidence validator applies to uncited claims. A missing board is a smaller
 * loss than a wrong one, and a wrong one is worse than no feature at all —
 * because the player has no way to tell it is wrong.
 */
public final class ArtifactValidator {

    private static final Logger log = LoggerFactory.getLogger(ArtifactValidator.class);

    private ArtifactValidator() {}

    /**
     * @return the artifact if every field checks out, otherwise null
     */
    public static PraxArtifact validate(PraxArtifact artifact) {
        if (artifact == null) return null;
        return switch (artifact) {
            case ChessPositionArtifact p -> validatePosition(p);
            // Validated through its board half, so one code path covers both and
            // a fix to square checking cannot apply to only one of them.
            case MoveComparisonArtifact m -> validatePosition(m.position()) == null ? null : m;
            case ChartArtifact c -> validateChart(c);
            case TableArtifact t -> validateTable(t);
        };
    }

    private static ChartArtifact validateChart(ChartArtifact c) {
        if (c.chartId() == null) return dropChart(c, "no chartId");
        if (c.bars() == null || c.bars().isEmpty()) return dropChart(c, "no bars");
        for (var b : c.bars()) {
            if (b == null || b.label() == null || b.label().isBlank()) {
                return dropChart(c, "bar with no label");
            }
            // NaN and infinity render as a bar of undefined length — a chart that
            // is visibly broken is better than one that is quietly wrong, but
            // neither should ship.
            if (!Double.isFinite(b.value())) {
                return dropChart(c, "bar value is not finite: " + b.label());
            }
            if (b.value() < 0) return dropChart(c, "negative bar: " + b.label());
        }
        return c;
    }

    private static TableArtifact validateTable(TableArtifact t) {
        if (t.columns() == null || t.columns().isEmpty()) return dropTable(t, "no columns");
        if (t.rows() == null || t.rows().isEmpty()) return dropTable(t, "no rows");
        if (t.align() != null && t.align().size() != t.columns().size()) {
            return dropTable(t, "align has " + t.align().size()
                    + " entries for " + t.columns().size() + " columns");
        }
        for (var row : t.rows()) {
            // A short row silently shifts every value left of the gap into the
            // wrong column, which reads as data rather than as an error.
            if (row == null || row.size() != t.columns().size()) {
                return dropTable(t, "row width " + (row == null ? "null" : row.size())
                        + " does not match " + t.columns().size() + " columns");
            }
        }
        return t;
    }

    private static ChessPositionArtifact validatePosition(ChessPositionArtifact p) {
        // The FEN must load. Not "looks like a FEN" — actually parses, in the
        // same library that will be asked to render it.
        if (p.fen() == null || p.fen().isBlank()) {
            return drop(p, "no fen");
        }
        try {
            new Board().loadFromFen(p.fen());
        } catch (Exception e) {
            return drop(p, "fen does not parse: " + e.getMessage());
        }

        if (p.orientation() == null
                || !(p.orientation().equals("white") || p.orientation().equals("black"))) {
            return drop(p, "orientation must be white or black, was " + p.orientation());
        }

        if (p.highlights() != null) {
            for (String sq : p.highlights()) {
                if (!isSquare(sq)) return drop(p, "highlight is not a square: " + sq);
            }
        }

        if (p.arrows() != null) {
            for (var a : p.arrows()) {
                if (a == null || a.role() == null) return drop(p, "arrow has no role");
                if (!isSquare(a.from()) || !isSquare(a.to())) {
                    return drop(p, "arrow endpoint is not a square: " + a.from() + "->" + a.to());
                }
                if (a.from().equals(a.to())) {
                    return drop(p, "arrow starts and ends on " + a.from());
                }
            }
        }
        return p;
    }

    /** a1..h8, as chesslib understands them. */
    static boolean isSquare(String s) {
        if (s == null || s.length() != 2) return false;
        try {
            Square sq = Square.valueOf(s.toUpperCase(Locale.ROOT));
            return sq != Square.NONE;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ChartArtifact dropChart(ChartArtifact c, String why) {
        log.warn("[prax] dropped CHART artifact — {}", why);
        return null;
    }

    private static TableArtifact dropTable(TableArtifact t, String why) {
        log.warn("[prax] dropped TABLE artifact — {}", why);
        return null;
    }

    private static ChessPositionArtifact drop(ChessPositionArtifact p, String why) {
        // WARN, not debug: in Phase 0 this cannot happen by design, so if it
        // does, something upstream has broken and silence would hide it.
        log.warn("[prax] dropped {} artifact — {}", p.type(), why);
        return null;
    }
}
