package com.praxis.prax.artifact;

import com.praxis.prax.artifact.ChartArtifact.Bar;
import com.praxis.prax.artifact.ChartArtifact.ChartId;
import com.praxis.prax.artifact.TableArtifact.Align;
import com.praxis.prax.intelligence.ChessIntelligence.MotifStat;
import com.praxis.prax.intelligence.ChessIntelligence.OpeningStat;
import com.praxis.prax.intelligence.ChessIntelligence.PhaseStat;
import com.praxis.prax.intelligence.ChessIntelligence.Recommendation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Charts and tables, built from tool results that already counted the rows.
 *
 * Same rule as the board: DETERMINISTIC. If get_mistake_patterns ran, its
 * numbers are a bar chart by construction — nothing is asked of the model, so
 * there is no contract it can get wrong and no invented category to guard
 * against.
 *
 * This is also the argument against the "model plans the artifact" design the
 * original sketch proposed. Every artifact type here is implied by the tool that
 * produced its data. Letting the model choose would add a JSON contract, a
 * validation path and a retry, in exchange for a decision that is already made.
 */
@Component
public class StatArtifactBuilder {

    /** Beyond this a bar chart is a wall of labels rather than a comparison. */
    static final int MAX_BARS = 8;
    /** Beyond this a table stops being scannable and becomes a data dump. */
    static final int MAX_ROWS = 12;

    /** "HANGING_PIECE" reads as a database column; "Hanging piece" reads as chess. */
    static String humanise(String enumish) {
        if (enumish == null || enumish.isBlank()) return "Unknown";
        String s = enumish.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return s.isEmpty() ? "Unknown" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public PraxArtifact mistakesByMotif(List<MotifStat> motifs) {
        if (motifs == null || motifs.isEmpty()) return null;

        List<Bar> bars = new ArrayList<>();
        for (MotifStat m : motifs) {
            if (m.count() <= 0) continue;
            bars.add(new Bar(humanise(m.motif()), m.count()));
            if (bars.size() >= MAX_BARS) break;
        }
        if (bars.isEmpty()) return null;

        return ArtifactValidator.validate(new ChartArtifact(
                "chart-motifs", "Where your mistakes come from",
                ChartId.MISTAKES_BY_MOTIF, "mistakes", bars, null));
    }

    public PraxArtifact mistakesByPhase(List<PhaseStat> phases) {
        if (phases == null || phases.isEmpty()) return null;

        List<Bar> bars = new ArrayList<>();
        for (PhaseStat p : phases) {
            if (p.errors() <= 0) continue;
            bars.add(new Bar(humanise(p.phase()), p.errors()));
        }
        if (bars.isEmpty()) return null;

        return ArtifactValidator.validate(new ChartArtifact(
                "chart-phases", "Which part of the game costs you most",
                ChartId.MISTAKES_BY_PHASE, "errors", bars, null));
    }

    /**
     * A ranked recommendation, as rows.
     *
     * "Suggest an opening" is the question most damaged by prose: the answer is
     * a comparison across several candidates, and a paragraph can only assert
     * the winner. A table lets the player see WHY it won — and disagree.
     */
    public PraxArtifact recommendationTable(List<Recommendation> recs) {
        if (recs == null || recs.isEmpty()) return null;

        List<List<String>> rows = new ArrayList<>();
        for (Recommendation r : recs) {
            if (r.games() <= 0) continue;
            rows.add(List.of(
                    r.name() != null && !r.name().isBlank() ? r.name() : r.eco(),
                    String.valueOf(r.games()),
                    String.format("%.0f%%", r.winPct()),
                    r.avgAccuracy() == null ? "—" : String.format("%.0f%%", r.avgAccuracy())));
            if (rows.size() >= MAX_ROWS) break;
        }
        if (rows.isEmpty()) return null;

        return ArtifactValidator.validate(new TableArtifact(
                "table-recommendations", "Ranked by your own results",
                List.of("Opening", "Games", "Win rate", "Accuracy"),
                List.of(Align.LEFT, Align.RIGHT, Align.RIGHT, Align.RIGHT),
                rows, null));
    }

    /**
     * Openings as rows.
     *
     * A dozen openings times four numbers is a comparison no paragraph can
     * carry. Values are pre-formatted here because the backend knows a win rate
     * is one decimal place and a game count is an integer — deciding that again
     * in the renderer is how 52.4 becomes 52.400000000000006.
     */
    public PraxArtifact openingTable(List<OpeningStat> openings) {
        if (openings == null || openings.isEmpty()) return null;

        List<List<String>> rows = new ArrayList<>();
        for (OpeningStat o : openings) {
            if (o.games() <= 0) continue;
            rows.add(List.of(
                    o.name() != null && !o.name().isBlank() ? o.name() : o.eco(),
                    String.valueOf(o.games()),
                    String.format("%.0f%%", o.winPct()),
                    // "—" rather than 0: an unmeasured accuracy is not a bad one.
                    o.avgAccuracy() == null ? "—" : String.format("%.0f%%", o.avgAccuracy())));
            if (rows.size() >= MAX_ROWS) break;
        }
        if (rows.isEmpty()) return null;

        return ArtifactValidator.validate(new TableArtifact(
                "table-openings", "Your openings",
                List.of("Opening", "Games", "Win rate", "Accuracy"),
                List.of(Align.LEFT, Align.RIGHT, Align.RIGHT, Align.RIGHT),
                rows, null));
    }
}
