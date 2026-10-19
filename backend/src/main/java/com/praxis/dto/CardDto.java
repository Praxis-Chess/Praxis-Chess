package com.praxis.dto;

import com.praxis.domain.Card;
import com.praxis.domain.MistakeEvidence;
import com.praxis.domain.MoveError;
import com.praxis.domain.enums.CardStatus;
import com.praxis.service.diagnosis.VerifiedMistakes;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Card DTO — carries everything the frontend needs to render one drill card.
 */
public record CardDto(
        UUID id,
        String fenPosition,
        String movePlayed,      // the mistake move (SAN)
        String betterMove,      // engine best move (UCI)
        String severity,
        String tacticalMotif,
        String gamePhase,
        String playerColor,
        String explanation,
        CardStatus status,
        int intervalDays,
        LocalDate dueDate,
        int reviewCount,
        int lapseCount,
        UUID gameId
) {
    public static CardDto from(Card card) {
        return from(card, null);
    }

    /**
     * Motif and explanation come from the mistake's verified diagnosis (Phase 12):
     * the checked commentary when there is one, otherwise the rules' explanation.
     * The old per-move model's motif and text are never shown; without a current
     * diagnosis both are null.
     */
    public static CardDto from(Card card, MistakeEvidence evidence) {
        MoveError e = card.getSourceError();
        VerifiedMistakes.Diagnosis d = VerifiedMistakes.of(evidence);
        return new CardDto(
                card.getId(),
                card.getFenPosition(),
                e != null ? e.getMovePlayed() : null,
                e != null ? e.getBetterMove()  : null,
                e != null && e.getSeverity() != null ? e.getSeverity().name() : null,
                d != null ? d.motif() : null,
                e != null && e.getGamePhase()    != null ? e.getGamePhase().name()    : null,
                e != null ? e.getPlayerColor()   : null,
                d != null ? d.text() : null,
                card.getStatus(),
                card.getIntervalDays(),
                card.getDueDate(),
                card.getReviewCount(),
                card.getLapseCount(),
                e != null && e.getGame() != null ? e.getGame().getId() : null
        );
    }
}
