package com.praxis.dto;

import com.praxis.domain.DrillSession;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SessionDto(
        UUID id,
        int cardsTotal,
        int cardsCompleted,
        int budgetMinutes,
        boolean completed,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        /** Cards whose first attempt in this session was correct (F4); null when not counted. */
        Integer cardsSolved
) {
    public static SessionDto from(DrillSession s) {
        return from(s, null);
    }

    public static SessionDto from(DrillSession s, Integer cardsSolved) {
        return new SessionDto(s.getId(), s.getCardsTotal(), s.getCardsCompleted(),
                s.getBudgetMinutes(), s.isCompleted(), s.getStartedAt(), s.getCompletedAt(), cardsSolved);
    }
}
