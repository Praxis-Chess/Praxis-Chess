package com.praxis.service.drills;

import com.praxis.domain.Card;
import com.praxis.domain.enums.AttemptRating;

/**
 * Pluggable spaced-repetition scheduling strategy.
 *
 * Implementations must be stateless — all state lives in the Card entity.
 * The {@link #schedule} method returns a mutated copy; callers persist it.
 */
public interface SchedulerStrategy {

    /**
     * Apply the rating to the card and compute the next review interval.
     *
     * @param card   current card state (never null)
     * @param rating user's self-assessed recall quality
     * @return updated card with new status, stability, difficulty, intervalDays, dueDate
     */
    Card schedule(Card card, AttemptRating rating);
}
