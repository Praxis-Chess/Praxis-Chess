package com.praxis.service.practice;

import com.praxis.config.PraxisClock;
import com.praxis.domain.MetricSnapshot;
import com.praxis.domain.enums.MeaningfulActivity;
import com.praxis.repository.MetricSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The transactional half of the ledger write.
 *
 * Separate bean, not a private method, because the try/catch that makes a
 * ledger failure harmless has to sit OUTSIDE the transaction boundary. Catching
 * inside a transactional method does not help: the transaction is already
 * rollback-only by then, and the commit on the way out throws regardless — the
 * trap that took startup down when the backfill hit a null column.
 *
 * Self-invocation would bypass the proxy and silently drop REQUIRES_NEW, so the
 * split has to be across beans.
 */
@Service
public class PracticeDayWriter {

    private final MetricSnapshotRepository snapshots;
    private final PraxisClock clock;

    public PracticeDayWriter(MetricSnapshotRepository snapshots, PraxisClock clock) {
        this.snapshots = snapshots;
        this.clock = clock;
    }

    /**
     * REQUIRES_NEW so a failure here cannot poison the caller's transaction —
     * losing a streak day is a far smaller harm than losing the graded drill
     * attempt that was being recorded when it happened.
     *
     * @return true if this was the first qualifying activity of the day
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean write(String username, MeaningfulActivity activity) {
        LocalDate today = clock.today();
        MetricSnapshot snap = snapshots.findByUsernameAndSnapshotDate(username, today)
                .orElseGet(() -> MetricSnapshot.builder()
                        .username(username)
                        .snapshotDate(today)
                        .build());

        boolean firstToday = !snap.isPracticeDay();

        Set<String> acts = new LinkedHashSet<>();
        if (snap.getActivities() != null && !snap.getActivities().isBlank()) {
            acts.addAll(Arrays.asList(snap.getActivities().split(",")));
        }
        acts.add(activity.name());
        snap.setActivities(String.join(",", acts));

        if (activity == MeaningfulActivity.ANALYSIS_COMPLETED) {
            // Null-safe: rows predating this column read as null, not zero.
            snap.setAnalysisRuns(snap.analysisRunCount() + 1);
        }
        snap.setUpdatedAt(clock.now());
        snapshots.save(snap);

        return firstToday;
    }
}
