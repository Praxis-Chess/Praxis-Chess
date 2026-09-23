package com.praxis.evidence.graph;

import com.praxis.config.AppProperties;
import com.praxis.evidence.diagnosis.DiagnosisRules;
import com.praxis.evidence.diagnosis.DiagnosisVerifier;
import com.praxis.service.analysis.StockfishService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The evidence graph from the command line — no Spring, no database (§17.1).
 *
 * <p>This is what someone outside Praxis runs to reproduce a graph, and what the
 * public dataset's graphs can be rebuilt with. It deliberately shares every line
 * of graph code with the app; it only replaces the wiring.
 *
 * <pre>
 *   java -cp … com.praxis.evidence.graph.GraphCli \
 *        --stockfish /path/to/stockfish \
 *        --fen "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3" \
 *        --move Nf6 [--depth 14] [--level R0|R1|R2|R3|json|diagnosis]
 * </pre>
 */
public final class GraphCli {

    private GraphCli() {}

    public static void main(String[] args) {
        Map<String, String> opts = parse(args);
        String stockfish = opts.getOrDefault("stockfish", System.getenv("STOCKFISH_PATH"));
        String fen = opts.get("fen");
        String move = opts.get("move");
        if (stockfish == null || fen == null || move == null) {
            System.err.println("usage: GraphCli --stockfish PATH --fen FEN --move MOVE "
                    + "[--depth N] [--ply N] [--level R0|R1|R2|R3|json|diagnosis]");
            System.exit(2);
        }
        int depth = Integer.parseInt(opts.getOrDefault("depth", String.valueOf(EvidenceGraphBuilder.DEFAULT_DEPTH)));
        int ply = Integer.parseInt(opts.getOrDefault("ply", "0"));
        String level = opts.getOrDefault("level", "json");

        var engine = new StockfishService(new AppProperties(null, null,
                new AppProperties.Stockfish(stockfish), null, null, null));
        engine.init();
        if (!engine.isAvailable()) {
            System.err.println("could not start Stockfish at " + stockfish);
            System.exit(3);
        }
        engine.setDeterministic(true);
        try {
            var built = new EvidenceGraphBuilder(engine, depth)
                    .build(fen, move, ply, opts.getOrDefault("phase", "MIDDLEGAME"), opts.get("severity"));
            if (built.isEmpty()) {
                System.err.println("no graph: the move is not legal in that position, or the engine returned nothing");
                System.exit(4);
            }
            EvidenceGraph g = built.get().graph();
            switch (level) {
                case "json" -> System.out.println(GraphJson.write(g));
                case "diagnosis" -> {
                    var d = DiagnosisRules.diagnose(g);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("labels", DiagnosisRules.label(g));
                    out.put("diagnosis", d);
                    out.put("verification", DiagnosisVerifier.verify(g, d, DiagnosisVerifier.Mode.CITATION));
                    System.out.println(GraphJson.write(out));
                }
                default -> System.out.println(Representations.render(g, Representations.Level.valueOf(level)).text());
            }
        } finally {
            engine.destroy();
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].startsWith("--")) out.put(args[i].substring(2), args[++i]);
        }
        return out;
    }
}
