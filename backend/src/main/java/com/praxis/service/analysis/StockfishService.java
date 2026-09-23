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

    /** The bulk-sweep default. See {@link AnalysisProfile} for why it varies. */
    private static final int DEFAULT_SWEEP_MOVETIME_MS = 100;

    // Returns evaluation in pawns from White's perspective. null on failure.
    public Double evaluate(String fen) {
        return evaluate(fen, DEFAULT_SWEEP_MOVETIME_MS);
    }

    /**
     * As above, with the search budget named by the caller.
     *
     * Still a movetime search, so the caveat on {@link #evaluateAtDepth} holds
     * however long it runs: a movetime result and a `go depth N` result are not
     * comparable, and raising this does not make them so.
     */
    public synchronized Double evaluate(String fen, int moveTimeMs) {
        ensureAlive();
        if (!isAvailable()) return null;
        try {
            // MultiPV is process state, and evaluateWithMultiPV leaves it raised.
            // Without this the sweep inherits whatever the last enrichment set,
            // searching several lines per position to report one number — slower
            // for no gain, and slower still now that a profile can ask for four.
            send("setoption name MultiPV value 1");
            send("position fen " + fen);
            send("go movetime " + moveTimeMs);

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

    // ── evidence-graph searches ──────────────────────────────────────────────

    /**
     * One search, with everything the evidence graph needs from it.
     *
     * @param bestMoveUci the move the engine chose, or null if there is none
     * @param score       final evaluation in pawns, White's perspective
     * @param pv          the principal variation in UCI, as far as the engine reported it
     * @param depthCurve  score at each depth searched, White's perspective — the
     *                    raw material for "when did the engine first see it"
     */
    public record Search(String bestMoveUci, Double score, List<String> pv,
                         NavigableMap<Integer, Double> depthCurve) {

        public static Search empty() {
            return new Search(null, null, List.of(), new TreeMap<>());
        }
    }

    /**
     * Search a position and keep the whole depth curve, not just the answer.
     *
     * <p>Stockfish already prints an {@code info depth N score …} line every time
     * it finishes a depth; the pipeline has always thrown them away and kept the
     * last one. Reading them is free — no extra search — and it is the only way
     * to answer how hard a mistake was to see, which is the difference between
     * "you missed a one-mover" and "you missed something the engine needed depth
     * 18 to find". Those are different lessons and a report that conflates them
     * is telling a club player their oversight was careless when it was not.
     *
     * @param restrictTo if non-empty, search only these UCI moves — the
     *                   {@code searchmoves} counterfactual: "what is this reply
     *                   worth in <i>that</i> position"
     */
    public synchronized Search search(String fen, int depth, List<String> restrictTo) {
        ensureAlive();
        if (!isAvailable()) return Search.empty();
        try {
            send("setoption name MultiPV value 1");
            send("position fen " + fen);
            StringBuilder go = new StringBuilder("go depth ").append(depth);
            if (restrictTo != null && !restrictTo.isEmpty()) {
                go.append(" searchmoves");
                for (String move : restrictTo) go.append(' ').append(move);
            }
            send(go.toString());

            boolean flip = blackToMove(fen);
            NavigableMap<Integer, Double> curve = new TreeMap<>();
            String bestMoveUci = null;
            Double score = null;
            List<String> pv = List.of();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("bestmove")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2 && !"(none)".equals(parts[1])) bestMoveUci = parts[1];
                    break;
                }
                if (!line.startsWith("info") || !line.contains(" score ")) continue;
                // Skip the periodic "currmove" progress lines: they carry a depth
                // but no evaluation of the position, and folding them into the
                // curve would record whatever the previous depth had found.
                if (line.contains(" currmove ")) continue;

                Double raw = parseScore(line);
                if (raw == null) continue;
                double whiteView = flip ? -raw : raw;
                score = whiteView;

                int d = parseIntField(line, " depth ");
                if (d > 0) curve.put(d, whiteView);

                int pvIdx = line.indexOf(" pv ");
                if (pvIdx >= 0) {
                    pv = List.of(line.substring(pvIdx + 4).trim().split("\\s+"));
                }
            }
            return new Search(bestMoveUci, score, pv, curve);
        } catch (IOException e) {
            log.warn("Stockfish search error: {}", e.getMessage());
            return Search.empty();
        }
    }

    public Search search(String fen, int depth) {
        return search(fen, depth, List.of());
    }

    /**
     * Fix the engine so the same position searched twice gives the same answer.
     *
     * <p>A multi-threaded search is not reproducible: helper threads finish in
     * whatever order the scheduler gives them, so the same position at the same
     * depth can return a different move between runs. Neither is a warm
     * transposition table — a position reached after a previous search is
     * evaluated with knowledge the same position has not got on a cold engine.
     *
     * <p>Both are fine for a one-off analysis and neither is acceptable for
     * evidence. A graph that cannot be rebuilt identically cannot be verified,
     * and an experiment whose inputs move under it measures nothing. This is
     * slower, and that is the price.
     */
    public synchronized void setDeterministic(boolean deterministic) {
        ensureAlive();
        if (!isAvailable()) return;
        try {
            send("setoption name Threads value " + (deterministic ? 1 : 6));
            send("ucinewgame");
            send("isready");
            waitFor("readyok");
            this.deterministic = deterministic;
            log.info("Stockfish deterministic mode: {}", deterministic);
        } catch (IOException e) {
            log.warn("Stockfish could not switch determinism: {}", e.getMessage());
        }
    }

    public boolean isDeterministic() {
        return deterministic;
    }

    /**
     * Clear the transposition table. In deterministic mode this must happen
     * before each search, or the answer depends on what was searched before it.
     */
    public synchronized void clearHash() {
        ensureAlive();
        if (!isAvailable()) return;
        try {
            send("ucinewgame");
            send("isready");
            waitFor("readyok");
        } catch (IOException e) {
            log.warn("Stockfish could not clear hash: {}", e.getMessage());
        }
    }

    private volatile boolean deterministic = false;

    /** Reads the integer following a UCI field label, or -1. */
    private static int parseIntField(String line, String label) {
        int idx = line.indexOf(label);
        if (idx < 0) return -1;
        int start = idx + label.length();
        int end = line.indexOf(' ', start);
        try {
            return Integer.parseInt(end >= 0 ? line.substring(start, end) : line.substring(start));
        } catch (NumberFormatException e) {
            return -1;
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
