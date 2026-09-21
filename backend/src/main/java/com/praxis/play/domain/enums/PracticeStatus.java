package com.praxis.play.domain.enums;

public enum PracticeStatus {
    IN_PROGRESS,
    /** Played to a real conclusion — analysed and counted. */
    FINISHED,
    /** Resigned or walked away. Kept for honesty, never analysed or counted. */
    ABANDONED
}
