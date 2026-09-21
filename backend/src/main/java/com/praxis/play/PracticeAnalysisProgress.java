package com.praxis.play;

import com.praxis.service.analysis.AnalysisProgressSink;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live progress for practice-game analysis, keyed by the archived Game id.
 *
 * In-memory and deliberately ephemeral, for the same reason
 * {@code AnalysisProgressTracker} is: this changes many times a second and is
 * worthless once the run ends. A restart mid-analysis loses the bar, not the
 * work — the game is already archived and the report gates on AnalysisStatus.
 *
 * {@code practiceExecutor} is single-threaded, so in practice there is one entry
 * at a time; the map exists so a queued second game cannot overwrite the first's
 * counts rather than to support real concurrency.
 */
@Component
public class PracticeAnalysisProgress {

    /**
     * @param stage what the engine is doing now
     * @param done  units finished in this stage
     * @param total units in this stage — always a counted total, never an estimate
     */
    public record Snapshot(Stage stage, int done, int total) {}

    public enum Stage { SWEEPING, ENRICHING, EXPLAINING }

    private final Map<UUID, Snapshot> byGame = new ConcurrentHashMap<>();

    public Optional<Snapshot> of(UUID gameId) {
        return gameId == null ? Optional.empty() : Optional.ofNullable(byGame.get(gameId));
    }

    /** A sink that records into this tracker under the given game id. */
    public AnalysisProgressSink sinkFor(UUID gameId) {
        return new AnalysisProgressSink() {
            @Override public void sweeping(int done, int total) { put(Stage.SWEEPING, done, total); }
            @Override public void enriching(int done, int total) { put(Stage.ENRICHING, done, total); }
            @Override public void explaining(int done, int total) { put(Stage.EXPLAINING, done, total); }

            @Override public void finished() { byGame.remove(gameId); }

            private void put(Stage stage, int done, int total) {
                byGame.put(gameId, new Snapshot(stage, done, total));
            }
        };
    }
}
