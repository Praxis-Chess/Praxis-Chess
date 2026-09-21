package com.praxis.service.practice;

import com.praxis.config.AppProperties;
import com.praxis.config.PraxisClock;
import com.praxis.domain.MetricSnapshot;
import com.praxis.domain.enums.MeaningfulActivity;
import com.praxis.repository.AttemptRepository;
import com.praxis.repository.MetricSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Marks the practice days that already happened, once.
 *
 * Without this the feature launches reading "1 day" for someone with months of
 * history, which is worse than not shipping it.
 *
 * Two sources, both durable:
 *   1. existing snapshot rows with reviews — those days are already proven
 *   2. attempt timestamps — which recover the partial sessions the old
 *      completion-only snapshot write never recorded at all
 *
 * Analysis history is NOT recoverable. Re-analyze All rewrites every game's
 * analyzedAt to the moment it ran, so those timestamps no longer say when the
 * work was done; and using playedAt instead would credit days the player spent
 * on Chess.com rather than in Praxis.
 */
@Component
public class PracticeLedgerBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PracticeLedgerBackfill.class);

    private final MetricSnapshotRepository snapshots;
    private final AttemptRepository attempts;
    private final AppProperties props;
    private final PraxisClock clock;

    public PracticeLedgerBackfill(MetricSnapshotRepository snapshots, AttemptRepository attempts,
                                  AppProperties props, PraxisClock clock) {
        this.snapshots = snapshots;
        this.attempts = attempts;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Deliberately NOT @Transactional.
     *
     * Catching an exception inside a transactional method does not undo it: the
     * transaction is already marked rollback-only, so the commit on the way out
     * throws UnexpectedRollbackException and takes startup down with it — which
     * is exactly what the catch below was written to prevent. Each save() runs
     * in its own transaction instead, so a failure mid-backfill leaves the days
     * already marked and simply stops.
     */
    @Override
    public void run(ApplicationArguments args) {
        String username = props.chessCom() == null ? null : props.chessCom().username();
        if (username == null || username.isBlank()) return;

        try {
            var existing = snapshots.findByUsernameOrderBySnapshotDateDesc(username);

            // Guard on unmarked rows, not on an empty table — the table is
            // already populated, so "empty" would be the wrong question.
            boolean anyUnmarked = existing.stream().anyMatch(s -> !s.isPracticeDay());
            long attemptDays = 0;

            Map<LocalDate, MetricSnapshot> byDate = new HashMap<>();
            for (MetricSnapshot s : existing) byDate.put(s.getSnapshotDate(), s);

            // 1 — snapshot rows that clearly represent drilling
            int marked = 0;
            for (MetricSnapshot s : existing) {
                if (!s.isPracticeDay() && s.getCardsReviewed() > 0) {
                    s.setActivities(MeaningfulActivity.DRILL_COMPLETED.name());
                    snapshots.save(s);
                    marked++;
                }
            }

            // 2 — attempt days with no row at all: the sessions abandoned midway
            for (OffsetDateTime at : attempts.findAttemptTimestamps(username)) {
                LocalDate day = clock.dateOf(at);
                if (day == null) continue;
                MetricSnapshot s = byDate.get(day);
                if (s == null) {
                    s = MetricSnapshot.builder()
                            .username(username)
                            .snapshotDate(day)
                            .activities(MeaningfulActivity.DRILL_COMPLETED.name())
                            .build();
                    byDate.put(day, s);
                    snapshots.save(s);
                    attemptDays++;
                } else if (!s.isPracticeDay()) {
                    s.setActivities(MeaningfulActivity.DRILL_COMPLETED.name());
                    snapshots.save(s);
                    marked++;
                }
            }

            if (anyUnmarked || attemptDays > 0) {
                log.info("[practice] backfilled ledger: {} existing day(s) marked, "
                        + "{} day(s) recovered from attempts with no snapshot", marked, attemptDays);
            }
        } catch (Exception e) {
            // Startup must never fail because a backfill did.
            log.warn("[practice] ledger backfill skipped: {}", e.getMessage());
        }
    }
}
