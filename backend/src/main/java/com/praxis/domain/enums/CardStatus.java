package com.praxis.domain.enums;

public enum CardStatus {
    /** Card has never been reviewed. */
    NEW,
    /** Card is in active learning (interval < graduation threshold). */
    LEARNING,
    /** Card has graduated — shown at spaced intervals. */
    REVIEW,
    /** Card lapsed — was reviewed but failed; restarted from learning. */
    RELEARNING,
    /** Card suspended after repeated lapses (lapse count ≥ 5). */
    SUSPENDED
}
