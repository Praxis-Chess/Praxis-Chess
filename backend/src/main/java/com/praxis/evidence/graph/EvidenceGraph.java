package com.praxis.evidence.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * The Reasoning Evidence Graph for one mistake (§6).
 *
 * <p>Typed sections rather than a bag of items, because two consumers read it
 * very differently. The verifier and the rules need typed fields — "is the
 * critical reply {@code Qxf7#}?" is a field comparison, not a text search. The
 * model and the citation checker need a flat list of stable IDs ({@code P0},
 * {@code T1}, {@code Δ2}) — which {@link #items()} derives from the same data,
 * so the two views cannot disagree.
 *
 * <p>Everything here is a fact computed from the board or the engine. Nothing is
 * a label; labels are {@code DiagnosisRules}' job, applied on top.
 *
 * <p>Squares are lowercase ({@code "c3"}), pieces are lowercase names
 * ({@code "knight"}), sides are {@code "WHITE"}/{@code "BLACK"}, evaluations are
 * pawns from White's point of view unless a field says otherwise.
 */
public record EvidenceGraph(
        int version,
        Header header,
        PositionNode p0,
        MoveNode best,
        MoveNode played,
        Reply reply,
        LineOutcome playedLine,
        LineOutcome bestLine,
        Counterfactual cf1,
        ThreatNode t1,
        List<DeltaItem> delta,
        List<GeometryItem> geometry,
        List<EffectItem> bestEffects,
        List<EffectItem> movedEffects,
        VisibilityNode d1,
        boolean backRankMate
) {

    /** What position, whose move, and how it was searched. */
    public record Header(String fen, String player, int ply, String phase, String severity,
                         int depth, String engine) {}

    public record PositionNode(double evalWhite, double winPctPlayer,
                               int materialWhite, int materialBlack, String sideToMove) {}

    /**
     * A move from P0 — the engine's best or the one played.
     *
     * @param landingSee static exchange value on the destination square after the
     *                   move, from the mover's side: negative means it can be won
     * @param captureSee for a capture, what the capture sequence gains; null otherwise
     */
    public record MoveNode(String uci, String san, String from, String to, String piece,
                           Double evalWhiteAfter, Double winPctPlayerAfter,
                           String captured, Integer captureSee, int landingSee,
                           boolean givesCheck) {}

    /** A piece that changed hands, and at which ply of the line. */
    public record Taken(String piece, String square, String san, int ply, int value) {}

    /**
     * R1 and the line it starts, walked to the consequence (PC). Counted from the
     * position after the played move, with the played move's own capture at ply 0.
     */
    public record Reply(String uci, String san, String from, String to, boolean givesCheck,
                        List<String> lineSan, List<Taken> losses, List<Taken> gains,
                        int netLoss, boolean mate, Integer mateIn, int consequencePly) {}

    /**
     * A whole line from P0, first move included — what the labels compare.
     * {@code netLoss} is pawns the player is down at the end of it; negative
     * means the line wins material.
     */
    public record LineOutcome(List<String> lineSan, int netLoss, boolean mateForPlayer,
                              boolean mateAgainstPlayer, Integer mateIn) {}

    /**
     * CF1: the critical reply, played after the engine's move instead (§6.5).
     *
     * @param lossAfterBest pawns the player would lose if the opponent played R1
     *                      after B, against P0's value; null when R1 is illegal there
     * @param parries       R1 does not work after B — illegal, or worth under a pawn
     */
    public record Counterfactual(String replyUci, String replySan, boolean bestIsPlayed,
                                 boolean legalAfterBest, Double lossAfterBest, boolean parries) {}

    /**
     * T1: the null-move probe at P0, and whether R1 was already that threat.
     *
     * @param cost      pawns the player loses by passing
     * @param replyCost pawns the player loses if the opponent plays R1 with a free move
     */
    public record ThreatNode(boolean probed, String skipReason, String moveUci, String moveSan,
                             Double cost, Double replyCost, boolean threatened,
                             boolean replyIsThreat) {}

    /**
     * One Δ item: something the played move changed for a piece that did not
     * move, or a line it opened or closed.
     *
     * @param kind DEFENDER_REMOVED · DEFENDER_GAINED · NOW_LOOSE · LINE_OPENED · LINE_BLOCKED
     */
    public record DeltaItem(String kind, String square, String piece, String side,
                            Integer seeBefore, Integer seeAfter,
                            List<String> defendersBefore, List<String> defendersAfter,
                            String slider, String target) {}

    /** G*: tactic geometry created by R1. */
    public record GeometryItem(String kind, String by, String piece, List<String> targets) {}

    /**
     * B.e* / M.e*: what a move does in its own right.
     *
     * @param kind ATTACKS · CAPTURES · LANDS · CHECK · BLOCKS_LINE · OPENS_LINE
     */
    public record EffectItem(String kind, List<String> squares, String text) {}

    /** D1: when the engine first saw the played move was bad, and kept seeing it. */
    public record VisibilityNode(Integer depth, String band, double finalLoss, double threshold) {}

    /** One citable item, for R3 rendering, citation checking and the budget. */
    public record GraphItem(String id, String text, List<String> squares, int priority) {}

    // ── the flat, ID'd view ───────────────────────────────────────────────────

    /**
     * Every item with a stable ID, highest priority first.
     *
     * <p>IDs never depend on truncation: {@code Δ3} is the third Δ item whether or
     * not items after it are dropped for budget, so a citation made against a
     * truncated rendering still means the same thing against the full graph.
     *
     * <p>Priorities follow §6.6's truncation order — consequence, R1, CF1, T1, Δ,
     * B.e*, detail — with the skeleton nodes pinned at the top.
     */
    public List<GraphItem> items() {
        List<GraphItem> out = new ArrayList<>();
        String player = cap(header.player());
        String opponent = "White".equals(player) ? "Black" : "White";

        out.add(new GraphItem("P0",
                "before the move · " + player + " to move · " + player + " win ≈ "
                        + Math.round(p0.winPctPlayer()) + "% · material " + p0.materialWhite()
                        + "–" + p0.materialBlack(),
                List.of(), 0));

        out.add(new GraphItem("PC", consequenceText(), lossSquares(), 1));
        if (reply.san() != null) {
            out.add(new GraphItem("R1",
                    "critical reply " + reply.san() + (reply.givesCheck() ? " (check)" : "")
                            + " · line " + String.join(" ", reply.lineSan()),
                    squares(reply.from(), reply.to()), 2));
        }
        out.add(new GraphItem("CF1", counterfactualText(), squares(reply.from(), reply.to()), 3));
        out.add(new GraphItem("T1", threatText(opponent), threatSquares(), 4));

        out.add(new GraphItem("B",
                "best " + best.san() + " → PB " + player + " win ≈ "
                        + (best.winPctPlayerAfter() == null ? "?" : Math.round(best.winPctPlayerAfter()) + "%")
                        + bestLineText(),
                squares(best.from(), best.to()), 0));
        out.add(new GraphItem("M",
                "played " + played.san() + " → PP " + player + " win ≈ "
                        + (played.winPctPlayerAfter() == null ? "?" : Math.round(played.winPctPlayerAfter()) + "%"),
                squares(played.from(), played.to()), 0));
        out.add(new GraphItem("D1", visibilityText(), List.of(), 5));

        for (int i = 0; i < geometry.size(); i++) {
            GeometryItem g = geometry.get(i);
            List<String> sq = new ArrayList<>();
            sq.add(g.by());
            sq.addAll(g.targets());
            out.add(new GraphItem("G" + (i + 1), geometryText(g), sq, 6));
        }

        // Δ: pieces left losing material first, then other defender changes, then lines.
        for (int i = 0; i < delta.size(); i++) {
            DeltaItem d = delta.get(i);
            int priority = switch (d.kind()) {
                case "NOW_LOOSE" -> 7;
                case "DEFENDER_REMOVED" -> 8;
                case "DEFENDER_GAINED" -> 10;
                default -> 9;
            };
            List<String> sq = new ArrayList<>();
            if (d.square() != null) sq.add(d.square());
            if (d.slider() != null) sq.add(d.slider());
            if (d.target() != null) sq.add(d.target());
            if (d.defendersBefore() != null) sq.addAll(d.defendersBefore());
            out.add(new GraphItem("Δ" + (i + 1), deltaText(d), sq, priority));
        }
        for (int i = 0; i < movedEffects.size(); i++) {
            EffectItem e = movedEffects.get(i);
            out.add(new GraphItem("M.e" + (i + 1), e.text(), e.squares(), 11));
        }
        for (int i = 0; i < bestEffects.size(); i++) {
            EffectItem e = bestEffects.get(i);
            out.add(new GraphItem("B.e" + (i + 1), e.text(), e.squares(), 12));
        }

        out.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
        return out;
    }

    /** All item IDs, including ones a budgeted rendering would drop. */
    public List<String> ids() {
        return items().stream().map(GraphItem::id).toList();
    }

    // ── item text ──────────────────────────────────────────────────────────────

    private String consequenceText() {
        StringBuilder s = new StringBuilder("consequence");
        if (reply.mate()) {
            s.append(" · mate in ").append(reply.mateIn());
        } else if (reply.netLoss() > 0) {
            s.append(" · ").append(cap(header.player())).append(" loses ")
                    .append(reply.netLoss()).append(reply.netLoss() == 1 ? " pawn" : " pawns")
                    .append(" net");
        } else {
            s.append(" · no material lost within the horizon");
        }
        if (reply.consequencePly() > 0 && (reply.mate() || reply.netLoss() > 0)) {
            s.append(" · consequence ply ").append(reply.consequencePly());
        }
        if (!reply.losses().isEmpty()) s.append(" · lost ").append(taken(reply.losses()));
        if (!reply.gains().isEmpty()) s.append(" · won ").append(taken(reply.gains()));
        return s.toString();
    }

    private String counterfactualText() {
        if (reply.san() == null) return "no critical reply";
        if (cf1.bestIsPlayed()) return "the played move was the engine's move";
        if (!cf1.legalAfterBest()) {
            return "after " + best.san() + ", " + reply.san() + " is not legal";
        }
        return "after " + best.san() + ", " + reply.san() + " costs "
                + fmt(cf1.lossAfterBest()) + (cf1.parries() ? " — it does not work" : " — it still works");
    }

    private String threatText(String opponent) {
        if (!t1.probed()) return "threat probe skipped: " + t1.skipReason();
        StringBuilder s = new StringBuilder("threat at P0 (null-move probe): ")
                .append(opponent).append(' ').append(t1.moveSan())
                .append(" worth ").append(fmt(t1.cost()));
        if (!t1.threatened()) s.append(" — no real threat");
        if (t1.replyCost() != null) {
            s.append(" · ").append(reply.san()).append(" as a free move worth ").append(fmt(t1.replyCost()));
            s.append(t1.replyIsThreat() ? " — it was already a threat" : " — not already a threat");
        }
        return s.toString();
    }

    private String bestLineText() {
        if (bestLine.mateForPlayer()) return " · best line mates";
        if (bestLine.netLoss() < 0) return " · best line wins " + (-bestLine.netLoss()) + " net";
        return "";
    }

    private String visibilityText() {
        return d1.depth() == null
                ? "played move's drop not held at any depth searched → visibility " + d1.band()
                : "played move seen as losing from depth " + d1.depth() + " → visibility " + d1.band();
    }

    private static String geometryText(GeometryItem g) {
        String kind = g.kind().toLowerCase().replace('_', ' ');
        return switch (g.kind()) {
            case "PIN" -> g.piece() + " on " + g.by() + " pins " + g.targets().get(0) + " to " + g.targets().get(1);
            case "SKEWER" -> g.piece() + " on " + g.by() + " skewers " + g.targets().get(0) + " to " + g.targets().get(1);
            default -> kind + " by the " + g.piece() + " on " + g.by() + " on " + String.join(", ", g.targets());
        };
    }

    private static String deltaText(DeltaItem d) {
        return switch (d.kind()) {
            case "LINE_OPENED" -> "line opened: " + d.piece() + " on " + d.slider() + " now reaches " + d.target();
            case "LINE_BLOCKED" -> "line blocked: " + d.piece() + " on " + d.slider() + " no longer reaches " + d.target();
            default -> d.piece() + " on " + d.square() + ": defenders " + d.defendersBefore()
                    + " → " + d.defendersAfter() + " · SEE " + d.seeBefore() + " → " + d.seeAfter()
                    + ("NOW_LOOSE".equals(d.kind()) ? " (now loose)" : "");
        };
    }

    private List<String> lossSquares() {
        List<String> out = new ArrayList<>();
        reply.losses().forEach(t -> out.add(t.square()));
        reply.gains().forEach(t -> out.add(t.square()));
        return out;
    }

    private List<String> threatSquares() {
        List<String> out = new ArrayList<>();
        if (t1.moveUci() != null && t1.moveUci().length() >= 4) {
            out.add(t1.moveUci().substring(0, 2));
            out.add(t1.moveUci().substring(2, 4));
        }
        out.addAll(squares(reply.from(), reply.to()));
        return out;
    }

    private static List<String> squares(String a, String b) {
        List<String> out = new ArrayList<>();
        if (a != null) out.add(a);
        if (b != null) out.add(b);
        return out;
    }

    private static String taken(List<Taken> list) {
        List<String> out = new ArrayList<>();
        for (Taken t : list) out.add(t.piece() + " on " + t.square() + " (" + t.san() + ")");
        return String.join(", ", out);
    }

    private static String fmt(Double pawns) {
        if (pawns == null) return "?";
        if (Math.abs(pawns) >= 90) return "mate";
        return String.format(java.util.Locale.ROOT, "%.1f", pawns);
    }

    private static String cap(String side) {
        return side == null ? "" : side.charAt(0) + side.substring(1).toLowerCase();
    }
}
