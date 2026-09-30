package com.praxis.service.diagnosis;

import com.praxis.config.AppProperties;
import com.praxis.evidence.diagnosis.AnswerSchema;
import com.praxis.evidence.diagnosis.Diagnosis;
import com.praxis.evidence.diagnosis.DiagnosisVerifier;
import com.praxis.evidence.graph.EvidenceGraph;
import com.praxis.evidence.graph.GraphJson;
import com.praxis.evidence.graph.Representations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 9b: the trained model writes the "AI commentary", and the verifier
 * checks every claim of it before anyone sees it.
 *
 * <p>The pre-registered comparison shipped the rules (training/reports/grid_v1.md):
 * the verified headline on every mistake is theirs. This is a product choice
 * made after it: the trained 2B-R3 replaces qwen2.5's loose commentary, because
 * on the player's own mistakes it stated 100% true claims to qwen's 9–50%, in
 * 4.6 s to ~12 s. It is asked exactly as it was trained and examined:
 * <ul>
 *   <li>the dataset's system message, then the R3 render of the same graph
 *       (the renderer the training data came from), then an assistant turn
 *       whose thinking block is already closed;</li>
 *   <li>greedy and seeded, constrained to the answer schema.</li>
 * </ul>
 * An answer that fails the verifier (claim mode, against the full graph) is
 * kept for the record and never shown.
 */
@Component
public class TrainedCommentary {

    private static final Logger log = LoggerFactory.getLogger(TrainedCommentary.class);

    /** The training rows' system message, verbatim (training/src/praxis_train/build_dataset6.py). */
    static final String SYSTEM = "You are Prax. Diagnose the mistake using ONLY the evidence given. "
            + "Every claim must be typed. Output JSON.";

    public record Result(String explanation, String motif, boolean verified, String model, long millis) {}

    private final String model;
    private final RestClient ollama;

    public TrainedCommentary(AppProperties props) {
        var o = props.ollama();
        this.model = o == null || o.commentaryModel() == null || o.commentaryModel().isBlank() ? null : o.commentaryModel();
        var timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofMinutes(3));     // a cold model load, then up to 1,000 tokens
        this.ollama = RestClient.builder()
                .baseUrl(o == null || o.baseUrl() == null ? "http://localhost:11434" : o.baseUrl())
                .requestFactory(timeouts)
                .build();
    }

    public boolean enabled() {
        return model != null;
    }

    public String model() {
        return model;
    }

    /** The exact prompt format the adapter was trained on (ChatML, thinking closed). */
    static String prompt(EvidenceGraph g) {
        String evidence = Representations.render(g, Representations.Level.R3).text();
        return "<|im_start|>system\n" + SYSTEM + "<|im_end|>\n"
                + "<|im_start|>user\n" + evidence + "<|im_end|>\n"
                + "<|im_start|>assistant\n<think>\n\n</think>\n\n";
    }

    /** Empty when commentary is off or the model could not be reached or read. */
    public Optional<Result> write(EvidenceGraph g) {
        if (!enabled()) return Optional.empty();
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "prompt", prompt(g),
                    "raw", true,
                    "stream", false,
                    "format", AnswerSchema.jsonSchema(),
                    "keep_alive", "30m",
                    "options", Map.of("temperature", 0, "top_p", 1, "seed", 0, "num_ctx", 3072,
                            "num_predict", 1000, "stop", List.of("<|im_end|>")));
            @SuppressWarnings("unchecked")
            Map<String, Object> reply = ollama.post().uri("/api/generate").body(body).retrieve().body(Map.class);
            String text = reply == null ? null : (String) reply.get("response");
            if (text == null || text.isBlank()) return Optional.empty();
            Diagnosis d = GraphJson.readDiagnosis(text);
            boolean passed = DiagnosisVerifier.verify(g, d, DiagnosisVerifier.Mode.CLAIM).passed();
            return Optional.of(new Result(d.explanation(), d.motif(), passed, model,
                    System.currentTimeMillis() - started));
        } catch (Exception e) {
            log.warn("[commentary] {} could not write for ply {}: {}", model, g.header().ply(), e.getMessage());
            return Optional.empty();
        }
    }
}
