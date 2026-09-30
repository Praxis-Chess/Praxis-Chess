package com.praxis.evidence.diagnosis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The JSON shape of a diagnosis (§8.1), built from the verifier's own
 * vocabularies so the two cannot drift apart.
 *
 * <p>One definition for two readers: EvalCli writes it to schema.json for the
 * evaluation harness (the file PREREGISTRATION.md fingerprints), and the app
 * hands it to Ollama so the trained model can only answer in this shape
 * (Phase 9b, {@code TrainedCommentary}).
 */
public final class AnswerSchema {

    private AnswerSchema() {}

    public static Map<String, Object> jsonSchema() {
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
        return schema;
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static List<String> sorted(Set<String> s) {
        return s.stream().sorted().toList();
    }
}
