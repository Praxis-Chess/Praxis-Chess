package com.praxis.evidence.diagnosis;

import com.praxis.evidence.graph.EvidenceGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rule composer (§7.3): applies the §7.1 taxonomy to a graph.
 *
 * <p>Three jobs, which is why it has to be right before anything is trained:
 * <ol>
 *   <li><b>Labels</b> for training and evaluation.</li>
 *   <li><b>A baseline system</b>, {@code S_rules}, in every experiment.</li>
 *   <li><b>The runtime fallback</b> when a model's answer fails verification.</li>
 * </ol>
 *
 * <p>Every mechanism rule is evaluated and <b>every rule that fires is
 * recorded</b> (§7.2). Exactly one → a single-cause mistake, and a full typed
 * chain is emitted. None, or several → the composite subset, where the rules
 * abstain from naming a cause and a model has to earn its place (H4).
 *
 * <p>Each rule is a definition from §7.1 made mechanical, reading only fields of
 * the graph. When a definition needed an interpretation to become computable,
 * the interpretation is stated next to it. Hand labels (Phase 3's exit) measure
 * whether those interpretations were the right ones.
 */
public final class DiagnosisRules {

    private DiagnosisRules() {}

    /**
     * @param fired     every mechanism rule whose preconditions hold, in rule order
     * @param composite no rule fired, or more than one did
     * @param mechanism the single fired rule; UNCLEAR when composite; NONE when abstaining
     */
    public record Labels(String consequence, List<String> fired, boolean composite,
                         String mechanism, String motif, String visibility) {

        public boolean singleCause() {
            return fired.size() == 1 && !"NOT_CONCRETE".equals(consequence);
        }
    }

    // ── labels ────────────────────────────────────────────────────────────────

    public static Labels label(EvidenceGraph g) {
        String consequence = consequence(g);
        List<String> fired = fired(g, consequence);
        boolean concrete = !"NOT_CONCRETE".equals(consequence);
        boolean composite = concrete && fired.size() != 1;
        String mechanism = !concrete ? "NONE" : fired.size() == 1 ? fired.get(0) : "UNCLEAR";
        return new Labels(consequence, List.copyOf(fired), composite, mechanism,
                motif(g, consequence, mechanism), g.d1().band());
    }

    /**
     * Consequence — what happened (§7.1), first match wins.
     *
     * <p>Interpretation: both sides of every comparison are "a move plus eight
     * plies", with the move's own capture counted — the played move against the
     * engine's. LOST_MATERIAL needs the played line to actually lose material, not
     * merely to win less than the best line would have; that difference is
     * MISSED_MATERIAL. Read literally, "loss relative to the best line" would make
     * every missed win a loss and MISSED_MATERIAL unreachable.
     */
    static String consequence(EvidenceGraph g) {
        var played = g.playedLine();
        var best = g.bestLine();
        if (played.mateAgainstPlayer() && !best.mateAgainstPlayer()) return "MATED";
        if (best.mateForPlayer() && !played.mateForPlayer()) return "MISSED_MATE";
        int gap = played.netLoss() - best.netLoss();
        if (played.netLoss() >= 1 && gap >= 1) return "LOST_MATERIAL";
        if (best.netLoss() <= -1 && gap >= 1 && played.netLoss() < 1) return "MISSED_MATERIAL";
        return "NOT_CONCRETE";
    }

    /** Mechanism — why. Every rule is tried; all that hold are returned. */
    static List<String> fired(EvidenceGraph g, String consequence) {
        List<String> out = new ArrayList<>();
        boolean bad = "MATED".equals(consequence) || "LOST_MATERIAL".equals(consequence);
        boolean missed = "MISSED_MATE".equals(consequence) || "MISSED_MATERIAL".equals(consequence);
        var t1 = g.t1();
        var cf1 = g.cf1();
        var played = g.played();
        // "No threat at P0" is read as §6.5 states it — R1 was not ALREADY the
        // threat — rather than §7.1's shorthand, "no threat of any kind". The
        // shorthand fails on real games: in two of the first sixty, the opponent had
        // a different threat (a mate) that the move dealt with, and the move then
        // walked into a new tactic. That is the move creating a problem, and the
        // shorthand could not say so because some other threat existed.
        //
        // It still needs a probe that ran. When the player was in check the probe
        // cannot run, and the rules that need it stay silent rather than reading a
        // missing probe as "not already threatened".
        boolean noThreat = t1.probed() && !t1.replyIsThreat();

        if (bad) {
            // T1 found R1 (it was already a threat), B parries it, M does not
            // (R1 refutes M by construction). Or the threat T1 found is not R1
            // but is carried out later in the played line, and wins material there.
            if ((t1.replyIsThreat() && cf1.parries()) || threatCarriedOut(g)) out.add("IGNORED_THREAT");

            // A piece other than the mover went from safe to losing, and the
            // moved piece was one of its defenders.
            if (noThreat && removedDefender(g) != null) out.add("REMOVED_DEFENDER");

            // Interpretation: a non-capture. A losing capture also lands where it
            // can be won, so allowing captures here would make LOSING_CAPTURE fire
            // MOVED_INTO_ATTACK every time — an overlap by definition, which tells
            // the composite subset nothing.
            if (played.captured() == null && played.landingSee() < 0) out.add("MOVED_INTO_ATTACK");

            if (played.captured() != null && played.captureSee() != null && played.captureSee() < 0) {
                out.add("LOSING_CAPTURE");
            }

            // R1 is a geometric tactic available only after M: CF1 confirms it
            // does not work after B.
            //
            // Not when R1 simply takes the piece that was just moved or captured
            // with, and a rule above already says that piece was lost. The geometry
            // is then a side effect of the capture: on 100 hand-labelled mistakes,
            // Rc7+ Bxc7, Nd4 Bxd4 and Re5 Bxe5 were each labelled "moved into
            // attack", and firing both rules made them composite.
            boolean takesTheMover = g.played().to().equals(g.reply().to())
                    && (out.contains("MOVED_INTO_ATTACK") || out.contains("LOSING_CAPTURE"));
            if (noThreat && !g.geometry().isEmpty() && cf1.parries() && !takesTheMover) {
                out.add("CREATED_TACTIC");
            }
        }
        if (missed) out.add("MISSED_OPPORTUNITY");
        return out;
    }

    /**
     * The threat T1 found before the move is not R1, but the opponent plays it
     * later in the played line, it takes material there, and the opponent does not
     * play it in the engine's line.
     *
     * <p>A threat does not have to be the first reply to be what costs material.
     * With a knight attacked, the engine often plays a quiet move first and takes
     * the knight a few moves later. Seen on 100 hand-labelled mistakes: after
     * Qc3, White played Ke2 first and took with cxd5 on move seven. The rules
     * missed it because they only compared the threat with R1.
     *
     * <p>Moves are compared by SAN. Within eight plies of one line, the same SAN
     * for the same side is the same move.
     */
    public static boolean threatCarriedOut(EvidenceGraph g) {
        var t1 = g.t1();
        String threat = t1.moveSan();
        if (!t1.probed() || !t1.threatened() || threat == null) return false;
        if (threat.equals(g.reply().san())) return false;   // that is R1, judged above
        if (!opponentPlays(g.playedLine().lineSan(), threat)) return false;
        if (opponentPlays(g.bestLine().lineSan(), threat)) return false;
        return g.reply().losses().stream().anyMatch(l -> threat.equals(l.san()));
    }

    /** The opponent's moves are the odd indices: index 0 is the player's own move. */
    private static boolean opponentPlays(List<String> line, String san) {
        for (int i = 1; i < line.size(); i += 2) {
            if (san.equals(line.get(i))) return true;
        }
        return false;
    }

    /** The Δ item for a piece the move left losing, having been one of its defenders. */
    static EvidenceGraph.DeltaItem removedDefender(EvidenceGraph g) {
        String from = g.played().from();
        for (var d : g.delta()) {
            if (!"NOW_LOOSE".equals(d.kind())) continue;
            if (d.defendersBefore() != null && d.defendersBefore().contains(from)
                    && (d.defendersAfter() == null || !d.defendersAfter().contains(from))) {
                return d;
            }
        }
        return null;
    }

    /** Motif, from geometry first — never from a label. */
    static String motif(EvidenceGraph g, String consequence, String mechanism) {
        // Abstention first. A pin that wins nothing within the horizon is not the
        // motif of a mistake the rules decline to explain — on the first sixty real
        // mistakes, six positional ones were being labelled "pin" this way.
        if ("NOT_CONCRETE".equals(consequence)) return "POSITIONAL";
        if ("MATED".equals(consequence) && g.backRankMate()) return "BACK_RANK";
        if (!g.geometry().isEmpty() && !consequence.startsWith("MISSED")) {
            return g.geometry().get(0).kind();
        }
        if ("MOVED_INTO_ATTACK".equals(mechanism) || "LOSING_CAPTURE".equals(mechanism)
                || "REMOVED_DEFENDER".equals(mechanism)) return "HANGING_PIECE";
        return "OTHER";
    }

    // ── chains ────────────────────────────────────────────────────────────────

    /**
     * The rules' own diagnosis. A full typed chain for single-cause mistakes;
     * facts without a cause for the composite subset; abstention for
     * NOT_CONCRETE.
     *
     * <p>The prose uses only moves and squares that appear in a claim, and only
     * uses causal words ("allows") where a COUNTERFACTUAL claim backs them —
     * because the verifier checks exactly that, and the rules' output is the
     * fallback: it must pass its own checks.
     */
    public static Diagnosis diagnose(EvidenceGraph g) {
        Labels labels = label(g);
        String r1 = g.reply().san();
        List<Diagnosis.Claim> chain = new ArrayList<>();
        String explanation;

        if ("NOT_CONCRETE".equals(labels.consequence())) {
            chain.add(visibility(g));
            explanation = "No material or mate changes hands within the horizon; the difference "
                    + "is positional, which this diagnosis does not cover.";
            return new Diagnosis(chain, labels.consequence(), "NONE", labels.motif(), r1,
                    labels.visibility(), explanation);
        }

        String player = cap(g.header().player());
        String opponent = "White".equals(player) ? "Black" : "White";
        String m = g.played().san();
        String b = g.best().san();

        switch (labels.mechanism()) {
            case "IGNORED_THREAT" -> {
                if (!(g.t1().replyIsThreat() && g.cf1().parries())) {
                    // The threat is carried out later, not as R1. R1 is still named,
                    // as what happens first; no counterfactual, since CF1 tests R1.
                    String threat = g.t1().moveSan();
                    chain.add(claim("THREAT_EXISTS", args("move", threat, "cost", g.t1().cost()), List.of("T1"),
                            "Before " + m + ", " + opponent + " already threatened " + threat + "."));
                    chain.add(claim("DOES_NOT_ADDRESS", args("move", m, "threat", threat), List.of("M", "T1"),
                            m + " does not deal with it."));
                    chain.add(critical(g));
                    chain.add(outcome(g));
                    explanation = "Before " + m + ", " + opponent + " already threatened " + threat + ". "
                            + m + " does not deal with it: after " + r1 + ", " + threat
                            + " still comes, and " + outcomePhrase(g, player) + ".";
                    break;
                }
                chain.add(claim("THREAT_EXISTS", args("move", r1, "cost", g.t1().replyCost()), List.of("T1"),
                        "Before " + m + ", " + opponent + " already threatened " + r1 + "."));
                chain.add(claim("DOES_NOT_ADDRESS", args("move", m, "threat", r1), List.of("M", "T1"),
                        m + " does not deal with it."));
                chain.add(critical(g));
                chain.add(outcome(g));
                chain.add(counterfactual(g));
                explanation = "Before " + m + ", " + opponent + " already threatened " + r1 + ". "
                        + m + " does not deal with it: " + r1 + " follows, and " + outcomePhrase(g, player) + ". "
                        + afterBest(g) + ".";
            }
            case "REMOVED_DEFENDER" -> {
                var d = removedDefender(g);
                int idx = g.delta().indexOf(d) + 1;
                chain.add(claim("DEFENDER_REMOVED",
                        args("square", d.square(), "piece", d.piece(), "defender", g.played().from(), "move", m),
                        List.of("Δ" + idx),
                        m + " moves the " + g.played().piece() + " off " + g.played().from()
                                + ", the defender of the " + d.piece() + " on " + d.square() + "."));
                chain.add(critical(g));
                chain.add(outcome(g));
                if (g.cf1().parries()) chain.add(counterfactual(g));
                explanation = m + " moves the " + g.played().piece() + " off " + g.played().from()
                        + ", where it was defending the " + d.piece() + " on " + d.square() + ". "
                        + r1 + " follows, and " + outcomePhrase(g, player) + "."
                        + (g.cf1().parries() ? " " + afterBest(g) + "." : "");
            }
            case "MOVED_INTO_ATTACK" -> {
                chain.add(claim("MOVED_INTO_ATTACK",
                        args("square", g.played().to(), "move", m, "see", g.played().landingSee()),
                        List.of(effectId(g, "LANDS")),
                        m + " puts the " + g.played().piece() + " where it can be won."));
                chain.add(critical(g));
                chain.add(outcome(g));
                if (g.cf1().parries()) chain.add(counterfactual(g));
                explanation = m + " puts the " + g.played().piece() + " on " + g.played().to()
                        + ", where it can be won. " + r1 + " follows, and " + outcomePhrase(g, player) + "."
                        + (g.cf1().parries() ? " " + afterBest(g) + "." : "");
            }
            case "LOSING_CAPTURE" -> {
                chain.add(claim("LOSING_CAPTURE",
                        args("move", m, "square", g.played().to(), "see", g.played().captureSee()),
                        List.of(effectId(g, "CAPTURES")),
                        m + " starts an exchange that loses material."));
                chain.add(critical(g));
                chain.add(outcome(g));
                explanation = m + " starts an exchange on " + g.played().to() + " that loses material. "
                        + r1 + " follows, and " + outcomePhrase(g, player) + ".";
            }
            case "CREATED_TACTIC" -> {
                var t = g.geometry().get(0);
                chain.add(claim("TACTIC_GEOMETRY",
                        args("kind", t.kind(), "by", t.by(), "targets", t.targets(), "move", r1),
                        List.of("G1"),
                        r1 + " is a " + t.kind().toLowerCase().replace('_', ' ') + "."));
                chain.add(critical(g));
                chain.add(outcome(g));
                chain.add(counterfactual(g));
                explanation = m + " allows " + r1 + ", a " + t.kind().toLowerCase().replace('_', ' ')
                        + " by the " + t.piece() + " on " + t.by() + " against " + String.join(" and ", t.targets())
                        + ", and " + outcomePhrase(g, player) + ". " + afterBest(g) + ".";
            }
            case "MISSED_OPPORTUNITY" -> {
                var best = g.bestLine();
                boolean mates = best.mateForPlayer();
                chain.add(claim("COUNTERFACTUAL",
                        args("alt_move", b, "effect", mates ? "MATES" : "WINS_MATERIAL"), List.of("B"),
                        b + (mates ? " forces mate." : " wins material.")));
                if (mates && best.mateIn() != null) {
                    chain.add(claim("MATE_IN", args("n", best.mateIn(), "line", "BEST"), List.of("B"),
                            "Mate in " + best.mateIn() + "."));
                } else if (!mates) {
                    chain.add(claim("MATERIAL_CHANGE", args("amount", best.netLoss(), "line", "BEST"),
                            List.of("B"), player + " would win " + (-best.netLoss()) + " net."));
                }
                // The played move is not named in this prose: no claim type says
                // "M is not B", and inventing one — or bending DOES_NOT_ADDRESS,
                // which is about threats — would be a claim the verifier cannot
                // check honestly.
                explanation = b + (mates ? " would have forced mate" : " would have won "
                        + (-best.netLoss()) + (best.netLoss() == -1 ? " pawn" : " pawns") + " net")
                        + ", and the move played misses it.";
            }
            default -> {
                // Composite or unclear: state what happened, name no cause.
                chain.add(critical(g));
                chain.add(outcome(g));
                explanation = r1 + " follows " + m + ", and " + outcomePhrase(g, player) + ".";
            }
        }
        chain.add(visibility(g));
        return new Diagnosis(chain, labels.consequence(), labels.mechanism(), labels.motif(), r1,
                labels.visibility(), explanation);
    }

    // ── claim builders ─────────────────────────────────────────────────────────

    private static Diagnosis.Claim critical(EvidenceGraph g) {
        var r = g.reply();
        String result = r.mate() || g.playedLine().mateAgainstPlayer() ? "MATE"
                : r.netLoss() > 0 ? "MATERIAL" : "NONE";
        // "after" names the move being punished, so prose that says "R1 follows M"
        // has a verified claim for both moves.
        return claim("CRITICAL_REPLY", args("move", r.san(), "after", g.played().san(),
                        "result", result, "ply", r.consequencePly()),
                List.of("R1"), r.san() + " is the critical reply.");
    }

    private static Diagnosis.Claim outcome(EvidenceGraph g) {
        var r = g.reply();
        if (r.mate() && r.mateIn() != null) {
            return claim("MATE_IN", args("n", r.mateIn(), "line", "PLAYED"), List.of("PC"),
                    "Mate in " + r.mateIn() + ".");
        }
        return claim("MATERIAL_CHANGE", args("amount", r.netLoss(), "line", "PLAYED"), List.of("PC"),
                "Net material lost: " + r.netLoss() + ".");
    }

    private static Diagnosis.Claim counterfactual(EvidenceGraph g) {
        var cf = g.cf1();
        String effect = cf.legalAfterBest() ? "REPLY_FAILS" : "REPLY_ILLEGAL";
        return claim("COUNTERFACTUAL",
                args("alt_move", g.best().san(), "played", g.played().san(),
                        "effect", effect, "reply", g.reply().san()),
                List.of("B", "CF1"),
                "After " + g.best().san() + ", " + g.reply().san()
                        + (cf.legalAfterBest() ? " does not work." : " is not possible."));
    }

    private static Diagnosis.Claim visibility(EvidenceGraph g) {
        return claim("VISIBILITY", args("band", g.d1().band(), "depth", g.d1().depth()),
                List.of("D1"), "Visibility " + g.d1().band().toLowerCase() + ".");
    }

    private static String afterBest(EvidenceGraph g) {
        return "After " + g.best().san() + ", " + g.reply().san()
                + (g.cf1().legalAfterBest() ? " would not work" : " would not be possible");
    }

    private static String outcomePhrase(EvidenceGraph g, String player) {
        var r = g.reply();
        if (r.mate() && r.mateIn() != null) return "it is mate in " + r.mateIn();
        if (g.playedLine().mateAgainstPlayer()) return "it leads to a forced mate";
        if (r.netLoss() > 0) {
            return player + " loses " + r.netLoss() + (r.netLoss() == 1 ? " pawn" : " pawns") + " net";
        }
        return "no material changes hands within the horizon";
    }

    private static String effectId(EvidenceGraph g, String kind) {
        for (int i = 0; i < g.movedEffects().size(); i++) {
            if (kind.equals(g.movedEffects().get(i).kind())) return "M.e" + (i + 1);
        }
        return "M";
    }

    private static Diagnosis.Claim claim(String type, Map<String, Object> args, List<String> cites, String text) {
        return new Diagnosis.Claim(type, args, cites, text);
    }

    /** Ordered arguments; nulls dropped so an absent fact is absent, not "null". */
    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static String cap(String side) {
        return side == null ? "" : side.charAt(0) + side.substring(1).toLowerCase();
    }
}
