package com.praxis.prax.chat;

import com.praxis.prax.artifact.PraxArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Answers in flight, so the browser can watch one being formed.
 *
 * A question takes 15-40s. In the floating card that reads as a pause; in a
 * full-width workspace it reads as broken. The fix is not to make the model
 * faster — it is to stop hiding the work: tool calls appear as they run, and a
 * board appears the moment analyze_position returns, well before the prose.
 *
 * In memory, deliberately. A run is worthless once its answer has been stored in
 * the conversation, and a run that outlived a restart would be one nobody is
 * waiting for. Persistence belongs to the conversation, not to the run.
 */
@Service
public class PraxRunRegistry {

    private static final Logger log = LoggerFactory.getLogger(PraxRunRegistry.class);

    /** Enough for a handful of tabs; anything older is finished and read. */
    static final int MAX_RUNS = 32;
    /** A run nobody collected. Long enough to survive a slow answer and a reload. */
    static final long EVICT_AFTER_MS = 10 * 60_000;

    public enum Status { RUNNING, DONE, FAILED }

    /**
     * Mutable on purpose, and written from the prax- thread while read from
     * request threads. Every field is either volatile or a concurrent
     * collection — a half-written step list would render as a flicker.
     */
    public static final class Run {
        public final UUID id = UUID.randomUUID();
        public final long startedAt = System.currentTimeMillis();
        public final List<PraxAgent.Step> steps = new CopyOnWriteArrayList<>();
        public final List<PraxArtifact> artifacts = new CopyOnWriteArrayList<>();
        volatile Status status = Status.RUNNING;
        volatile PraxReasoningService.Answer answer;
        volatile String error;
        volatile Instant finishedAt;

        public Status status() { return status; }
        public PraxReasoningService.Answer answer() { return answer; }
        public String error() { return error; }
    }

    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    public Run start() {
        evictStale();
        Run run = new Run();
        runs.put(run.id, run);
        return run;
    }

    public Run find(UUID id) {
        return runs.get(id);
    }

    /**
     * Records a completed tool call. Artifacts are deduplicated by id, because a
     * board is keyed on its position and two calls on one position are one
     * diagram.
     */
    public void addStep(Run run, PraxAgent.Step step, List<PraxArtifact> artifacts) {
        if (run == null) return;
        run.steps.add(step);
        for (PraxArtifact a : artifacts) {
            if (a == null) continue;
            boolean known = run.artifacts.stream().anyMatch(x -> x.id().equals(a.id()));
            if (!known) run.artifacts.add(a);
        }
    }

    public void succeed(Run run, PraxReasoningService.Answer answer) {
        if (run == null) return;
        run.answer = answer;
        run.status = Status.DONE;
        run.finishedAt = Instant.now();
    }

    public void fail(Run run, String message) {
        if (run == null) return;
        run.error = message;
        run.status = Status.FAILED;
        run.finishedAt = Instant.now();
        log.warn("[prax] run {} failed: {}", run.id, message);
    }

    /**
     * Drops runs nobody came back for.
     *
     * Without this the map is a slow leak: every question ever asked, held with
     * its full answer payload, for as long as the process lives.
     */
    private void evictStale() {
        long now = System.currentTimeMillis();
        runs.entrySet().removeIf(e -> now - e.getValue().startedAt > EVICT_AFTER_MS);

        if (runs.size() <= MAX_RUNS) return;
        // Oldest first, keeping the cap. Sorted rather than arbitrary, so a
        // still-running answer is never the one thrown away.
        List<Map.Entry<UUID, Run>> byAge = new ArrayList<>(runs.entrySet());
        byAge.sort((a, b) -> Long.compare(a.getValue().startedAt, b.getValue().startedAt));
        for (int i = 0; i < byAge.size() - MAX_RUNS; i++) {
            runs.remove(byAge.get(i).getKey());
        }
    }

    /**
     * What the client polls for.
     *
     * A RECORD, not a Map. Jackson's SNAKE_CASE strategy does not apply to Map
     * keys, so a Map here would ship `runId` to a client reading `run_id` — the
     * exact failure that sent every practice move to /session/undefined/move.
     */
    public record RunView(
            UUID runId,
            Status status,
            List<PraxAgent.Step> steps,
            List<PraxArtifact> artifacts,
            PraxReasoningService.Answer answer,
            String error,
            long elapsedMs
    ) {}

    public RunView view(Run run) {
        return new RunView(
                run.id,
                run.status,
                List.copyOf(run.steps),
                List.copyOf(run.artifacts),
                run.answer,
                run.error,
                System.currentTimeMillis() - run.startedAt);
    }
}
