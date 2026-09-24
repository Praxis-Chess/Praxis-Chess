package com.praxis.evidence.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.praxis.config.AppProperties;
import com.praxis.evidence.diagnosis.Diagnosis;
import com.praxis.evidence.diagnosis.DiagnosisRules;
import com.praxis.evidence.diagnosis.DiagnosisVerifier;
import com.praxis.evidence.graph.EvidenceGraph;
import com.praxis.evidence.graph.EvidenceGraphBuilder;
import com.praxis.evidence.graph.GraphJson;
import com.praxis.evidence.graph.Representations;
import com.praxis.service.analysis.StockfishService;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The evaluation's Java half (§15): everything that must be the same code as the
 * app. Python only talks to models; it never renders evidence, never writes the
 * answer schema and never judges an answer.
 *
 * <pre>
 *   EvalCli schema   --out schema.json
 *   EvalCli fewshot  --stockfish PATH --out fewshot.jsonl
 *   EvalCli testset  --graphs graphs.jsonl --fewshot fewshot.jsonl --out testset.jsonl
 *   EvalCli verify   --testset testset.jsonl --outputs outputs.jsonl --out verified.jsonl
 * </pre>
 *
 * Every command writes to a file, never to stdout: the engine's logger prints
 * there, and a JSON file with a log line in it is a broken file.
 */
public final class EvalCli {

    private EvalCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0) usage();
        Map<String, String> o = parse(args);
        switch (args[0]) {
            case "schema" -> schema(Path.of(req(o, "out")));
            case "fewshot" -> fewshot(req(o, "stockfish"), Path.of(req(o, "out")));
            case "testset" -> testset(Path.of(req(o, "graphs")), Path.of(req(o, "fewshot")), Path.of(req(o, "out")));
            case "verify" -> verify(Path.of(req(o, "testset")), Path.of(req(o, "outputs")), Path.of(req(o, "out")));
            default -> usage();
        }
    }

    // ── schema ───────────────────────────────────────────────────────────────

    /**
     * The §8.1 answer as a JSON schema for Ollama's {@code format}, from the same
     * vocabularies the verifier enforces. Schema validity is then ~100% for every
     * system (§15.2), which is why it is reported and never claimed as a win.
     *
     * <p>Claim arguments are left as a free object: a per-type schema is a
     * grammar llama.cpp handles poorly, and the verifier checks required
     * arguments anyway (rule 1). The required arguments go to the prompt instead.
     */
    static void schema(Path out) throws IOException {
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("type", "object");
        claim.put("properties", ordered(
                "type", Map.of("type", "string", "enum", sorted(Diagnosis.CLAIM_TYPES)),
                "args", Map.of("type", "object"),
                "cites", Map.of("type", "array", "items", Map.of("type", "string")),
                "text", Map.of("type", "string")));
        claim.put("required", List.of("type", "args", "cites", "text"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        // Chain first: reason, then answer (§8.1). Property order is generation order.
        schema.put("properties", ordered(
                "reasoning_chain", Map.of("type", "array", "items", claim, "maxItems", Diagnosis.MAX_CHAIN),
                "consequence", Map.of("type", "string", "enum", sorted(Diagnosis.CONSEQUENCES)),
                "mechanism", Map.of("type", "string", "enum", sorted(Diagnosis.MECHANISMS)),
                "motif", Map.of("type", "string", "enum", sorted(Diagnosis.MOTIFS)),
                "critical_response", Map.of("type", "string"),
                "visibility", Map.of("type", "string", "enum", sorted(Diagnosis.VISIBILITIES)),
                "explanation", Map.of("type", "string")));
        schema.put("required", List.of("reasoning_chain", "consequence", "mechanism", "motif",
                "critical_response", "visibility", "explanation"));

        Map<String, Object> file = new LinkedHashMap<>();
        file.put("json_schema", schema);
        Map<String, Object> args = new LinkedHashMap<>();
        for (String t : sorted(Diagnosis.CLAIM_TYPES)) args.put(t, sorted(Diagnosis.REQUIRED_ARGS.getOrDefault(t, Set.of())));
        file.put("required_args", args);
        file.put("counterfactual_effects", sorted(Diagnosis.COUNTERFACTUAL_EFFECTS));
        file.put("max_chain", Diagnosis.MAX_CHAIN);
        Files.writeString(out, GraphJson.write(file), StandardCharsets.UTF_8);
    }

    // ── few-shot ─────────────────────────────────────────────────────────────

    /**
     * Worked examples for the prompted baselines, from textbook positions — never
     * from the player's games, which are the test set. Each is labelled by the
     * real engine and the rules, and its gold answer passes the verifier.
     * One per kind of answer: an ignored threat that mates, a piece moved into
     * attack, and a positional move where the right answer is to name no cause.
     */
    static final List<String[]> FEW_SHOT = List.of(
            new String[]{"r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3", "Nf6", "6", "OPENING", "BLUNDER"},
            new String[]{"rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2", "Qg5", "4", "OPENING", "BLUNDER"},
            new String[]{"rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2", "a6", "4", "OPENING", "INACCURACY"});

    static void fewshot(String stockfish, Path out) throws IOException {
        var engine = new StockfishService(new AppProperties(null, null,
                new AppProperties.Stockfish(stockfish), null, null, null));
        engine.init();
        if (!engine.isAvailable()) throw new IllegalStateException("no Stockfish at " + stockfish);
        engine.setDeterministic(true);
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            var builder = new EvidenceGraphBuilder(engine, EvidenceGraphBuilder.DEFAULT_DEPTH);
            for (String[] ex : FEW_SHOT) {
                var g = builder.build(ex[0], ex[1], Integer.parseInt(ex[2]), ex[3], ex[4])
                        .orElseThrow(() -> new IllegalStateException("few-shot graph failed: " + ex[1])).graph();
                Diagnosis gold = DiagnosisRules.diagnose(g);
                if (!DiagnosisVerifier.verify(g, gold, DiagnosisVerifier.Mode.CLAIM).passed()) {
                    throw new IllegalStateException("few-shot gold does not verify: " + ex[1]);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", "fewshot-" + ex[1]);
                row.put("fen_key", fenKey(ex[0]));
                row.put("renders", renders(g));
                row.put("gold", gold);
                w.write(GraphJson.write(row));
                w.newLine();
            }
        } finally {
            engine.destroy();
        }
    }

    // ── test set ─────────────────────────────────────────────────────────────

    /**
     * The player's mistakes as test items: slice C (blunders and mistakes) and C′
     * (inaccuracies, the abstention test). Each carries its four renders, the
     * rules' labels, any hand label, and the graph itself so verification needs
     * nothing else. Positions shared with a few-shot example are dropped — a model
     * shown the answer is not being tested.
     */
    static void testset(Path graphs, Path fewshotFile, Path out) throws IOException {
        Set<String> fewshotKeys = new HashSet<>();
        for (String line : Files.readAllLines(fewshotFile, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) fewshotKeys.add(GraphJson.MAPPER.readTree(line).get("fen_key").asText());
        }
        int written = 0, dropped = 0, failed = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (String line : Files.readAllLines(graphs, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode in = GraphJson.MAPPER.readTree(line);
                EvidenceGraph g;
                try {
                    g = GraphJson.readGraph(in.get("graph").toString());
                } catch (IllegalArgumentException e) {
                    failed++;
                    continue;
                }
                String key = fenKey(g.header().fen());
                if (fewshotKeys.contains(key)) {
                    dropped++;
                    continue;
                }
                String severity = text(in, "severity");
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
                row.put("id", text(in, "id"));
                row.put("slice", "INACCURACY".equals(severity) ? "C_PRIME" : "C");
                row.put("severity", severity);
                row.put("fen_key", key);
                row.put("rules", rules);
                if (in.hasNonNull("human_mechanism")) {
                    row.put("human", Map.of("consequence", text(in, "human_consequence"),
                            "mechanism", text(in, "human_mechanism")));
                }
                row.put("renders", renders(g));
                row.put("graph", GraphJson.MAPPER.readTree(GraphJson.write(g)));
                w.write(GraphJson.write(row));
                w.newLine();
                written++;
            }
        }
        System.err.printf("testset: %d items, %d dropped (few-shot position), %d unreadable%n", written, dropped, failed);
    }

    private static Map<String, Object> renders(EvidenceGraph g) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var level : Representations.Level.values()) {
            var r = Representations.render(g, level);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", r.text());
            m.put("tokens", r.tokens());
            if (level == Representations.Level.R3) {
                m.put("items", r.items());
                m.put("dropped", r.dropped());
            }
            out.put(level.name(), m);
        }
        return out;
    }

    // ── verify ───────────────────────────────────────────────────────────────

    /**
     * Every model answer, checked against the full graph (claim mode, the
     * headline), and at R3 against its citations too. Also scores the rules
     * themselves as the S_rules arm, so every system is judged by the same code.
     */
    static void verify(Path testsetFile, Path outputsFile, Path out) throws IOException {
        Map<String, JsonNode> items = new HashMap<>();
        for (String line : Files.readAllLines(testsetFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n = GraphJson.MAPPER.readTree(line);
            items.put(n.get("id").asText(), n);
        }
        int rows = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            // S_rules: the rules' own diagnosis of every item.
            for (JsonNode item : items.values()) {
                EvidenceGraph g = GraphJson.readGraph(item.get("graph").toString());
                Diagnosis d = DiagnosisRules.diagnose(g);
                w.write(GraphJson.write(result(item, "rules", "R3", g, d, true, null, 0L)));
                w.newLine();
                rows++;
            }
            if (Files.exists(outputsFile)) {
                for (String line : Files.readAllLines(outputsFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    JsonNode o = GraphJson.MAPPER.readTree(line);
                    JsonNode item = items.get(o.get("item_id").asText());
                    if (item == null) continue;
                    EvidenceGraph g = GraphJson.readGraph(item.get("graph").toString());
                    Diagnosis d = null;
                    String parseError = o.hasNonNull("error") ? o.get("error").asText() : null;
                    if (parseError == null) {
                        try {
                            d = GraphJson.readDiagnosis(o.get("raw").asText());
                        } catch (IllegalArgumentException e) {
                            parseError = "unparseable: " + e.getMessage();
                        }
                    }
                    w.write(GraphJson.write(result(item, o.get("arm").asText(), o.get("level").asText(),
                            g, d, d != null, parseError, o.path("latency_ms").asLong())));
                    w.newLine();
                    rows++;
                }
            }
        }
        System.err.printf("verified %d answers%n", rows);
    }

    private static Map<String, Object> result(JsonNode item, String arm, String level, EvidenceGraph g,
                                              Diagnosis d, boolean parsed, String error, long latencyMs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("item_id", item.get("id").asText());
        r.put("arm", arm);
        r.put("level", level);
        r.put("slice", item.get("slice").asText());
        r.put("parsed", parsed);
        if (error != null) r.put("error", error.length() > 300 ? error.substring(0, 300) : error);
        r.put("latency_ms", latencyMs);
        r.put("tokens_in", item.path("renders").path(level).path("tokens").asInt());
        if (d != null) {
            var claim = DiagnosisVerifier.verify(g, d, DiagnosisVerifier.Mode.CLAIM);
            r.put("claim_passed", claim.passed());
            r.put("claims", claim.claims());
            r.put("claims_true", claim.claimsTrue());
            List<String> violations = new ArrayList<>();
            for (var v : claim.violations()) violations.add("rule " + v.rule() + ": " + v.message());
            r.put("violations", violations);
            if ("R3".equals(level)) {
                r.put("citation_passed", DiagnosisVerifier.verify(g, d, DiagnosisVerifier.Mode.CITATION).passed());
            }
            r.put("consequence", d.consequence());
            r.put("mechanism", d.mechanism());
            r.put("motif", d.motif());
            r.put("visibility", d.visibility());
            r.put("chain_types", d.reasoningChain() == null ? List.of()
                    : d.reasoningChain().stream().map(Diagnosis.Claim::type).toList());
            r.put("explanation", d.explanation());
        }
        return r;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Piece placement and side to move: the dedupe key (§11.5). */
    static String fenKey(String fen) {
        String[] f = fen.trim().split("\\s+");
        return f.length > 1 ? f[0] + " " + f[1] : f[0];
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static List<String> sorted(Set<String> s) {
        return s.stream().sorted().toList();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
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
        System.err.println("usage: EvalCli schema|fewshot|testset|verify --out FILE [...] (see the class comment)");
        System.exit(2);
    }
}
