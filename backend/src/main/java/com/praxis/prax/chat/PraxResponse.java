package com.praxis.prax.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * The model's final message. Structure, not prose-with-numbers-in-it — the
 * figures shown to the player are rendered from validated evidence, so a
 * fabricated statistic has nowhere to appear (Reasoning Plan §6).
 */
public record PraxResponse(String answer, List<Claim> evidence, List<String> factIds, String followUp) {

    /** A claim as the model asserted it, before validation. */
    public record Claim(String label, String value, String callId) {}

    /**
     * Does this reply honour the JSON contract — an `answer` plus an `evidence`
     * array? Prose answers are tolerated by parse(), but they carry no citations,
     * so the caller gets a chance to ask again before that is accepted.
     */
    public static boolean isContract(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) return false;
        String json = extractJson(raw);
        if (json == null) return false;
        try {
            JsonNode n = mapper.readTree(json);
            return !n.path("answer").asText("").isBlank() && n.path("evidence").isArray();
        } catch (Exception e) {
            return false;
        }
    }

    public static PraxResponse parse(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return new PraxResponse(null, List.of(), List.of(), null);
        }
        String json = extractJson(raw);
        if (json != null) {
            try {
                JsonNode n = mapper.readTree(json);
                List<Claim> claims = new ArrayList<>();
                for (JsonNode e : n.path("evidence")) {
                    claims.add(new Claim(
                            e.path("label").asText(null),
                            e.path("value").asText(null),
                            e.path("callId").asText(null)));
                }
                // Which verified facts to show. The model picks; the backend
                // prints them word for word, so a misreading cannot reach the
                // player — the worst it can do is pick a less relevant true one.
                List<String> factIds = new ArrayList<>();
                for (JsonNode f : n.path("facts")) {
                    String id = f.asText(null);
                    if (id != null && !id.isBlank()) factIds.add(id.trim());
                }
                String answer = n.path("answer").asText(null);
                String follow = n.path("followUp").isNull() ? null : n.path("followUp").asText(null);
                if (answer != null && !answer.isBlank()) {
                    return new PraxResponse(answer, claims, factIds, follow);
                }
            } catch (Exception ignored) {
                // fall through to prose
            }
        }
        // A small model will sometimes answer in plain prose. Keep it rather than
        // failing the turn — it just arrives with no citations, and §7.4 applies.
        // Capped, because this is also the path a runaway model lands on and an
        // answer that has already broken the contract should not also be a wall.
        return new PraxResponse(trimToAnswer(raw.trim()), List.of(), List.of(), null);
    }

    /** Off-contract prose, cut at the last sentence boundary within the cap. */
    private static String trimToAnswer(String prose) {
        final int CAP = 900;
        if (prose.length() <= CAP) return prose;
        String cut = prose.substring(0, CAP);
        int stop = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf(".\n"));
        return (stop > 200 ? cut.substring(0, stop + 1) : cut.trim()) + " …";
    }

    /** Tolerates ```json fences and leading commentary. */
    private static String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : null;
    }
}
