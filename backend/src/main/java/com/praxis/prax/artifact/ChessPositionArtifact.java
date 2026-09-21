package com.praxis.prax.artifact;

import java.util.List;

/**
 * A board the player can look at, instead of a table of numbers describing one.
 *
 * This is the entire point of the feature. Prax already had all of this in hand
 * — the evaluation before and after, the move played, the engine's preference,
 * which piece was attacked — and rendered it as:
 *
 *     Evaluation before g6   ENGINE   5.28 pawns
 *     Material loss from g6  ENGINE   8.48 pawns
 *     Knight on c2 attacked  ENGINE   yes
 *
 * All true, all computed, and almost unreadable. The same facts drawn on a board
 * are immediately obvious.
 *
 * @param fen         from a chesslib replay of the real game. NEVER from the model.
 * @param orientation "white" or "black" — the player's side, so the board is
 *                    shown the way they saw it
 * @param highlights  squares worth the eye's attention, e.g. an attacked piece
 * @param arrows      moves drawn on the board
 * @param caption     one line of context, written by the backend
 */
public record ChessPositionArtifact(
        String id,
        String title,
        String fen,
        String orientation,
        List<String> highlights,
        List<Arrow> arrows,
        String caption
) implements PraxArtifact {

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("type")
    public Type type() {
        return Type.CHESS_POSITION;
    }

    /**
     * @param role what the arrow MEANS, not what colour it is.
     *
     *             The backend states meaning; the frontend owns the palette. A
     *             hex code chosen here would be a theming bug — it cannot know
     *             about light and dark mode, and the same green would mean
     *             different things on different screens.
     */
    public record Arrow(String from, String to, Role role) {
        public enum Role {
            /** What the engine would have played. */
            BEST,
            /** What the player actually played. */
            PLAYED,
            /** An attack that mattered. */
            THREAT
        }
    }
}
