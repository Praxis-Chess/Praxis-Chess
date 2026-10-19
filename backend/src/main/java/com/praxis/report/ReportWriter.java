package com.praxis.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.ai.CompletionRequest;
import com.praxis.ai.Feature;
import com.praxis.ai.ModelRouter;
import com.praxis.ai.OutputBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Puts computed facts into words (Phase 12, steps 3 and 4).
 *
 * <p>The model receives the facts and writes short paragraphs around them;
 * {@link ProseChecker} holds every paragraph to those facts. One retry, with the
 * failures listed. If the second answer still fails, no prose is shown: the page
 * renders the facts alone, with a note. A wrong report is never shown.
 *
 * <p>Pass and fail are recorded with each report ({@link Prose#status()}), which
 * is what the step-7 comparison measures.
 */
@Service
public class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);

    /**
     * @param status     checked: every paragraph passed; rejected: still failing after the retry;
     *                   unavailable: no usable answer from the model
     * @param text       the paragraphs, only when checked
     * @param violations what the last failing answer got wrong
     * @param lastAnswer  the model's last answer as it came, checked or not: only for
     *                    the step-7 comparison, never shown on a report
     */
    public record Prose(String status, String model, Map<String, Object> text, List<String> violations,
                        int attempts, long millis, Map<String, Object> lastAnswer) {
        public static final String CHECKED = "checked";
        public static final String REJECTED = "rejected";
        public static final String UNAVAILABLE = "unavailable";

        /** Prose switched off (praxis-chess.reports.prose): no model call, nothing to show. */
        public static Prose off() {
            return new Prose(null, null, null, List.of(), 0, 0, null);
        }
    }

    private final ModelRouter router;
    private final ObjectMapper json;

    public ReportWriter(ModelRouter router, ObjectMapper json) {
        this.router = router;
        this.json = json;
    }

    public Prose writeReport(ReportFacts facts) {
        return writeReport(facts, null);
    }

    /**
     * As above, with another local model than the configured one: the step-7
     * comparison runs the same facts through each candidate writer.
     */
    public Prose writeReport(ReportFacts facts, String model) {
        String prompt = """
            You write the short paragraphs of a chess player's Pattern Report.
            Every figure is already computed and shown on the page. Your words go around them.

            Rules:
            - Use only the facts below. Never add a number, a percentage, a tactic or a game that is not in them.
            - Quote numbers exactly as they appear in the facts. Do not round, add up or work out new ones.
            - "positional" and "other" are not tactics: never call them one.
            - A tactic was used against the player or missed, never "made": write "forks caught you",
              not "you make forks".
            - Say nothing about wins, losses, results or rating: the facts contain none.
            - Plain, direct, encouraging. Second person ("you"). No headings, no lists, no markdown.

            Facts:
            %s

            Answer with this JSON only:
            {
              "summary": "2 or 3 sentences: what stands out most, and where in the game it happens",
              "weaknesses": ["one or two sentences for each entry of weaknesses_in_order, in that order"],
              "openings": "one sentence about the openings, or an empty string if there are none"
            }
            """.formatted(toJson(reportView(facts)));
        int expected = facts.topCauses().size();
        return write(route(model), prompt, text -> {
            List<String> problems = new ArrayList<>();
            List<String> paragraphs = paragraphs(text, expected, problems);
            for (String p : paragraphs) problems.addAll(ProseChecker.check(p, facts).violations());
            return problems;
        });
    }

    public Prose writePlan(ReportFacts facts, PlanFacts plan) {
        String prompt = """
            You write the short paragraphs of a chess player's Training Plan.
            The plan is already decided: which weaknesses, in what order, and what to drill. Do not change it.
            Your job is to explain it.

            Rules:
            - Use only the facts below. Never add a number, a tactic, an opening or a game that is not in them.
            - Quote numbers exactly. Do not round, add up or work out new ones.
            - A tactic was used against the player or missed, never "made": write "forks caught you",
              not "you make forks".
            - Say nothing about wins, losses, results or rating: the facts contain none.
            - Do not repeat each priority's "drill": the page shows it. Say why it comes at this place.
            - Plain, direct, encouraging. Second person ("you"). No headings, no lists, no markdown.

            Plan:
            %s

            Answer with this JSON only:
            {
              "intro": "2 sentences: what the plan is for and how the week is organised",
              "priorities": ["one or two sentences for each priority, in order: why it comes at this place"]
            }
            """.formatted(toJson(planView(plan)));
        int expected = plan.priorities().size();
        return write(route(null), prompt, text -> {
            List<String> problems = new ArrayList<>();
            List<String> paragraphs = new ArrayList<>();
            String intro = text.path("intro").asText("");
            if (intro.isBlank()) problems.add("\"intro\" is missing");
            paragraphs.add(intro);
            JsonNode ps = text.path("priorities");
            if (!ps.isArray() || ps.size() != expected) {
                problems.add("\"priorities\" must have exactly " + expected + " entries");
            } else {
                ps.forEach(p -> paragraphs.add(p.asText("")));
            }
            for (String p : paragraphs) problems.addAll(ProseChecker.checkPlan(p, facts, plan).violations());
            return problems;
        });
    }

    /** One attempt and, on failure, one retry that lists what was wrong. */
    private ModelRouter.Route route(String model) {
        ModelRouter.Route configured = router.forFeature(Feature.REPORTS);
        if (model == null || model.isBlank()) return configured;
        return new ModelRouter.Route(Feature.REPORTS, configured.provider(), model, configured.cloud());
    }

    @SuppressWarnings("unchecked")
    private Prose write(ModelRouter.Route route, String prompt, Function<JsonNode, List<String>> check) {
        long started = System.currentTimeMillis();
        List<String> violations = List.of();
        Map<String, Object> last = null;
        String ask = prompt;
        for (int attempt = 1; attempt <= 2; attempt++) {
            JsonNode answer;
            try {
                String raw = route.provider().completeWithRetry(
                        CompletionRequest.structured(route.model(), ask, OutputBudget.REPORT));
                answer = json.readTree(raw.replaceAll("```json", "").replaceAll("```", "").trim());
            } catch (Exception e) {
                log.warn("[report] no usable answer from {}: {}", route.model(), e.getMessage());
                return new Prose(Prose.UNAVAILABLE, route.model(), null, List.of(String.valueOf(e.getMessage())),
                        attempt, System.currentTimeMillis() - started, last);
            }
            last = answer.isObject() ? json.convertValue(answer, LinkedHashMap.class) : null;
            violations = check.apply(answer);
            if (violations.isEmpty()) {
                log.info("[report] prose from {} passed the checker on attempt {}", route.model(), attempt);
                return new Prose(Prose.CHECKED, route.model(), last, List.of(), attempt,
                        System.currentTimeMillis() - started, last);
            }
            log.info("[report] prose from {} failed the checker on attempt {}: {}", route.model(), attempt, violations);
            ask = prompt + "\nYour previous answer was rejected because "
                    + String.join("; ", violations) + ". Write it again, following the rules exactly.\n";
        }
        return new Prose(Prose.REJECTED, route.model(), null, violations, 2, System.currentTimeMillis() - started, last);
    }

    private static List<String> paragraphs(JsonNode text, int expectedWeaknesses, List<String> problems) {
        List<String> out = new ArrayList<>();
        String summary = text.path("summary").asText("");
        if (summary.isBlank()) problems.add("\"summary\" is missing");
        out.add(summary);
        JsonNode ws = text.path("weaknesses");
        if (!ws.isArray() || ws.size() != expectedWeaknesses) {
            problems.add("\"weaknesses\" must have exactly " + expectedWeaknesses + " entries");
        } else {
            ws.forEach(w -> out.add(w.asText("")));
        }
        out.add(text.path("openings").asText(""));
        return out;
    }

    // ── what the model sees: the facts without positions or ids ─────────────

    static Map<String, Object> reportView(ReportFacts f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("games_analysed", f.gamesAnalysed());
        m.put("serious_mistakes", f.serious());
        m.put("blunders", f.blunders());
        m.put("serious_mistakes_per_game", f.perGame());
        List<Map<String, Object>> rates = new ArrayList<>();
        for (var r : f.byPhase()) {
            rates.add(Map.of("phase", r.label(), "serious_mistakes", r.count(), "per_100_moves", r.perHundred()));
        }
        m.put("by_phase", rates);
        m.put("highest_rate_in_phase", f.worstPhase() == null ? "" : f.worstPhase().toLowerCase(java.util.Locale.ROOT));
        m.put("weaknesses_in_order", weaknessView(f.topCauses()));
        m.put("tactics_that_caught_you", weaknessView(f.topTactics()));
        List<Map<String, Object>> os = new ArrayList<>();
        for (var o : f.openings()) {
            os.add(Map.of("opening", o.name(), "games", o.games(), "serious_mistakes_per_game", o.seriousPerGame()));
        }
        m.put("openings_above_your_average", os);
        return m;
    }

    private static List<Map<String, Object>> weaknessView(List<ReportFacts.Weakness> ws) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var w : ws) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("title", w.title());
            x.put("count", w.count());
            x.put("out_of", w.base());
            x.put("percent", w.pct());
            x.put("blunders", w.blunders());
            out.add(x);
        }
        return out;
    }

    static Map<String, Object> planView(PlanFacts p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessions_per_week", p.sessionsPerWeek());
        m.put("minutes_per_session", p.minutesPerSession());
        List<Map<String, Object>> ps = new ArrayList<>();
        for (var x : p.priorities()) {
            ps.add(Map.of("rank", x.rank(), "focus", x.focus(), "evidence", x.why(), "own_positions", x.positions()));
        }
        m.put("priorities", ps);
        if (p.pattern() != null) m.put("tactic_to_learn", Map.of("focus", p.pattern().focus(), "evidence", p.pattern().why()));
        List<Map<String, Object>> ss = new ArrayList<>();
        for (var x : p.sessions()) ss.add(Map.of("session", x.number(), "focus", x.focus(), "positions", x.positions().size()));
        m.put("sessions", ss);
        return m;
    }

    private String toJson(Object o) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
