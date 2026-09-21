package com.praxis.service.practice;

import com.praxis.domain.enums.MeaningfulActivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Marks the days on which real work happened. The streak is derived from these
 * rows and nothing else — no counter is stored, so there is no counter to drift.
 *
 * Deliberately NOT transactional: this is the outside of the boundary, where a
 * catch can actually swallow a failure. The write itself lives in
 * {@link PracticeDayWriter}.
 */
@Service
public class PracticeLedgerService {

    private static final Logger log = LoggerFactory.getLogger(PracticeLedgerService.class);

    private final PracticeDayWriter writer;

    public PracticeLedgerService(PracticeDayWriter writer) {
        this.writer = writer;
    }

    /**
     * Record that a meaningful activity happened today. Idempotent per day: a
     * hundred drills and a Re-analyze All over a hundred games all collapse into
     * one row, because a day is a day.
     *
     * <p>Never throws. This observes work rather than doing it, and a ledger
     * failure must not take down the drill attempt or analysis run that was
     * being recorded.
     *
     * @return true if this was the first qualifying activity of the day
     */
    public boolean record(String username, MeaningfulActivity activity) {
        try {
            boolean firstToday = writer.write(username, activity);
            if (firstToday) log.info("[practice] first activity today — {}", activity);
            return firstToday;
        } catch (Exception e) {
            log.warn("[practice] could not record {} for {}: {}", activity, username, e.getMessage());
            return false;
        }
    }
}
