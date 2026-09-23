package com.praxis.evidence.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The four evidence levels the research question compares (§2).
 *
 * <pre>
 *   R0  FEN + played move                                  "just the board"
 *   R1  + engine best move, evaluation change, engine line today's production prompt
 *   R2  + flat facts (loose pieces, attacks, lines)        revision 1
 *   R3  + the full evidence graph, with citable IDs        this plan
 * </pre>
 *
 * <p>Each level strictly contains the one below it: R2 adds facts to R1, it does
 * not rephrase R1. Otherwise a difference between levels could be a difference in
 * wording rather than in evidence, and H1 would be measuring the wrong thing.
 *
 * <p>R2's facts are deliberately the ones a flat fact list can express — what is
 * attacked, what is loose, which lines changed. What only the graph can express
 * — the threat before the move, the counterfactual, how deep it was — is R3's
 * alone, because that difference is the experiment.
 *
 * <p>The backend owns rendering (§17.3): Python asks for R0–R3 through the dev
 * endpoint and never builds a prompt itself, so the text a model is trained on
 * and the text it is served cannot drift apart.
 */
public final class Representations {

    private Representations() {}

    public enum Level { R0, R1, R2, R3 }

    public static final String INSTRUCTION =
            "Diagnose the move. Give the reasoning chain first, then the consequence, "
                    + "mechanism, motif, critical response, visibility and a short explanation. "
                    + "Claim only what the evidence supports.";

    /**
     * @param text   exactly what the model is shown
     * @param tokens estimated tokens, logged per graph because length confounds H1
     * @param dropped R3 items removed to meet the budget; empty for R0–R2
     */
    public record Rendered(Level level, String text, int tokens, int items, List<String> dropped) {}

    public static Rendered render(EvidenceGraph g, Level level) {
        return switch (level) {
            case R0 -> plain(level, r0(g));
            case R1 -> plain(level, r1(g));
            case R2 -> plain(level, r2(g));
            case R3 -> r3(g);
        };
    }

    private static Rendered plain(Level level, List<String> lines) {
        String text = String.join("\n", lines) + "\n\n" + INSTRUCTION;
        return new Rendered(level, text, GraphBudget.estimateTokens(text), lines.size(), List.of());
    }

    private static List<String> r0(EvidenceGraph g) {
        List<String> out = new ArrayList<>();
        out.add("FEN: " + g.header().fen());
        out.add("Player: " + cap(g.header().player()));
        out.add("Played: " + g.played().san());
        return out;
    }

    private static List<String> r1(EvidenceGraph g) {
        List<String> out = r0(g);
        out.add("Engine best: " + g.best().san());
        out.add(String.format(Locale.ROOT, "Evaluation for %s: %s before, %s after the played move",
                cap(g.header().player()),
                pawns(playerView(g, g.p0().evalWhite())),
                pawns(playerView(g, g.played().evalWhiteAfter()))));
        if (!g.reply().lineSan().isEmpty()) {
            out.add("Engine line after the played move: " + String.join(" ", g.reply().lineSan()));
        }
        return out;
    }

    private static List<String> r2(EvidenceGraph g) {
        List<String> out = r1(g);
        List<String> facts = new ArrayList<>();
        for (var e : g.movedEffects()) facts.add(e.text());
        for (var d : g.delta()) {
            switch (d.kind()) {
                case "NOW_LOOSE" -> facts.add("The " + d.piece() + " on " + d.square()
                        + " is left losing material (exchange " + d.seeAfter() + ")");
                case "DEFENDER_REMOVED" -> facts.add("The " + d.piece() + " on " + d.square()
                        + " lost a defender: " + d.defendersBefore() + " → " + d.defendersAfter());
                case "LINE_OPENED" -> facts.add("The " + d.piece() + " on " + d.slider()
                        + " now reaches " + d.target());
                case "LINE_BLOCKED" -> facts.add("The " + d.piece() + " on " + d.slider()
                        + " no longer reaches " + d.target());
                default -> { }
            }
        }
        if (!facts.isEmpty()) {
            out.add("Facts:");
            for (String f : facts) out.add("- " + f);
        }
        return out;
    }

    /** R3: the §6.7 serialisation, budgeted. */
    private static Rendered r3(EvidenceGraph g) {
        var fitted = GraphBudget.fit(g.items(), items -> r3Text(g, items));
        String text = r3Text(g, fitted.kept());
        return new Rendered(Level.R3, text, GraphBudget.estimateTokens(text),
                fitted.kept().size(), fitted.dropped());
    }

    static String r3Text(EvidenceGraph g, List<EvidenceGraph.GraphItem> items) {
        var h = g.header();
        StringBuilder s = new StringBuilder();
        s.append("GRAPH v").append(g.version())
                .append(" · player: ").append(cap(h.player()))
                .append(" · move ").append((h.ply() + 1) / 2)
                .append(" · phase ").append(h.phase())
                .append(" · severity ").append(h.severity()).append('\n');
        s.append("FEN ").append(h.fen()).append('\n');
        for (var item : items) {
            s.append(String.format("%-5s", "[" + item.id() + "]")).append(' ').append(item.text()).append('\n');
        }
        return s.append('\n').append(INSTRUCTION).toString();
    }

    private static Double playerView(EvidenceGraph g, Double white) {
        if (white == null) return null;
        return "WHITE".equals(g.header().player()) ? white : -white;
    }

    private static String pawns(Double v) {
        if (v == null) return "?";
        if (v >= 90) return "winning (mate)";
        if (v <= -90) return "lost (mate)";
        return String.format(Locale.ROOT, "%+.2f", v);
    }

    private static String cap(String side) {
        return side == null ? "" : side.charAt(0) + side.substring(1).toLowerCase(Locale.ROOT);
    }
}
