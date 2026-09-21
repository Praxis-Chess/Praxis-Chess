package com.praxis.play.patterns;

import java.util.UUID;

/**
 * One game in which a tendency showed, addressable rather than merely printed.
 *
 * A bare "ply 23" is checkable by someone who can rebuild the position in their
 * head, which is not the player this feature is for. Carrying the ids lets the
 * client open the board at that move instead of asking the reader to imagine it.
 *
 * @param gameId the archived Game — what the review route takes
 * @param ply    1-indexed half-move, or null when the finding is about the game
 *               rather than a move. "Never castled" has no ply.
 * @param label  one short line, already phrased for display
 */
public record Occurrence(UUID gameId, Integer ply, String label) {

    public static Occurrence atPly(UUID gameId, int ply, String label) {
        return new Occurrence(gameId, ply, label);
    }

    public static Occurrence forGame(UUID gameId, String label) {
        return new Occurrence(gameId, null, label);
    }
}
