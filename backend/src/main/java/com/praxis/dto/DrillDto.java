package com.praxis.dto;

import com.praxis.domain.MoveError;
import com.praxis.service.diagnosis.VerifiedMistakes;

import java.util.UUID;

/**
 * A tactics drill generated from one of the player's own mistakes.
 * The user is shown {@code fen} (their position before the blunder) and must find {@code bestMove}.
 */
public record DrillDto(
        UUID id,
        String fen,
        String bestMove,      // engine move in UCI (e.g. "e2e4"), the drill solution
        String movePlayed,    // what the player actually played (SAN)
        String severity,
        String tacticalMotif,
        String gamePhase,
        String explanation,
        String playerColor,   // board orientation / side to move
        String gameId,
        /** The ply of the mistake in its game: with gameId, the position's stable name across re-analysis. */
        int ply
) {
    /** Motif and explanation from the verified diagnosis (Phase 12); null without one. */
    public static DrillDto from(VerifiedMistakes.Mistake m) {
        MoveError e = m.error();
        VerifiedMistakes.Diagnosis d = m.diagnosis();
        return new DrillDto(
                e.getId(),
                e.getFenPosition(),
                e.getBetterMove(),
                e.getMovePlayed(),
                e.getSeverity() != null ? e.getSeverity().name() : null,
                d != null ? d.motif() : null,
                e.getGamePhase() != null ? e.getGamePhase().name() : null,
                d != null ? d.text() : null,
                e.getPlayerColor(),
                e.getGame() != null ? e.getGame().getId().toString() : null,
                e.getMoveNumber());
    }
}
