package com.praxis.prax.artifact;

import java.util.List;

/**
 * A count per category — where the rating actually leaks.
 *
 * "Most of your blunders land in the middlegame (97 of them)" is one sentence
 * competing with three others. The same numbers as bars are read in a glance,
 * and the comparison between categories — which is the actual finding — becomes
 * the thing the eye lands on first.
 *
 * THE SERIES IS NEVER MODEL-AUTHORED. It is transcribed from a tool result that
 * already counted rows in PostgreSQL. There is no path by which a bar can show a
 * number nobody computed.
 *
 * @param chartId names the shape of the data, so the frontend can label axes
 *                without the backend shipping presentation strings
 * @param unit    what a value MEANS — "blunders", "games". Rendered next to the
 *                number rather than baked into it.
 */
public record ChartArtifact(
        String id,
        String title,
        ChartId chartId,
        String unit,
        List<Bar> bars,
        String caption
) implements PraxArtifact {

    /**
     * A closed set, deliberately.
     *
     * A free-text identifier here would be a string the model could invent, and
     * a chart labelled with an invented axis is a chart that lies quietly.
     */
    public enum ChartId {
        /** Blunder counts per tactical motif. */
        MISTAKES_BY_MOTIF,
        /** Errors and blunders per game phase. */
        MISTAKES_BY_PHASE,
        /** Games played per opening. */
        GAMES_BY_OPENING
    }

    public record Bar(String label, double value) {}

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("type")
    public Type type() {
        return Type.CHART;
    }
}
