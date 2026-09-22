package com.praxis.pipeline;

import com.praxis.domain.AnalysisTiming;
import com.praxis.repository.AnalysisTimingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one {@link AnalysisTiming} row, in its OWN transaction.
 *
 * It's a separate bean for exactly that reason. The timing is recorded at the
 * end of a game's analysis, inside the game's REQUIRES_NEW transaction. A failed
 * insert there would mark that transaction rollback-only, and the game's whole
 * analysis — its move errors, its accuracy — would be discarded to protect a
 * performance metric. A nested REQUIRES_NEW transaction fails alone.
 *
 * Callers must invoke {@link #record} on the INJECTED bean and catch its
 * exception themselves. A "quiet" wrapper method in this class calling
 * {@code record} would be a self-invocation, which bypasses Spring's proxy, and
 * the REQUIRES_NEW would silently not apply.
 */
@Service
public class AnalysisTimingRecorder {

    private final AnalysisTimingRepository timings;

    public AnalysisTimingRecorder(AnalysisTimingRepository timings) {
        this.timings = timings;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AnalysisTiming timing) {
        timings.save(timing);
    }
}
