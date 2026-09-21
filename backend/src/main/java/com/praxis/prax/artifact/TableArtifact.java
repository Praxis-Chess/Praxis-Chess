package com.praxis.prax.artifact;

import java.util.List;

/**
 * Rows the player can scan.
 *
 * For questions whose honest answer is a comparison across many things —
 * "which openings suit me" is six numbers per opening across a dozen openings.
 * Prose flattens that into a ranking the model chose; a table lets the player do
 * their own comparing, which is the point of a coaching tool.
 *
 * Values are pre-formatted STRINGS, not numbers. That is deliberate: the backend
 * already knows a win rate is a percentage to one decimal and a game count is an
 * integer, and duplicating those rules in the renderer is how "52.4%" becomes
 * "52.400000000000006%".
 *
 * @param align one entry per column. Numbers right, text left — a column of
 *              right-aligned figures can be compared down its length.
 */
public record TableArtifact(
        String id,
        String title,
        List<String> columns,
        List<Align> align,
        List<List<String>> rows,
        String caption
) implements PraxArtifact {

    public enum Align { LEFT, RIGHT }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("type")
    public Type type() {
        return Type.TABLE;
    }
}
