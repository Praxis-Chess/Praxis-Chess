package com.praxis.evidence.diagnosis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.praxis.evidence.Attacks;
import com.praxis.evidence.graph.EvidenceGraph;
import com.praxis.evidence.graph.EvidenceGraphBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One mistake's diagnosis, shaped for the product (§17.2, D3): the "Why?" panel
 * on Game Analysis and Prax's explain_mistake.
 *
 * <p>Everything here is read from the evidence graph and the rules' diagnosis.
 * Nothing is written by a model. The explanation passed the verifier; the steps
 * are its claims, in order; each step names the board that shows it. The boards
 * are the graph's own positions (P0, PP, PB, PC), with arrows for the moves the
 * step is about.
 *
 * <p>Pure: graph in, view out. No database, no engine.
 */
public final class WhyView {

    private WhyView() {}

    /** Who wrote the diagnosis. Only the rules exist until a model is trained and verified. */
    public static final String DIAGNOSED_BY_RULES = "RULES";

    /** PLAYED, BEST, REPLY or THREAT. The page picks the colour; the backend says what the arrow is. */
    public record Arrow(String from, String to, String kind) {}

    /** One of the graph's positions. */
    public record BoardNode(String id, String title, String fen) {}

    /** One claim of the chain: its words, and the board and arrows that show it. */
    public record Step(String type, String text, String board, List<Arrow> arrows) {}

    /** @param evalBefore / evalAfter pawns, White-positive: the position, and after the played move */
    public record View(String player, String playedSan, String playedUci, String bestSan, String bestUci,
                       Double evalBefore, Double evalAfter,
                       String consequence, String mechanism, String motif, String visibility,
                       boolean composite, String explanation, boolean verified, String diagnosedBy,
                       String criticalReply, int landsAtPly,
                       List<String> playedLine, List<String> bestLine,
                       List<Step> steps, Map<String, BoardNode> boards) {}

    public static View of(EvidenceGraph g) {
        Diagnosis d = DiagnosisRules.diagnose(g);
        var labels = DiagnosisRules.label(g);
        boolean verified = DiagnosisVerifier.verify(g, d, DiagnosisVerifier.Mode.CITATION).passed();

        Map<String, BoardNode> boards = boards(g);
        List<Step> steps = new ArrayList<>();
        for (var c : d.reasoningChain()) steps.add(step(g, c, boards));

        return new View(g.header().player(), g.played().san(), g.played().uci(), g.best().san(), g.best().uci(),
                g.p0().evalWhite(), g.played().evalWhiteAfter(),
                d.consequence(), d.mechanism(), d.motif(), d.visibility(),
                labels.composite(), d.explanation(), verified, DIAGNOSED_BY_RULES,
                g.reply().san(), landsAt(g),
                g.playedLine().lineSan(), g.bestLine().lineSan(), steps, boards);
    }

    // ── boards ───────────────────────────────────────────────────────────────

    static Map<String, BoardNode> boards(EvidenceGraph g) {
        Map<String, BoardNode> out = new LinkedHashMap<>();
        String fen = g.header().fen();
        out.put("P0", new BoardNode("P0", "Before your move", fen));

        String pp = after(fen, List.of(g.played().uci()), 1);
        if (pp != null) out.put("PP", new BoardNode("PP", "After " + g.played().san(), pp));

        if (!g.best().uci().equals(g.played().uci())) {
            String pb = after(fen, List.of(g.best().uci()), 1);
            if (pb != null) out.put("PB", new BoardNode("PB", "After " + g.best().san() + ", the engine's move", pb));
        }

        // PC: along the played line to the ply where the damage lands.
        int ply = landsAt(g);
        List<String> line = g.reply().lineSan();
        if (pp != null && ply >= 1 && ply <= line.size()) {
            String pc = after(pp, line, ply);
            if (pc != null) {
                out.put("PC", new BoardNode("PC", "After " + line.get(ply - 1) + ", where it costs you", pc));
            }
        }
        return out;
    }

    /**
     * The ply of the played line at which the loss is complete: where mate lands,
     * or the first ply by which the running net loss reaches its final value. 0
     * when the played line loses nothing.
     *
     * <p>Not the graph's consequence ply, which is the first ply the player loses
     * <em>anything</em>, and so lands on the recapture of an even trade. On a real
     * game, Bxc6+ bxc6 (a fair trade) came first and hxg4, the pawn actually lost,
     * came later. "The damage lands with bxc6" would have been false.
     */
    static int landsAt(EvidenceGraph g) {
        var r = g.reply();
        if (r.mate() || g.playedLine().mateAgainstPlayer()) return r.consequencePly();
        if (r.netLoss() <= 0) return 0;
        int last = 0;
        for (var t : r.losses()) last = Math.max(last, t.ply());
        for (int ply = 0; ply <= last; ply++) {
            int net = 0;
            for (var t : r.losses()) if (t.ply() <= ply) net += t.value();
            for (var t : r.gains()) if (t.ply() <= ply) net -= t.value();
            if (net >= r.netLoss()) return ply;
        }
        return r.consequencePly();
    }

    /** The FEN after the first {@code count} moves (SAN or UCI), or null if one is not legal. */
    static String after(String fen, List<String> moves, int count) {
        try {
            Board board = Attacks.at(fen);
            for (int i = 0; i < count && i < moves.size(); i++) {
                Move m = EvidenceGraphBuilder.resolve(board, moves.get(i));
                if (m == null || !board.doMove(m)) return null;
            }
            return board.getFen();
        } catch (Exception e) {
            return null;
        }
    }

    // ── steps ────────────────────────────────────────────────────────────────

    /**
     * Which position shows this claim, and which moves to draw on it.
     *
     * <p>A claim about the move itself is shown before it; a claim about the
     * reply after it; the "what if" on the engine's move; the outcome where it
     * lands. A board that could not be built falls back to the one before it.
     */
    static Step step(EvidenceGraph g, Diagnosis.Claim c, Map<String, BoardNode> boards) {
        Arrow played = arrow(g.played().from(), g.played().to(), "PLAYED");
        Arrow best = arrow(g.best().from(), g.best().to(), "BEST");
        Arrow reply = arrow(g.reply().from(), g.reply().to(), "REPLY");

        String board;
        List<Arrow> arrows;
        switch (c.type()) {
            case "THREAT_EXISTS" -> {
                board = "P0";
                arrows = listOf(threatArrow(g, str(c.args().get("move"))));
            }
            case "DOES_NOT_ADDRESS" -> {
                board = "P0";
                arrows = listOf(played, threatArrow(g, str(c.args().get("threat"))));
            }
            case "DEFENDER_REMOVED" -> {
                board = "P0";
                arrows = listOf(played);
            }
            case "MOVED_INTO_ATTACK", "LOSING_CAPTURE", "TACTIC_GEOMETRY", "CRITICAL_REPLY" -> {
                board = "PP";
                arrows = listOf(reply);
            }
            case "COUNTERFACTUAL" -> {
                String effect = str(c.args().get("effect"));
                if ("MATES".equals(effect) || "WINS_MATERIAL".equals(effect)) {
                    board = "P0";
                    arrows = listOf(best);
                } else {
                    board = "PB";
                    // The reply is drawn only where it is legal: "is not possible"
                    // with an arrow for it would show the impossible move.
                    arrows = g.cf1().legalAfterBest() ? listOf(reply) : List.of();
                }
            }
            case "MATE_IN", "MATERIAL_CHANGE" -> {
                boolean bestLine = "BEST".equals(str(c.args().get("line")));
                board = bestLine ? "PB" : "PC";
                arrows = List.of();
            }
            default -> {   // VISIBILITY, and anything added later
                board = "P0";
                arrows = listOf(played, best);
            }
        }
        return new Step(c.type(), c.text(), available(board, boards), arrows);
    }

    private static String available(String board, Map<String, BoardNode> boards) {
        if (boards.containsKey(board)) return board;
        if ("PC".equals(board) && boards.containsKey("PP")) return "PP";
        return "P0";
    }

    /** The threat is either the probe's move or R1; both are stored with their squares. */
    private static Arrow threatArrow(EvidenceGraph g, String san) {
        if (san == null) return null;
        var t1 = g.t1();
        if (san.equals(t1.moveSan()) && t1.moveUci() != null && t1.moveUci().length() >= 4) {
            return arrow(t1.moveUci().substring(0, 2), t1.moveUci().substring(2, 4), "THREAT");
        }
        if (san.equals(g.reply().san())) return arrow(g.reply().from(), g.reply().to(), "THREAT");
        return null;
    }

    private static Arrow arrow(String from, String to, String kind) {
        return from == null || to == null ? null : new Arrow(from, to, kind);
    }

    private static List<Arrow> listOf(Arrow... arrows) {
        List<Arrow> out = new ArrayList<>();
        for (Arrow a : arrows) if (a != null) out.add(a);
        return List.copyOf(out);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ── facts, for Prax ──────────────────────────────────────────────────────

    /**
     * Plain statements for Prax's verified-facts channel, most important first.
     * Shown to the player word for word; the model only chooses which.
     *
     * <p>Each is computed from the graph: the verified explanation, when the
     * damage lands, how deep it was to see, what the engine's move does, and what
     * the played move left loose.
     */
    public static List<String> facts(EvidenceGraph g, View v) {
        List<String> out = new ArrayList<>();
        out.add(v.explanation());

        if (!"NOT_CONCRETE".equals(v.consequence()) && v.landsAtPly() > 0
                && v.landsAtPly() <= g.reply().lineSan().size()) {
            int ply = v.landsAtPly();
            String move = g.reply().lineSan().get(ply - 1);
            out.add(ply == 1
                    ? "The damage is immediate: it lands with the opponent's reply, " + move + "."
                    : "The damage lands " + ply + " half-moves into the line, with " + move + ".");
        }

        String vis = visibility(g.d1());
        if (vis != null) out.add(vis);

        for (var e : g.bestEffects()) {
            if (!"LANDS".equals(e.kind()) && e.text() != null) {
                // The graph's text carries engine notation after a "·" ("exchange
                // +0", "SEE there -3"), written for the model's evidence block. The
                // player gets the sentence before it.
                String text = e.text();
                int cut = text.indexOf(" · ");
                out.add("The engine's move: " + (cut > 0 ? text.substring(0, cut) : text) + ".");
                break;
            }
        }

        String side = g.header().player();
        for (var d : g.delta()) {
            if ("NOW_LOOSE".equals(d.kind()) && side.equals(d.side())) {
                out.add(g.played().san() + " left the " + d.piece() + " on " + d.square()
                        + " where it can be won.");
                break;
            }
        }
        return List.copyOf(out);
    }

    private static String visibility(EvidenceGraph.VisibilityNode d1) {
        if (d1 == null || d1.band() == null) return null;
        return switch (d1.band().toUpperCase(Locale.ROOT)) {
            case "SHALLOW" -> "The engine sees the problem at depth " + d1.depth()
                    + ", a shallow search: it was there to be found over the board.";
            case "MEDIUM" -> "The engine sees the problem from depth " + d1.depth()
                    + ": a real oversight, of the kind most club mistakes are.";
            case "DEEP" -> "The engine only sees the problem from depth " + d1.depth()
                    + ": hard to find over the board.";
            default -> null;
        };
    }
}
