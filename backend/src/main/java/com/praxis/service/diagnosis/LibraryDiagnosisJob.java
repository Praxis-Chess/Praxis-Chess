package com.praxis.service.diagnosis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Phase 9: give every mistake in the library a current, verified diagnosis.
 *
 * <p>Runs the same builds the Rule validation page runs 25 at a time: first
 * every diagnosis from an older builder is rebuilt (its hand label kept), then
 * every mistake without one is built, until nothing is left. On the analysis
 * executor, which is single-threaded: it never competes with an analysis run
 * for the engine, and queues behind one that is under way.
 *
 * <p>New games need none of this: the pipeline diagnoses each game as it
 * commits ({@link DiagnosisService#buildForGame}). This is the backfill, and
 * the way to catch up after a rule change.
 */
@Component
public class LibraryDiagnosisJob {

    private static final Logger log = LoggerFactory.getLogger(LibraryDiagnosisJob.class);
    private static final int BATCH = 25;

    public enum State { IDLE, QUEUED, RUNNING, DONE, STOPPED, FAILED }

    /**
     * @param commented   Phase 9b: mistakes given the trained model's commentary this run
     * @param uncommented current diagnoses still without it (0 when no commentary model is set)
     */
    public record Status(State state, int built, int rebuilt, int failed, long missing, long stale,
                         int commented, long uncommented, boolean commentaryEnabled,
                         OffsetDateTime startedAt, OffsetDateTime finishedAt, String error) {}

    private final DiagnosisService diagnosis;

    private volatile State state = State.IDLE;
    private volatile int built, rebuilt, failed, commented;
    private volatile long missing, stale, uncommented;
    private volatile OffsetDateTime startedAt, finishedAt;
    private volatile String error;
    private volatile boolean stopRequested;

    public LibraryDiagnosisJob(DiagnosisService diagnosis) {
        this.diagnosis = diagnosis;
    }

    public Status status() {
        if (state == State.IDLE || state == State.DONE || state == State.STOPPED || state == State.FAILED) {
            var c = diagnosis.coverage();       // fresh counts whenever nothing is in flight
            missing = c.missing();
            stale = c.stale();
            uncommented = diagnosis.uncommented();
        }
        return new Status(state, built, rebuilt, failed, missing, stale, commented, uncommented,
                diagnosis.commentaryEnabled(), startedAt, finishedAt, error);
    }

    /** Claims the job; false if one is already queued or running. */
    public synchronized boolean tryQueue() {
        if (state == State.QUEUED || state == State.RUNNING) return false;
        state = State.QUEUED;
        built = rebuilt = failed = commented = 0;
        startedAt = finishedAt = null;
        error = null;
        stopRequested = false;
        return true;
    }

    public void requestStop() {
        stopRequested = true;
    }

    @Async("analysisExecutor")
    public void run() {
        state = State.RUNNING;
        startedAt = OffsetDateTime.now();
        log.info("[diagnosis] library job started");
        try {
            // Older diagnoses first: they are what the app would show wrongly.
            while (!stopRequested) {
                var r = diagnosis.rebuild(BATCH);
                rebuilt += r.built();
                failed += r.failed();
                stale = r.remaining();
                // A batch that fixed nothing means only failures are left: stop, don't retry them forever.
                if (r.built() == 0 || r.remaining() <= 0) break;
            }
            while (!stopRequested) {
                var r = diagnosis.build(BATCH);
                built += r.built();
                failed += r.failed();
                missing = r.remaining();
                if (r.built() == 0 || r.remaining() <= 0) break;
            }
            // Phase 9b: the trained model's commentary on every current diagnosis
            // (~5 s each on the laptop GPU, through Ollama; the engine is idle).
            while (!stopRequested && diagnosis.commentaryEnabled()) {
                var r = diagnosis.comment(BATCH);
                commented += r.built();
                failed += r.failed();
                uncommented = r.remaining();
                if (r.built() == 0 || r.remaining() <= 0) break;
            }
            state = stopRequested ? State.STOPPED : State.DONE;
        } catch (Exception e) {
            log.error("[diagnosis] library job failed: {}", e.getMessage(), e);
            error = e.getMessage();
            state = State.FAILED;
        } finally {
            finishedAt = OffsetDateTime.now();
            log.info("[diagnosis] library job {}: {} built, {} rebuilt, {} commented, {} failed",
                    state, built, rebuilt, commented, failed);
        }
    }
}
