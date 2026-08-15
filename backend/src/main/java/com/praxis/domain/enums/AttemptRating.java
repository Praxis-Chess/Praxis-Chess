package com.praxis.domain.enums;

/**
 * User's self-assessment after seeing the answer, used by the FSRS scheduler.
 * Maps to FSRS grades: AGAIN=1, HARD=2, GOOD=3, EASY=4.
 */
public enum AttemptRating {
    AGAIN,  // Forgot / wrong — re-enter learning
    HARD,   // Remembered but with significant difficulty
    GOOD,   // Recalled correctly with some effort (typical correct response)
    EASY    // Recalled perfectly, effortlessly
}
