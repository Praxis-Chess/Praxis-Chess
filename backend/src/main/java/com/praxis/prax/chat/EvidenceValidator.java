package com.praxis.prax.chat;

import com.praxis.prax.evidence.Evidence;
import com.praxis.prax.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reasoning Plan §7 — the mechanism, not the instruction.
 *
 * A prompt asking the model not to invent statistics is a request. This is what
 * makes it enforceable: a claim whose callId does not resolve to a tool result
 * from THIS turn never reaches the player.
 */
final class EvidenceValidator {

    private static final Logger log = LoggerFactory.getLogger(EvidenceValidator.class);

    private EvidenceValidator() {}

    static List<Evidence> validate(List<PraxResponse.Claim> claims, Map<String, ToolResult> results) {
        List<Evidence> out = new ArrayList<>();
        int dropped = 0;

        for (var c : claims) {
            if (c.label() == null || c.value() == null) continue;

            // §7.1 — citation resolution. No resolvable source, no claim.
            ToolResult src = c.callId() == null ? null : results.get(c.callId());
            if (src == null) src = byToolName(c.callId(), results);
            if (src == null) {
                log.debug("[prax] dropped uncited claim: {} = {} (callId={})",
                        c.label(), c.value(), c.callId());
                dropped++;
                continue;
            }

            // A tool that errored cannot support anything.
            if (src.data() instanceof Map<?, ?> m && m.containsKey("error")) {
                log.debug("[prax] dropped claim citing failed tool {}", src.tool());
                continue;
            }

            // §7.3 — a player-data claim is a measurement, so it must carry a
            // figure. "Philidor Defense: C41" is a subject wearing evidence's
            // clothes. Engine claims are exempt: "forced mate" is a verdict the
            // engine actually returned, and there is no number that says it.
            if (src.provenance() == Evidence.Provenance.PLAYER_DATA
                    && !c.value().matches(".*\\d.*")) {
                log.debug("[prax] dropped non-quantitative player-data claim: {} = {}",
                        c.label(), c.value());
                dropped++;
                continue;
            }

            var ev = new Evidence(c.label().trim(), c.value().trim(),
                    src.sampleSize(), src.provenance(), c.callId());

            // §7.2 — a confident figure resting on three games is noise. Keep it
            // but make the thinness visible rather than silently discarding it.
            if (ev.isUnderpowered()) {
                out.add(new Evidence(ev.label(), ev.value() + " (only " + ev.sampleSize() + " games)",
                        ev.sampleSize(), ev.source(), ev.toolCallId()));
            } else {
                out.add(ev);
            }
        }

        // An empty evidence list looks identical whether the model cited nothing
        // or cited everything wrongly. They need opposite fixes, so say which.
        if (out.isEmpty()) {
            if (claims.isEmpty()) {
                log.warn("[prax] model returned no evidence entries at all");
            } else {
                log.warn("[prax] all {} evidence claims were dropped; ids seen: {}, ids valid: {}",
                        dropped, claims.stream().map(PraxResponse.Claim::callId).toList(),
                        results.keySet());
            }
        }
        return out;
    }

    /**
     * A small model often cites the tool by name ("get_mistake_patterns") rather
     * than by callId. That is still a real reference, so honour it — but only
     * when exactly one call used that tool, or the provenance attached to the
     * claim would be a guess.
     */
    private static ToolResult byToolName(String ref, Map<String, ToolResult> results) {
        if (ref == null || ref.isBlank()) return null;
        ToolResult found = null;
        for (ToolResult r : results.values()) {
            if (!ref.equals(r.tool())) continue;
            if (found != null) return null;   // ambiguous
            found = r;
        }
        return found;
    }
}
