package com.praxis.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * The single source of "what day is it".
 *
 * The codebase writes instants as {@code OffsetDateTime.now(ZoneOffset.UTC)} but
 * computes dates with a bare {@code LocalDate.now()}, which resolves to the JVM
 * zone. Those two disagree by up to a day, which for anything day-shaped — FSRS
 * due dates, the practice ledger — means a drill at 23:30 lands on tomorrow, or
 * yesterday, depending on which side of the code you ask.
 *
 * Everything day-shaped goes through here so there is exactly one answer, and so
 * the midnight and DST cases can be tested with a fixed clock instead of by
 * waiting for midnight.
 */
@Component
public class PraxisClock {

    private final ZoneId zone;
    private final Clock clock;

    /**
     * Explicitly annotated, because there is more than one constructor.
     *
     * Spring only auto-selects a constructor when {@code getDeclaredConstructors()}
     * returns exactly one — and that includes private ones, so hiding the test
     * seam did not reduce the count. Without this annotation Spring finds two
     * candidates, declines to guess, falls back to a no-arg constructor that does
     * not exist, and the whole context fails to start.
     */
    @Autowired
    public PraxisClock(AppProperties props) {
        this.zone = props.practiceZone();
        this.clock = Clock.systemDefaultZone();
    }

    private PraxisClock(ZoneId zone, Clock clock) {
        this.zone = zone;
        this.clock = clock;
    }

    /** Test seam — pass {@code Clock.fixed(...)} to pin the moment. */
    public static PraxisClock fixed(ZoneId zone, Clock clock) {
        return new PraxisClock(zone, clock);
    }

    public ZoneId zone() {
        return zone;
    }

    /** The user's current calendar day. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock.withZone(zone));
    }

    /** Which calendar day an instant fell on, for the user. */
    public LocalDate dateOf(OffsetDateTime at) {
        return at == null ? null : at.atZoneSameInstant(zone).toLocalDate();
    }
}
