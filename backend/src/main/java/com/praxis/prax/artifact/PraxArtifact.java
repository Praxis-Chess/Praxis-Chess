package com.praxis.prax.artifact;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Something Prax can SHOW rather than describe.
 *
 * THE RULE THAT KEEPS THIS HONEST:
 *
 *     The model may reference an artifact. It may never author one.
 *
 * Every field below is computed by this backend — the FEN comes from a chesslib
 * replay, the arrows from moves resolved against the legal move list. The model
 * is never asked for a board position, so there is no fabricated-FEN failure
 * mode to guard against. That is the same separation the evidence layer already
 * uses: the model selects and phrases, the backend computes and renders.
 *
 * The vocabulary is deliberately small and frozen. Adding a type requires a
 * renderer and a test; without that discipline this becomes a generic
 * AI-generates-UI framework, which is not what a chess coach needs.
 */
public sealed interface PraxArtifact
        permits ChessPositionArtifact, MoveComparisonArtifact, ChartArtifact, TableArtifact {

    /**
     * The vocabulary. Small on purpose, and every member is emitted
     * DETERMINISTICALLY from a tool result — never chosen by the model.
     *
     * The plan reserved a fifth and sixth type (CHESS_SEQUENCE, METRIC). METRIC
     * is deliberately absent: the evidence table already renders label/value
     * pairs with provenance tags, and a second way to show one number would be
     * two things to keep consistent for no gain.
     */
    enum Type {
        /** A board, with squares highlighted and moves drawn. */
        CHESS_POSITION,
        /** The same board plus what the played move cost. */
        MOVE_COMPARISON,
        /** A count per category. */
        CHART,
        /** Rows to scan and compare. */
        TABLE
    }

    /** Stable within one answer, so the UI can key on it. */
    String id();

    /**
     * MUST carry @JsonProperty.
     *
     * These are records, and Jackson serialises record COMPONENTS. `type()` is
     * an override method, not a component, so without this it is simply absent
     * from the JSON — the frontend switch then falls to `default` and every
     * artifact renders as nothing at all. Silent, and invisible in any backend
     * test, because the artifact object itself was perfectly well formed.
     */
    @JsonProperty("type")
    Type type();

    /** Short heading. Written by the backend, not the model. */
    String title();
}
