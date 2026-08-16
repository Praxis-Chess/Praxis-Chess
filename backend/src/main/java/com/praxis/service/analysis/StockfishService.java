package com.praxis.service.analysis;

import com.praxis.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class StockfishService {

    private static final Logger log = LoggerFactory.getLogger(StockfishService.class);

    private final AppProperties props;
    private volatile Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    public StockfishService(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        String path = props.stockfishPath();
        if (path.isBlank()) {
            log.info("Stockfish not configured — set praxis-chess.stockfish.path in application.yml");
            return;
        }
        try {
            process = new ProcessBuilder(path)
                    .redirectErrorStream(true)
                    .start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            send("uci");
            waitFor("uciok");
            send("setoption name Threads value 6");
            send("setoption name Hash value 256");
            send("isready");
            waitFor("readyok");
            log.info("Stockfish ready: {}", path);
        } catch (Exception e) {
            log.warn("Stockfish failed to start ({}): {} — using material fallback", path, e.getMessage());
            process = null;
        }
    }

    @PreDestroy
    public void destroy() {
        if (process != null && process.isAlive()) {
            try { send("quit"); } catch (Exception ignored) {}
            process.destroyForcibly();
        }
    }

    public boolean isAvailable() {
        return process != null && process.isAlive();
    }

    // Restarts Stockfish if the process has died.
    private void ensureAlive() {
        if (isAvailable()) return;
        log.warn("Stockfish process dead — attempting restart");
        destroy();
        init();
    }

    // Returns evaluation in pawns from White's perspective. null on failure.
    public synchronized Double evaluate(String fen) {
        ensureAlive();
        if (!isAvailable()) return null;
        try {
            send("position fen " + fen);
            send("go movetime 100");

            Double score = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("bestmove")) break;
                if (line.startsWith("info") && line.contains(" score ")) {
                    Double s = parseScore(line);
                    if (s != null) score = s;
                }
            }
            // UCI reports `score cp` from the SIDE TO MOVE's perspective, but the
            // whole pipeline assumes White's. Without this the sign flips every
            // ply, so consecutive evals show a phantom swing on every move —
            // which is what drove game accuracy down to single digits.
            return score == null ? null : (blackToMove(fen) ? -score : score);
        } catch (IOException e) {
            log.warn("Stockfish evaluation error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Evaluation at a fixed depth, single PV. In pawns, White's perspective.
     *
     * evaluate() searches for 100ms, which is right for bulk pipeline work and
     * wrong for anything compared against evaluateWithMultiPV's `go depth N`.
     * Mixing the two made every played move look as good as the engine's choice,
     * because the shallow search overstates — a comparison is only valid between
     * searches of the same depth.
     */
    public synchronized Double evaluateAtDepth(String fen, int depth) {
        ensureAlive();
        if (!isAvailable()) return null;
        try {
            send("setoption name MultiPV value 1");
            send("position fen " + fen);
            send("go depth " + depth);

            Double score = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("bestmove")) break;
                if (line.startsWith("info") && line.contains(" score ")) {
                    Double s = parseScore(line);
                    if (s != null) score = s;
                }
            }
            return score == null ? null : (blackToMove(fen) ? -score : score);
        } catch (IOException e) {
            log.warn("Stockfish depth evaluation error: {}", e.getMessage());
            return null;
        }
    }

    /** FEN field 2 is the side to move: "w" or "b". */
    private static boolean blackToMove(String fen) {
        if (fen == null) return false;
        String[] parts = fen.trim().split("\\s+");
        return parts.length > 1 && "b".equalsIgnoreCase(parts[1]);
    }

    // Returns top-N engine lines at given depth. Used for mistake candidates.
    public synchronized MultiPVResult evaluateWithMultiPV(String fen, int depth, int multiPv) {
        ensureAlive();
        if (!isAvailable()) return new MultiPVResult(0.0, null, List.of());
        try {
            send("setoption name MultiPV value " + multiPv);
            send("position fen " + fen);
            send("go depth " + depth);

            Map<Integer, String> pvByRank = new LinkedHashMap<>();
            Map<Integer, Double> scoreByRank = new LinkedHashMap<>();
            String bestMoveUci = null;
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("bestmove")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2 && !"(none)".equals(parts[1])) bestMoveUci = parts[1];
                    break;
                }
                if (line.startsWith("info") && line.contains(" multipv ") && line.contains(" pv ")) {
                    int rank = parseMultiPvRank(line);
                    if (rank > 0) {
                        pvByRank.put(rank, extractPv(line));
                        Double s = parseScore(line);
                        if (s != null) scoreByRank.put(rank, s);
                    }
                }
            }

            send("setoption name MultiPV value 1");

            // Same perspective correction as evaluate() — MultiPV scores come
            // from the side to move as well.
            double sign = blackToMove(fen) ? -1.0 : 1.0;
            double topScore = scoreByRank.getOrDefault(1, 0.0) * sign;
            return new MultiPVResult(topScore, bestMoveUci, List.copyOf(pvByRank.values()));
        } catch (IOException e) {
            log.warn("Stockfish MultiPV error: {}", e.getMessage());
            return new MultiPVResult(0.0, null, List.of());
        }
    }

    private int parseMultiPvRank(String line) {
        int idx = line.indexOf(" multipv ");
        if (idx < 0) return -1;
        int start = idx + 9;
        int end = line.indexOf(' ', start);
        try { return Integer.parseInt(end >= 0 ? line.substring(start, end) : line.substring(start)); }
        catch (NumberFormatException e) { return -1; }
    }

    private String extractPv(String line) {
        int pvIdx = line.indexOf(" pv ");
        if (pvIdx < 0) return "";
        String[] moves = line.substring(pvIdx + 4).trim().split("\\s+");
        int take = Math.min(moves.length, 4);
        return String.join(" ", Arrays.copyOf(moves, take));
    }

    private Double parseScore(String infoLine) {
        // centipawn score: "score cp 45"
        int cpIdx = infoLine.indexOf(" score cp ");
        if (cpIdx >= 0) {
            int start = cpIdx + 10;
            int end = infoLine.indexOf(' ', start);
            String val = (end >= 0 ? infoLine.substring(start, end) : infoLine.substring(start)).trim();
            try { return Integer.parseInt(val) / 100.0; } catch (NumberFormatException ignored) {}
        }
        // mate score: "score mate 3" (positive = White mates), "score mate -2" (negative = Black mates)
        int mateIdx = infoLine.indexOf(" score mate ");
        if (mateIdx >= 0) {
            int start = mateIdx + 12;
            int end = infoLine.indexOf(' ', start);
            String val = (end >= 0 ? infoLine.substring(start, end) : infoLine.substring(start)).trim();
            try {
                int m = Integer.parseInt(val);
                return m > 0 ? 100.0 : -100.0;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private void send(String cmd) throws IOException {
        writer.write(cmd);
        writer.newLine();
        writer.flush();
    }

    private void waitFor(String token) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains(token)) return;
        }
    }
}
