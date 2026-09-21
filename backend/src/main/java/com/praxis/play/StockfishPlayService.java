package com.praxis.play;

import com.praxis.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * A SECOND Stockfish process, dedicated to playing.
 *
 * Not a convenience. {@code StockfishService} is one process with every method
 * {@code synchronized}, running {@code Threads 6 / Hash 256} for deep analysis.
 * An opponent move has to come back in a few hundred milliseconds, and during a
 * Re-analyze All run — many depth-16 and depth-18 searches per game — it would
 * queue behind minutes of work. The board would simply appear frozen.
 *
 * So: a separate process with its own lock, configured for speed rather than
 * depth. Two threads and 64 MB against the analysis engine's six and 256, on a
 * machine with sixteen logical cores.
 */
@Service
public class StockfishPlayService {

    private static final Logger log = LoggerFactory.getLogger(StockfishPlayService.class);

    /** Stockfish's own strength dial. 0 is nearly random, 20 is full strength. */
    public static final int MIN_SKILL = 0;
    public static final int MAX_SKILL = 20;

    private final AppProperties props;
    private volatile Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private int currentSkill = -1;

    public StockfishPlayService(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        String path = props.stockfishPath();
        if (path == null || path.isBlank()) {
            log.info("[play] Stockfish not configured — Play & Improve will be unavailable");
            return;
        }
        try {
            process = new ProcessBuilder(path).redirectErrorStream(true).start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            send("uci");
            waitFor("uciok");
            // Deliberately small: this instance shares a machine with the
            // analysis engine, and latency matters more than depth here.
            send("setoption name Threads value 2");
            send("setoption name Hash value 64");
            send("isready");
            waitFor("readyok");
            currentSkill = -1;
            log.info("[play] opponent engine ready");
        } catch (Exception e) {
            log.warn("[play] opponent engine failed to start: {}", e.getMessage());
            process = null;
        }
    }

    @PreDestroy
    public void destroy() {
        if (process != null && process.isAlive()) {
            try { send("quit"); } catch (Exception ignored) { /* going away regardless */ }
            process.destroyForcibly();
        }
        process = null;
    }

    public boolean isAvailable() {
        return process != null && process.isAlive();
    }

    private void ensureAlive() {
        if (isAvailable()) return;
        log.warn("[play] opponent engine died — restarting");
        destroy();
        init();
    }

    /**
     * The engine's move for this position, in UCI, or null if it cannot play.
     *
     * @param skill    0..20, set per call because it changes between games
     * @param moveMs   thinking time. Time-bounded rather than depth-bounded on
     *                 purpose: a depth limit takes wildly different wall time in
     *                 an open middlegame than a sparse endgame, and the player
     *                 is sitting there watching.
     */
    public synchronized String bestMove(String fen, int skill, int moveMs) {
        ensureAlive();
        if (!isAvailable()) return null;

        try {
            int clamped = Math.max(MIN_SKILL, Math.min(MAX_SKILL, skill));
            if (clamped != currentSkill) {
                send("setoption name Skill Level value " + clamped);
                currentSkill = clamped;
            }
            send("position fen " + fen);
            send("go movetime " + Math.max(50, moveMs));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("bestmove")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2 && !"(none)".equals(parts[1])) return parts[1];
                    return null;
                }
            }
            return null;
        } catch (IOException e) {
            log.warn("[play] engine move failed: {}", e.getMessage());
            // Force a restart on the next call rather than limping on a dead pipe.
            process = null;
            return null;
        }
    }

    private void send(String cmd) throws IOException {
        writer.write(cmd);
        writer.newLine();
        writer.flush();
    }

    private void waitFor(String token) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith(token)) return;
        }
    }
}
