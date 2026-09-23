package com.praxis.evidence;

import com.praxis.config.AppProperties;
import com.praxis.service.analysis.StockfishService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * The deterministic Stockfish process every piece of evidence is computed on.
 *
 * <p>Separate from the shared engine the analysis pipeline uses. Evidence must be
 * reproducible — the same position searched twice has to give the same line, or a
 * stored graph cannot be rebuilt and checked — and that needs one thread and a
 * cleared hash. The pipeline's engine cannot be switched to that: every
 * evaluation it records would change with no settings version to show for it,
 * which is exactly the silent change of ruler Phase 0 exists to prevent.
 *
 * <p>Started on first use, so nobody pays for a second process unless evidence is
 * actually asked for. One instance for the evidence lab and the graph builder
 * alike, so two features cannot quietly run under different settings.
 */
@Component
public class EvidenceEngine {

    private final AppProperties props;
    private StockfishService engine;

    public EvidenceEngine(AppProperties props) {
        this.props = props;
    }

    public synchronized StockfishService get() {
        if (engine == null) {
            StockfishService started = new StockfishService(props);
            started.init();
            started.setDeterministic(true);
            engine = started;
        }
        return engine;
    }

    public synchronized boolean isAvailable() {
        return get().isAvailable();
    }

    @PreDestroy
    synchronized void close() {
        if (engine != null) engine.destroy();
    }
}
