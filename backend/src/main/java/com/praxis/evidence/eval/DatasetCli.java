package com.praxis.evidence.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.praxis.config.AppProperties;
import com.praxis.evidence.diagnosis.Diagnosis;
import com.praxis.evidence.diagnosis.DiagnosisRules;
import com.praxis.evidence.diagnosis.DiagnosisVerifier;
import com.praxis.evidence.graph.EvidenceGraph;
import com.praxis.evidence.graph.EvidenceGraphBuilder;
import com.praxis.evidence.graph.GraphJson;
import com.praxis.evidence.graph.Representations;
import com.praxis.service.analysis.AnalysisProfile;
import com.praxis.service.analysis.CandidateMove;
import com.praxis.service.analysis.MistakeCandidateFilter;
import com.praxis.service.analysis.ParsedGame;
import com.praxis.service.analysis.PgnParserService;
import com.praxis.service.analysis.PositionEvaluator;
import com.praxis.service.analysis.StockfishService;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 6's Java half: the training data is built by the app's own code (§11,
 * §17.3), exactly as the Phase 5 test set was.
 *
 * <pre>
 *   DatasetCli graphs --stockfish SF --in candidates.tsv --out graphs.jsonl
 *   DatasetCli corpus --stockfish SF --pgn games.pgn     --out graphs.jsonl
 *   DatasetCli gold   --in a.jsonl,b.jsonl               --out gold.jsonl
 * </pre>
 *
 * <b>graphs</b> builds evidence for slice A (puzzles): one row per candidate.
 * <b>corpus</b> is slice B: public games run through Praxis's own parser,
 * engine sweep and {@link MistakeCandidateFilter}, so the mistakes it finds are
 * the ones the app would flag, with the app's thresholds. It never touches the
 * database: public games stay out of the player's library by construction.
 * <b>gold</b> renders R0–R3 and writes the rules' diagnosis as the target where
 * the rules can write one (single cause, or nothing concrete), verified; the
 * composite subset is marked for the teacher (§11.3).
 *
 * <p>graphs and corpus are long runs, so both resume: rows already written are
 * skipped, and {@code <out>.progress.json} is rewritten after every item for
 * {@code training/tools/phase6_status.py}.
 */
public final class DatasetCli {

    private DatasetCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0) usage();
        Map<String, String> o = parse(args);
        switch (args[0]) {
            case "graphs" -> graphs(req(o, "stockfish"), Path.of(req(o, "in")), Path.of(req(o, "out")));
            case "corpus" -> corpus(req(o, "stockfish"), Path.of(req(o, "pgn")), Path.of(req(o, "out")));
            case "gold" -> gold(req(o, "in"), Path.of(req(o, "out")));
            default -> usage();
        }
    }

    // ── slice A: puzzles ─────────────────────────────────────────────────────

    /**
     * One graph per candidate row. TSV columns, with a header line:
     * id, slice, group, fen, move, ply, phase, severity.
     */
    static void graphs(String stockfish, Path in, Path out) throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(in, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isBlank()) rows.add(line.split("\t", -1));
        }
        Set<String> done = doneIds(out);
        Progress progress = new Progress(out, "slice A graphs", rows.size(), done.size());
        StockfishService engine = engine(stockfish, true);
        try (BufferedWriter w = append(out)) {
            var builder = new EvidenceGraphBuilder(engine, EvidenceGraphBuilder.DEFAULT_DEPTH);
            for (String[] r : rows) {
                if (done.contains(r[0])) continue;
                var built = builder.build(r[3], r[4], Integer.parseInt(r[5]), r[6], r[7]);
                if (built.isPresent()) {
                    w.write(GraphJson.write(row(r[0], r[1], r[2], r[7], built.get().graph())));
                    w.newLine();
                    w.flush();
                } else {
                    progress.failed++;
                }
                progress.step(r[0]);
            }
        } finally {
            engine.destroy();
        }
        progress.finish();
    }

    // ── slice B: public games through the app's own mistake finder ──────────

    /**
     * Games separated by blank lines before each "[Event" header. Each game is
     * swept once and filtered twice, once for each side, so both players'
     * mistakes count; the scrub step renames the players "W" and "B", which is
     * how each side is selected.
     */
    static void corpus(String stockfish, Path pgnFile, Path out) throws IOException {
        List<String> games = splitPgn(Files.readString(pgnFile, StandardCharsets.UTF_8));
        Path doneFile = Path.of(out + ".games-done");
        Set<String> doneGames = Files.exists(doneFile)
                ? new HashSet<>(Files.readAllLines(doneFile, StandardCharsets.UTF_8)) : new HashSet<>();
        Progress progress = new Progress(out, "slice B games", games.size(), doneGames.size());

        // Two engines, as in the app: the sweep uses the ordinary multi-threaded
        // engine (the pipeline's), the evidence a separate deterministic one.
        StockfishService sweepEngine = engine(stockfish, false);
        StockfishService evidenceEngine = engine(stockfish, true);
        var parser = new PgnParserService();
        var evaluator = new PositionEvaluator(sweepEngine);
        var filter = new MistakeCandidateFilter();
        var builder = new EvidenceGraphBuilder(evidenceEngine, EvidenceGraphBuilder.DEFAULT_DEPTH);
        int sweepMs = AnalysisProfile.LIBRARY.sweepMoveTimeMs();

        try (BufferedWriter w = append(out); BufferedWriter d = append(doneFile)) {
            for (String pgn : games) {
                String gameId = header(pgn, "LichessId");
                if (gameId == null || doneGames.contains(gameId)) continue;
                ParsedGame asWhite = parser.parse(gameId, pgn, "W");
                if (asWhite.moves().isEmpty()) {
                    progress.failed++;
                } else {
                    List<Double> scores = evaluator.evaluateAll(asWhite.moves(), sweepMs);
                    ParsedGame asBlack = parser.parse(gameId, pgn, "B");
                    List<CandidateMove> found = new ArrayList<>(filter.filterCandidates(asWhite, scores));
                    found.addAll(filter.filterCandidates(asBlack, scores));
                    for (CandidateMove c : found) {
                        var built = builder.build(c.move().fenBefore(), c.move().san(), c.move().moveNumber(),
                                c.phase().name(), c.severity().name());
                        if (built.isEmpty()) continue;
                        String id = "lichess:game:" + gameId + "#" + c.move().moveNumber();
                        w.write(GraphJson.write(row(id, "B", gameId, c.severity().name(), built.get().graph())));
                        w.newLine();
                    }
                    w.flush();
                }
                d.write(gameId);
                d.newLine();
                d.flush();
                progress.step(gameId);
            }
        } finally {
            sweepEngine.destroy();
            evidenceEngine.destroy();
        }
        progress.finish();
    }

    static List<String> splitPgn(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            // Every game opens with its [Event] header; that line closes the one before.
            if (line.startsWith("[Event ") && !cur.toString().isBlank()) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    private static String header(String pgn, String name) {
        var m = java.util.regex.Pattern.compile("\\[" + name + " \"([^\"]*)\"]").matcher(pgn);
        return m.find() ? m.group(1) : null;
    }

    // ── gold: renders + the rules' target, verified ─────────────────────────

    /**
     * For every graph row: the four renders, the rules' labels, and the target.
     *
     * <p>Single-cause and nothing-concrete mistakes get the rules' own diagnosis,
     * which is correct by construction and verified here anyway (§11.3). The
     * composite subset needs a chain the rules refuse to write, so it is marked
     * {@code needs_teacher} and carries no target yet.
     */
    static void gold(String inputs, Path out) throws IOException {
        int rows = 0, verified = 0, teacher = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (String file : inputs.split(",")) {
                for (String line : Files.readAllLines(Path.of(file.trim()), StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    JsonNode in = GraphJson.MAPPER.readTree(line);
                    EvidenceGraph g = GraphJson.readGraph(in.get("graph").toString());
                    var labels = DiagnosisRules.label(g);

                    Map<String, Object> rules = new LinkedHashMap<>();
                    rules.put("consequence", labels.consequence());
                    rules.put("mechanism", labels.mechanism());
                    rules.put("fired", labels.fired());
                    rules.put("composite", labels.composite());
                    rules.put("single_cause", labels.singleCause());
                    rules.put("motif", labels.motif());
                    rules.put("visibility", labels.visibility());

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", in.get("id").asText());
                    row.put("slice", in.get("slice").asText());
                    row.put("group", in.get("group").asText());
                    row.put("severity", in.path("severity").asText(null));
                    row.put("fen_key", EvalCli.fenKey(g.header().fen()));
                    row.put("rules", rules);
                    Map<String, Object> renders = new LinkedHashMap<>();
                    for (var level : Representations.Level.values()) {
                        var r = Representations.render(g, level);
                        renders.put(level.name(), Map.of("text", r.text(), "tokens", r.tokens()));
                    }
                    row.put("renders", renders);

                    if (labels.composite()) {
                        row.put("needs_teacher", true);
                        teacher++;
                    } else {
                        Diagnosis d = DiagnosisRules.diagnose(g);
                        boolean ok = DiagnosisVerifier.verify(g, d, DiagnosisVerifier.Mode.CLAIM).passed()
                                && DiagnosisVerifier.verify(g, d, DiagnosisVerifier.Mode.CITATION).passed();
                        row.put("gold", d);
                        row.put("gold_verified", ok);
                        if (ok) verified++;
                    }
                    row.put("graph", in.get("graph"));
                    w.write(GraphJson.write(row));
                    w.newLine();
                    rows++;
                }
            }
        }
        System.err.printf("gold: %d rows, %d with a verified rules target, %d composite (for the teacher)%n",
                rows, verified, teacher);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> row(String id, String slice, String group, String severity, EvidenceGraph g) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("slice", slice);
        r.put("group", group);
        r.put("severity", severity);
        r.put("graph", GraphJson.MAPPER.valueToTree(g));
        return r;
    }

    private static StockfishService engine(String path, boolean deterministic) {
        var engine = new StockfishService(new AppProperties(null, null,
                new AppProperties.Stockfish(path), null, null, null));
        engine.init();
        if (!engine.isAvailable()) throw new IllegalStateException("no Stockfish at " + path);
        engine.setDeterministic(deterministic);
        return engine;
    }

    private static Set<String> doneIds(Path out) throws IOException {
        Set<String> ids = new HashSet<>();
        if (!Files.exists(out)) return ids;
        for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) ids.add(GraphJson.MAPPER.readTree(line).get("id").asText());
        }
        return ids;
    }

    private static BufferedWriter append(Path p) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        return Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Rewrites {@code <out>.progress.json}; the status tool reads it. */
    static final class Progress {
        final Path file;
        final String what;
        final int total;
        int done;
        int failed;
        final long started = System.currentTimeMillis();
        final int doneAtStart;

        Progress(Path out, String what, int total, int done) {
            this.file = Path.of(out + ".progress.json");
            this.what = what;
            this.total = total;
            this.done = done;
            this.doneAtStart = done;
            write("starting");
        }

        void step(String current) {
            done++;
            write(current);
        }

        void finish() {
            write("finished");
        }

        private void write(String current) {
            long ms = System.currentTimeMillis() - started;
            int thisRun = done - doneAtStart;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("what", what);
            m.put("total", total);
            m.put("done", done);
            m.put("failed", failed);
            m.put("current", current);
            m.put("seconds_per_item", thisRun > 0 ? Math.round(ms / 100.0 / thisRun) / 10.0 : null);
            m.put("updated", java.time.LocalDateTime.now().withNano(0).toString());
            try {
                Files.writeString(file, GraphJson.write(m), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // progress is a convenience; never fail the run over it
            }
        }
    }

    private static String req(Map<String, String> o, String key) {
        String v = o.get(key);
        if (v == null) usage();
        return v;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i + 1 < args.length; i++) {
            if (args[i].startsWith("--")) out.put(args[i].substring(2), args[++i]);
        }
        return out;
    }

    private static void usage() {
        System.err.println("usage: DatasetCli graphs|corpus|gold ... (see the class comment)");
        System.exit(2);
    }
}
