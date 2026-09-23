package com.praxis.evidence;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.praxis.config.AppProperties;
import com.praxis.evidence.graph.EvidenceGraphBuilder;
import com.praxis.service.analysis.StockfishService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One mistake, explained from evidence the backend computed.
 *
 * <p>This is the Phase 1 model finally reading a real position instead of a
 * puzzle. It is deliberately a separate path from the analysis pipeline: the
 * pipeline's explanations are versioned by engine settings and compared against
 * a baseline, and quietly swapping the model underneath would invalidate every
 * comparison in {@code reports/baseline.md}. Nothing here writes to the
 * database.
 *
 * <p>The extra cost over ordinary analysis is one engine search per mistake —
 * the position <i>after</i> the played move, which the pipeline never searches.
 * It is timed and reported, because that number decides whether the evidence
 * graph is affordable at library scale.
 */
@Service
public class EvidenceExplainService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceExplainService.class);

    /** Enough to find the refutation and see the line; not a full analysis depth. */
    private static final int REFUTATION_DEPTH = 14;

    private final AppProperties props;
    /**
     * Built on first use, not with the bean. Constructing a JDK HttpClient opens
     * a selector, and in some environments that fails outright — which, done
     * eagerly, takes the evidence primitives down with it even for callers that
     * never ask the model anything.
     */
    private volatile HttpClient http;

    /**
     * A Stockfish process of its own, in deterministic mode.
     *
     * <p>Not the shared engine, for two reasons found by running this on real
     * games. With six threads, the same position gave a different refutation on
     * each run — `Ne5` once, `Rfd8` the next — and the evidence block, and so the
     * explanation, changed with it. Deterministic mode fixes that, and at this
     * depth is faster too. But it cannot be switched on for the shared process:
     * the analysis pipeline uses that one, and dropping its sweep to a single
     * thread would silently change every evaluation it records — a change of
     * ruler with no settings version, which is exactly what Phase 0 exists to
     * prevent. The Play feature already runs a separate engine for the same kind
     * of reason.
     *
     * <p>Owned by {@link EvidenceEngine} since Phase 3, which shares it with the
     * graph builder so the lab and stored graphs cannot run under different
     * engine settings.
     */
    private final EvidenceEngine evidenceEngine;
    private StockfishService fixedEngine;

    @Autowired
    public EvidenceExplainService(EvidenceEngine evidenceEngine, AppProperties props) {
        this.evidenceEngine = evidenceEngine;
        this.props = props;
    }

    /** For tests and offline checks: run against an engine supplied by the caller. */
    public EvidenceExplainService(StockfishService stockfish, ThreatProbe threatProbe,
                                  AppProperties props) {
        this.evidenceEngine = null;
        this.fixedEngine = stockfish;
        this.props = props;
    }

    private StockfishService engine() {
        return fixedEngine != null ? fixedEngine : evidenceEngine.get();
    }

    // The threat thresholds live in EvidenceGraphBuilder, which computes them.

    /**
     * @param block           exactly what the model was shown
     * @param explanation     what it said, or null if the model was unreachable
     * @param threat          the raw null-move probe
     * @param threatCost      pawns the player would lose by passing instead of
     *                        moving; null when the probe could not run
     * @param threatened      true when that cost reaches
     *                        {@link com.praxis.evidence.graph.EvidenceGraphBuilder#THREAT_PAWNS}
     * @param replyThreatCost the same, for the refutation specifically
     * @param threatIsReply   the refutation was already a threat before the move
     *                        (see {@code EvidenceGraphBuilder.replyIsThreat})
     * @param engineMs        time spent in the extra searches this evidence needs
     * @param modelMs         time spent in the model
     */
    public record Explained(
            int moveNumber,
            String playedSan,
            String severity,
            String block,
            String explanation,
            String modelError,
            ThreatProbe.Threat threat,
            Double threatCost,
            boolean threatened,
            Double replyThreatCost,
            boolean threatIsReply,
            String replyUci,
            List<String> tactics,
            Integer visibilityDepth,
            String visibility,
            long engineMs,
            long modelMs
    ) {}

    public Optional<Explained> explain(String fenBefore, String playedUci, String playedSan,
                                       int moveNumber, String severity, String phase,
                                       String model) {
        Board before;
        try {
            before = Attacks.at(fenBefore);
        } catch (Exception e) {
            return Optional.empty();
        }
        Side player = before.getSideToMove();

        Move played = resolve(before, playedUci, playedSan);
        if (played == null) {
            log.debug("[evidence] cannot replay {} in {}", playedSan, fenBefore);
            return Optional.empty();
        }

        Optional<Evidence> gathered = gather(fenBefore, played, playedSan, player, phase);
        if (gathered.isEmpty()) return Optional.empty();
        Evidence e = gathered.get();

        long modelStart = System.currentTimeMillis();
        String explanation = null;
        String error = null;
        try {
            explanation = generate(model, e.block());
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        long modelMs = System.currentTimeMillis() - modelStart;

        return Optional.of(new Explained(
                moveNumber, playedSan, severity, e.block(), explanation, error, e.threat(),
                round(e.threatCost()), e.threatened(), round(e.replyThreatCost()),
                e.threatIsReply(), e.replyUci(),
                e.tactics().stream().map(t -> t.kind().name()).distinct().toList(),
                e.visibilityDepth(), e.visibility(),
                e.engineMs(), modelMs));
    }

    /**
     * Everything the engine and the board say about one move, without the model.
     *
     * @param threatCost      pawns the player loses if they pass: the opponent's
     *                        best free move against the position's actual value
     * @param replyThreatCost the same, for the specific reply that refutes the
     *                        move — the §6.5 counterfactual
     */
    public record Evidence(
            String block,
            ConsequenceWalk.Consequence consequence,
            List<TacticGeometry.Tactic> tactics,
            ThreatProbe.Threat threat,
            Double threatCost,
            boolean threatened,
            Double replyThreatCost,
            boolean threatIsReply,
            String replyUci,
            Integer visibilityDepth,
            String visibility,
            long engineMs
    ) {}

    /**
     * The evidence for one move, from the graph builder.
     *
     * <p>Since Phase 3 this is a thin view over {@link EvidenceGraphBuilder}: the
     * searches, the threat and counterfactual tests and the visibility measure
     * live there, once. Two copies of logic that took two rounds of real games to
     * get right would drift the first time one of them was touched.
     */
    public Optional<Evidence> gather(String fenBefore, Move played, String playedSan,
                                     Side player, String phase) {
        var builder = new EvidenceGraphBuilder(engine(), REFUTATION_DEPTH);
        var built = builder.build(fenBefore, played.toString(), 0, phase, null);
        if (built.isEmpty()) return Optional.empty();
        var b = built.get();
        var g = b.graph();
        if (b.replyConsequence().isEmpty()) return Optional.empty();

        String block = EvidenceBlock.render(fenBefore, playedSan, player, phase,
                b.replyConsequence(), b.replyTactics());
        return Optional.of(new Evidence(block, b.replyConsequence(), b.replyTactics(), b.threat(),
                g.t1().cost(), g.t1().threatened(), g.t1().replyCost(), g.t1().replyIsThreat(),
                g.reply().uci(), g.d1().depth(), g.d1().band(), b.engineMs()));
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 100) / 100.0;
    }

    /** Prefer UCI; fall back to SAN, which is what the stored analysis holds. */
    private static Move resolve(Board board, String uci, String san) {
        for (Move move : board.legalMoves()) {
            if (uci != null && move.toString().equalsIgnoreCase(uci)) return move;
        }
        for (Move move : board.legalMoves()) {
            try {
                if (san != null && san.equals(Attacks.san(board, move))) return move;
            } catch (Exception ignored) {
                // Not this one.
            }
        }
        return null;
    }

    /**
     * Raw completion, not chat.
     *
     * <p>The Modelfile pins the template to the form the adapter was trained on;
     * going through the chat endpoint would re-wrap the prompt and the model
     * would answer with a single stop token, which looks exactly like a broken
     * export. See {@code training/reports/phase1_spike.md}.
     */
    private String generate(String model, String prompt) throws Exception {
        String body = """
                {"model":%s,"prompt":%s,"stream":false,
                 "options":{"temperature":0,"top_p":1,"num_ctx":2048,"num_predict":128}}
                """.formatted(quote(model), quote(prompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.ollama().baseUrl() + "/api/generate"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpClient client = http;
        if (client == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            http = client;
        }
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama returned " + response.statusCode()
                    + ": " + response.body());
        }
        return extract(response.body());
    }

    /** Pulls the "response" field without pulling in a JSON mapper for one key. */
    static String extract(String json) {
        int idx = json.indexOf("\"response\":\"");
        if (idx < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int i = idx + 12; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> { }
                    case 'u' -> {
                        out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> out.append(next);
                }
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
