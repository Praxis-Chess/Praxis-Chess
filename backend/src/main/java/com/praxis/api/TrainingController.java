package com.praxis.api;

import com.praxis.config.AppProperties;
import com.praxis.domain.MistakeEvidence;
import com.praxis.evidence.EvidenceEngine;
import com.praxis.evidence.diagnosis.Diagnosis;
import com.praxis.evidence.diagnosis.DiagnosisRules;
import com.praxis.evidence.diagnosis.DiagnosisVerifier;
import com.praxis.evidence.graph.EvidenceGraph;
import com.praxis.evidence.graph.EvidenceGraphBuilder;
import com.praxis.evidence.graph.GraphJson;
import com.praxis.evidence.graph.Representations;
import com.praxis.repository.MistakeEvidenceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The endpoints the Python training pipeline calls (§17.3): render evidence at
 * any level, verify a diagnosis, export stored graphs with their labels.
 *
 * <p><b>Why Python calls Java instead of reimplementing.</b> One renderer and one
 * verifier means the text a model is trained on is the text it is served, and the
 * filter that cleans training data is the check that guards production. Two
 * implementations of either would drift, and the drift would be invisible.
 *
 * <p>Off unless {@code praxis-chess.training.enabled=true}: with the property
 * unset these endpoints do not exist, rather than existing and refusing. They
 * are for a development machine, and the repository is public.
 */
@RestController
@RequestMapping("/api/admin/training")
@ConditionalOnProperty(prefix = "praxis-chess.training", name = "enabled", havingValue = "true")
public class TrainingController {

    private final EvidenceEngine engine;
    private final MistakeEvidenceRepository evidence;
    private final AppProperties props;

    public TrainingController(EvidenceEngine engine, MistakeEvidenceRepository evidence, AppProperties props) {
        this.engine = engine;
        this.evidence = evidence;
        this.props = props;
    }

    public record RenderRequest(String fen, String move, String level, Integer ply, String phase) {}

    /** Build the graph for any position + move, and render it at the requested level. */
    @PostMapping("/render")
    public ResponseEntity<Map<String, Object>> render(@RequestBody RenderRequest req) {
        var built = new EvidenceGraphBuilder(engine.get(), EvidenceGraphBuilder.DEFAULT_DEPTH)
                .build(req.fen(), req.move(), req.ply() == null ? 0 : req.ply(),
                        req.phase() == null ? "MIDDLEGAME" : req.phase(), null);
        if (built.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no graph for that position and move"));
        }
        EvidenceGraph g = built.get().graph();
        var level = Representations.Level.valueOf(req.level() == null ? "R3" : req.level());
        var rendered = Representations.render(g, level);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graph", g);
        out.put("rendered", rendered);
        out.put("labels", DiagnosisRules.label(g));
        out.put("rules_diagnosis", DiagnosisRules.diagnose(g));
        return ResponseEntity.ok(out);
    }

    public record VerifyRequest(EvidenceGraph graph, Diagnosis diagnosis, String mode) {}

    @PostMapping("/verify")
    public DiagnosisVerifier.Report verify(@RequestBody VerifyRequest req) {
        var mode = DiagnosisVerifier.Mode.valueOf(req.mode() == null ? "CLAIM" : req.mode());
        return DiagnosisVerifier.verify(req.graph(), req.diagnosis(), mode);
    }

    /**
     * Stored graphs with rule and human labels, one JSON object per line.
     *
     * <p>These are the player's own games — slices C and C′ of §11.2 — and never
     * leave this machine; the public dataset is built from Lichess data only.
     */
    @GetMapping(value = "/export", produces = "application/x-ndjson")
    public ResponseEntity<String> export() {
        StringBuilder out = new StringBuilder();
        for (MistakeEvidence e : evidence.findByUsername(props.chessCom().username())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("graph", GraphJson.readGraph(e.getGraphJson()));
            row.put("rule_consequence", e.getRuleConsequence());
            row.put("rule_mechanisms", e.getRuleMechanisms());
            row.put("rule_mechanism", e.getRuleMechanism());
            row.put("rule_composite", e.isRuleComposite());
            row.put("rule_diagnosis", GraphJson.readDiagnosis(e.getRuleDiagnosisJson()));
            row.put("human_consequence", e.getHumanConsequence());
            row.put("human_mechanism", e.getHumanMechanism());
            row.put("analysis_settings_id", e.getAnalysisSettingsId());
            out.append(GraphJson.write(row)).append('\n');
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-ndjson")).body(out.toString());
    }
}
