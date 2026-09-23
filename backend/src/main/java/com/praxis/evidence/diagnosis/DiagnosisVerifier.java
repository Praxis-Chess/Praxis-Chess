package com.praxis.evidence.diagnosis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.praxis.evidence.Attacks;
import com.praxis.evidence.graph.EvidenceGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a diagnosis against the evidence graph (§8.4) — the runtime guard, the
 * training-data filter, and the evaluation metric, as one implementation.
 *
 * <p>Two modes (§8.3). <b>Claim mode</b> checks every typed claim against the
 * <i>full</i> graph, whatever the model was shown, and ignores citations — the
 * headline metric, fair to every representation R0–R3, since only R3 has IDs to
 * cite. <b>Citation mode</b> additionally requires every cited ID to exist and to
 * be about the squares the claim names — reported separately, for R3.
 *
 * <p>What this cannot catch is stated in §8.5 and belongs in any write-up: a
 * chain made entirely of true claims can still tell the wrong story. Rules 3 and
 * 4 close the common cases; the rest is human evaluation.
 */
public final class DiagnosisVerifier {

    private DiagnosisVerifier() {}

    public enum Mode { CLAIM, CITATION }

    /** @param claim index of the offending claim in the chain, or null for the whole diagnosis */
    public record Violation(int rule, Integer claim, String message) {}

    public record Report(boolean passed, List<Violation> violations,
                         int claims, int claimsTrue) {}

    // Causal language needs a counterfactual behind it; "already"/"ignored" needs
    // the threat to have existed (rule 4).
    private static final Pattern CAUSAL = Pattern.compile(
            "\\b(allow|allows|allowed|enable|enables|enabled|because|create|creates|created|lets)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern THREAT_WORDS = Pattern.compile(
            "\\b(already|ignore|ignores|ignored)\\b", Pattern.CASE_INSENSITIVE);

    // A SAN move or a bare square, after move numbers are stripped.
    private static final Pattern MOVE_OR_SQUARE = Pattern.compile(
            "(?<![A-Za-z0-9])(O-O-O|O-O|[KQRBN][a-h]?[1-8]?x?[a-h][1-8]|[a-h]x[a-h][1-8](?:=[QRBN])?|[a-h][1-8](?:=[QRBN])?)[+#]?(?![A-Za-z0-9])");
    private static final Pattern SQUARE = Pattern.compile("[a-h][1-8]");

    /** Argument keys whose values are squares — the only ones citation mode entity-matches. */
    private static final Set<String> SQUARE_KEYS = Set.of(
            "square", "defender", "by", "targets", "slider", "target", "attackers", "defenders");

    private static final Set<String> CAUSE_CLAIMS = Set.of(
            "THREAT_EXISTS", "DEFENDER_REMOVED", "MOVED_INTO_ATTACK", "LOSING_CAPTURE",
            "DOES_NOT_ADDRESS", "COUNTERFACTUAL", "TACTIC_GEOMETRY");

    public static Report verify(EvidenceGraph g, Diagnosis d, Mode mode) {
        List<Violation> v = new ArrayList<>();
        if (d == null) {
            return new Report(false, List.of(new Violation(1, null, "no diagnosis")), 0, 0);
        }
        List<Diagnosis.Claim> chain = d.reasoningChain() == null ? List.of() : d.reasoningChain();
        DiagnosisRules.Labels labels = DiagnosisRules.label(g);

        // ── rule 1: schema, closed vocabularies, chain length ───────────────
        if (chain.size() > Diagnosis.MAX_CHAIN) {
            v.add(new Violation(1, null, "chain has " + chain.size() + " steps; the limit is " + Diagnosis.MAX_CHAIN));
        }
        closed(v, "consequence", d.consequence(), Diagnosis.CONSEQUENCES);
        closed(v, "mechanism", d.mechanism(), Diagnosis.MECHANISMS);
        closed(v, "motif", d.motif(), Diagnosis.MOTIFS);
        closed(v, "visibility", d.visibility(), Diagnosis.VISIBILITIES);

        boolean[] wellFormed = new boolean[chain.size()];
        for (int i = 0; i < chain.size(); i++) {
            wellFormed[i] = schema(v, i, chain.get(i));
        }

        // ── rule 2: each claim is true of the graph and the board ───────────
        boolean[] truth = new boolean[chain.size()];
        int trueCount = 0;
        for (int i = 0; i < chain.size(); i++) {
            if (!wellFormed[i]) continue;
            String why = falsity(g, chain.get(i));
            if (why == null) {
                truth[i] = true;
                trueCount++;
            } else {
                v.add(new Violation(2, i, chain.get(i).type() + ": " + why));
            }
        }

        // ── rule 3: labels agree with the rules, or satisfy their preconditions ─
        if (d.consequence() != null && !d.consequence().equals(labels.consequence())) {
            v.add(new Violation(3, null, "consequence " + d.consequence() + ", evidence says " + labels.consequence()));
        }
        if (d.visibility() != null && !d.visibility().equals(labels.visibility())) {
            v.add(new Violation(3, null, "visibility " + d.visibility() + ", evidence says " + labels.visibility()));
        }
        if (labels.singleCause()) {
            if (!Objects.equals(d.mechanism(), labels.mechanism())) {
                v.add(new Violation(3, null, "mechanism " + d.mechanism() + ", the single rule that fires is " + labels.mechanism()));
            }
            if (!Objects.equals(d.motif(), labels.motif())) {
                v.add(new Violation(3, null, "motif " + d.motif() + ", evidence says " + labels.motif()));
            }
        } else if (labels.composite()) {
            // The composite subset: a model may name one of the causes whose
            // preconditions hold, or none. It may not name one whose don't.
            if (d.mechanism() != null && !"UNCLEAR".equals(d.mechanism())
                    && !labels.fired().contains(d.mechanism())) {
                v.add(new Violation(3, null, "mechanism " + d.mechanism()
                        + " — its preconditions do not hold; rules that do: " + labels.fired()));
            }
            // Not Set.of(motif, "OTHER"): that throws when the motif IS "OTHER",
            // which in the composite subset is the common case.
            Set<String> allowedMotifs = new HashSet<>();
            allowedMotifs.add(labels.motif());
            allowedMotifs.add("OTHER");
            g.geometry().forEach(x -> allowedMotifs.add(x.kind()));
            if (d.motif() != null && !allowedMotifs.contains(d.motif())) {
                v.add(new Violation(3, null, "motif " + d.motif() + " is not supported by the geometry"));
            }
        }

        // ── rule 4: causal words need a counterfactual; "already" needs a threat ─
        String prose = d.explanation() == null ? "" : d.explanation();
        if (CAUSAL.matcher(prose).find() && !hasTrue(chain, truth, "COUNTERFACTUAL")) {
            v.add(new Violation(4, null, "causal language without a verified COUNTERFACTUAL claim"));
        }
        if (THREAT_WORDS.matcher(prose).find() && !hasTrue(chain, truth, "THREAT_EXISTS")) {
            v.add(new Violation(4, null, "\"already\"/\"ignored\" without a verified THREAT_EXISTS claim"));
        }

        // ── rule 5: every move and square in the prose was earned by the chain ─
        String earned = earnedText(chain, truth);
        for (String token : tokens(prose)) {
            if (!earned.contains(token)) {
                v.add(new Violation(5, null, "\"" + token + "\" appears in the explanation but in no verified claim"));
            }
        }

        // ── rule 6: the critical response is R1 ─────────────────────────────
        if (!Objects.equals(norm(d.criticalResponse()), norm(g.reply().san()))) {
            v.add(new Violation(6, null, "critical_response " + d.criticalResponse()
                    + ", the critical reply is " + g.reply().san()));
        }

        // ── rule 7: citations exist and are about the claim's squares ────────
        if (mode == Mode.CITATION) {
            Map<String, EvidenceGraph.GraphItem> items = new java.util.HashMap<>();
            g.items().forEach(it -> items.put(it.id(), it));
            for (int i = 0; i < chain.size(); i++) {
                var c = chain.get(i);
                if (c.cites() == null || c.cites().isEmpty()) {
                    v.add(new Violation(7, i, "no citations"));
                    continue;
                }
                Set<String> cited = new HashSet<>();
                for (String id : c.cites()) {
                    var item = items.get(id);
                    if (item == null) v.add(new Violation(7, i, "cites " + id + ", which does not exist"));
                    else cited.addAll(item.squares());
                }
                for (String sq : claimSquares(c)) {
                    if (!cited.contains(sq)) {
                        v.add(new Violation(7, i, "names " + sq + ", which no cited item is about"));
                    }
                }
            }
        }

        // ── rule 8: no invented causes for positional mistakes ───────────────
        if ("NOT_CONCRETE".equals(labels.consequence())) {
            if (d.mechanism() != null && !"NONE".equals(d.mechanism())) {
                v.add(new Violation(8, null, "mechanism " + d.mechanism() + " asserted for a NOT_CONCRETE mistake"));
            }
            for (int i = 0; i < chain.size(); i++) {
                if (CAUSE_CLAIMS.contains(chain.get(i).type())) {
                    v.add(new Violation(8, i, chain.get(i).type() + " in a NOT_CONCRETE diagnosis"));
                }
            }
        }

        return new Report(v.isEmpty(), List.copyOf(v), chain.size(), trueCount);
    }

    // ── rule 1 ─────────────────────────────────────────────────────────────────

    private static void closed(List<Violation> v, String field, String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            v.add(new Violation(1, null, field + " " + value + " is not in the closed vocabulary"));
        }
    }

    private static boolean schema(List<Violation> v, int i, Diagnosis.Claim c) {
        if (c == null || c.type() == null || !Diagnosis.CLAIM_TYPES.contains(c.type())) {
            v.add(new Violation(1, i, "claim type " + (c == null ? null : c.type()) + " is not in the vocabulary"));
            return false;
        }
        Map<String, Object> args = c.args() == null ? Map.of() : c.args();
        for (String key : Diagnosis.REQUIRED_ARGS.getOrDefault(c.type(), Set.of())) {
            if (!args.containsKey(key) || args.get(key) == null) {
                v.add(new Violation(1, i, c.type() + " is missing \"" + key + "\""));
                return false;
            }
        }
        if ("COUNTERFACTUAL".equals(c.type())
                && !Diagnosis.COUNTERFACTUAL_EFFECTS.contains(String.valueOf(args.get("effect")))) {
            v.add(new Violation(1, i, "COUNTERFACTUAL effect " + args.get("effect") + " is not in the vocabulary"));
            return false;
        }
        return true;
    }

    // ── rule 2: claim truth ─────────────────────────────────────────────────────

    /** Null when the claim is true of the graph; otherwise why not. */
    static String falsity(EvidenceGraph g, Diagnosis.Claim c) {
        Map<String, Object> a = c.args();
        var r = g.reply();
        var t1 = g.t1();
        var cf = g.cf1();
        return switch (c.type()) {
            case "THREAT_EXISTS" -> {
                String move = str(a.get("move"));
                boolean replyWasThreat = t1.replyIsThreat() && sameMove(move, r.san());
                boolean probeThreat = t1.threatened() && sameMove(move, t1.moveSan());
                yield t1.probed() && (replyWasThreat || probeThreat) ? null
                        : move + " was not a threat before the move (probe: " + t1.moveSan()
                          + " worth " + t1.cost() + ")";
            }
            case "ATTACK_DEFENSE" -> attackDefense(g, a);
            case "DEFENDER_REMOVED" -> {
                String square = str(a.get("square"));
                String defender = str(a.get("defender"));
                boolean found = g.delta().stream().anyMatch(d ->
                        square.equals(d.square()) && d.defendersBefore() != null
                                && d.defendersBefore().contains(defender)
                                && (d.defendersAfter() == null || !d.defendersAfter().contains(defender)));
                if (!found) yield square + " did not lose " + defender + " as a defender";
                if (a.containsKey("move") && !sameMove(str(a.get("move")), g.played().san())) {
                    yield "the move is " + g.played().san() + ", not " + a.get("move");
                }
                yield null;
            }
            case "MOVED_INTO_ATTACK" -> str(a.get("square")).equals(g.played().to()) && g.played().landingSee() < 0
                    ? null : "the moved piece is not losing on " + a.get("square");
            case "LOSING_CAPTURE" -> sameMove(str(a.get("move")), g.played().san())
                    && g.played().captured() != null && g.played().captureSee() != null
                    && g.played().captureSee() < 0
                    ? null : a.get("move") + " is not a losing capture";
            case "BLOCKS_LINE" -> lineClaim(g, a, false);
            case "OPENS_LINE" -> lineClaim(g, a, true);
            // The threat is either R1 itself, or T1's threat carried out later in
            // the played line (DiagnosisRules.threatCarriedOut).
            case "DOES_NOT_ADDRESS" -> sameMove(str(a.get("move")), g.played().san())
                    && ((sameMove(str(a.get("threat")), r.san())
                         && (r.mate() || r.netLoss() > 0 || g.playedLine().mateAgainstPlayer()))
                        || (sameMove(str(a.get("threat")), t1.moveSan()) && DiagnosisRules.threatCarriedOut(g)))
                    ? null : "after " + a.get("move") + ", " + a.get("threat") + " is not what punishes it";
            case "CRITICAL_REPLY" -> {
                if (!sameMove(str(a.get("move")), r.san())) yield "the critical reply is " + r.san();
                if (a.containsKey("after") && !sameMove(str(a.get("after")), g.played().san())) {
                    yield "the move played was " + g.played().san();
                }
                if (a.containsKey("result")) {
                    boolean mate = r.mate() || g.playedLine().mateAgainstPlayer();
                    String actual = mate ? "MATE" : r.netLoss() > 0 ? "MATERIAL" : "NONE";
                    if (!actual.equals(str(a.get("result")))) yield "result is " + actual;
                }
                if (a.containsKey("ply") && num(a.get("ply")) != r.consequencePly()) {
                    yield "consequence ply is " + r.consequencePly();
                }
                yield null;
            }
            case "MATERIAL_CHANGE" -> {
                boolean best = "BEST".equals(str(a.get("line")));
                int actual = best ? g.bestLine().netLoss() : r.netLoss();
                yield num(a.get("amount")) == actual ? null : "net material is " + actual;
            }
            case "MATE_IN" -> {
                boolean best = "BEST".equals(str(a.get("line")));
                Integer actual = best ? g.bestLine().mateIn() : (r.mate() ? r.mateIn() : null);
                yield actual != null && num(a.get("n")) == actual ? null : "mate in " + actual;
            }
            case "COUNTERFACTUAL" -> {
                if (!sameMove(str(a.get("alt_move")), g.best().san())) yield "the engine's move is " + g.best().san();
                if (a.containsKey("reply") && !sameMove(str(a.get("reply")), r.san())) {
                    yield "the reply is " + r.san();
                }
                if (a.containsKey("played") && !sameMove(str(a.get("played")), g.played().san())) {
                    yield "the move played was " + g.played().san();
                }
                boolean holds = switch (str(a.get("effect"))) {
                    case "REPLY_ILLEGAL" -> r.san() != null && !cf.bestIsPlayed() && !cf.legalAfterBest();
                    case "REPLY_FAILS" -> cf.legalAfterBest() && cf.parries();
                    case "WINS_MATERIAL" -> g.bestLine().netLoss() <= -1;
                    case "MATES" -> g.bestLine().mateForPlayer();
                    default -> false;
                };
                yield holds ? null : a.get("effect") + " does not hold after " + g.best().san();
            }
            case "TACTIC_GEOMETRY" -> {
                String kind = str(a.get("kind"));
                String by = str(a.get("by"));
                Set<String> targets = new HashSet<>(strings(a.get("targets")));
                boolean found = g.geometry().stream().anyMatch(x -> x.kind().equals(kind)
                        && x.by().equals(by) && new HashSet<>(x.targets()).equals(targets));
                if (!found) yield "no " + kind + " by " + by + " on " + targets;
                if (a.containsKey("move") && !sameMove(str(a.get("move")), r.san())) {
                    yield "the geometry belongs to " + r.san();
                }
                yield null;
            }
            case "VISIBILITY" -> {
                if (!g.d1().band().equals(str(a.get("band")))) yield "visibility is " + g.d1().band();
                if (a.containsKey("depth") && a.get("depth") != null
                        && !Objects.equals(num(a.get("depth")), g.d1().depth())) {
                    yield "visibility depth is " + g.d1().depth();
                }
                yield null;
            }
            default -> "unknown claim type";
        };
    }

    /** ATTACK_DEFENSE, checked on the board itself: P0 by default, PP on request. */
    private static String attackDefense(EvidenceGraph g, Map<String, Object> a) {
        Board board = Attacks.at(g.header().fen());
        if ("PP".equals(str(a.get("position")))) {
            try {
                board.doMove(g.played().san());
            } catch (Exception e) {
                return "cannot replay the played move";
            }
        }
        Square square;
        try {
            square = Square.valueOf(str(a.get("square")).toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return "not a square: " + a.get("square");
        }
        Piece piece = board.getPiece(square);
        if (piece == null || piece == Piece.NONE) return "nothing stands on " + a.get("square");

        Set<String> attackers = squaresOf(Attacks.attackers(board, square, piece.getPieceSide().flip()));
        Set<String> defenders = squaresOf(Attacks.defenders(board, square));
        Set<String> claimedA = squaresIn(strings(a.get("attackers")));
        Set<String> claimedD = squaresIn(strings(a.get("defenders")));
        if (!claimedA.equals(attackers)) return "attackers of " + square + " are " + attackers;
        if (!claimedD.equals(defenders)) return "defenders of " + square + " are " + defenders;
        return null;
    }

    /** A line claim matches M's Δ, or one of B's effects — "g6 blocks the diagonal" is about B. */
    private static String lineClaim(EvidenceGraph g, Map<String, Object> a, boolean opened) {
        String slider = str(a.get("slider"));
        String target = str(a.get("target"));
        String kind = opened ? "LINE_OPENED" : "LINE_BLOCKED";
        boolean inDelta = g.delta().stream().anyMatch(d -> kind.equals(d.kind())
                && slider.equals(d.slider()) && target.equals(d.target()));
        boolean inEffects = !opened && g.bestEffects().stream().anyMatch(e -> "BLOCKS_LINE".equals(e.kind())
                && e.squares().size() >= 2 && e.squares().get(0).equals(slider) && e.squares().get(1).equals(target));
        return inDelta || inEffects ? null : "no line " + (opened ? "opened" : "blocked") + " from " + slider + " to " + target;
    }

    // ── rules 4 and 5 helpers ────────────────────────────────────────────────

    private static boolean hasTrue(List<Diagnosis.Claim> chain, boolean[] truth, String type) {
        for (int i = 0; i < chain.size(); i++) {
            if (truth[i] && type.equals(chain.get(i).type())) return true;
        }
        return false;
    }

    /** Every argument value of every verified claim, normalised, as one searchable string. */
    private static String earnedText(List<Diagnosis.Claim> chain, boolean[] truth) {
        StringBuilder s = new StringBuilder(" ");
        for (int i = 0; i < chain.size(); i++) {
            if (!truth[i] || chain.get(i).args() == null) continue;
            for (Object value : chain.get(i).args().values()) {
                for (String part : strings(value)) s.append(norm(part)).append(' ');
            }
        }
        return s.toString();
    }

    /** Moves and squares named in prose, move numbers stripped, check marks dropped. */
    static Set<String> tokens(String prose) {
        String stripped = prose.replaceAll("\\b\\d+\\.(\\.\\.)?\\s*", "");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = MOVE_OR_SQUARE.matcher(stripped);
        while (m.find()) out.add(norm(m.group(1)));
        return out;
    }

    private static Set<String> claimSquares(Diagnosis.Claim c) {
        Set<String> out = new LinkedHashSet<>();
        if (c.args() == null) return out;
        for (var e : c.args().entrySet()) {
            if (!SQUARE_KEYS.contains(e.getKey())) continue;
            out.addAll(squaresIn(strings(e.getValue())));
        }
        return out;
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private static Set<String> squaresOf(Collection<Square> squares) {
        Set<String> out = new HashSet<>();
        for (Square s : squares) out.add(s.name().toLowerCase(Locale.ROOT));
        return out;
    }

    /** The square each token refers to: "Qh5" → h5, "e8" → e8. */
    private static Set<String> squaresIn(List<String> tokens) {
        Set<String> out = new HashSet<>();
        for (String t : tokens) {
            Matcher m = SQUARE.matcher(t);
            String last = null;
            while (m.find()) last = m.group();
            if (last != null) out.add(last);
        }
        return out;
    }

    private static boolean sameMove(String a, String b) {
        return a != null && b != null && norm(a).equals(norm(b));
    }

    /** SAN compared without check marks, annotations or a leading move number. */
    static String norm(String san) {
        if (san == null) return null;
        return san.trim().replaceAll("^\\d+\\.(\\.\\.)?\\s*", "").replaceAll("[+#!?]", "");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    private static List<String> strings(Object o) {
        if (o == null) return List.of();
        if (o instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(o));
    }
}
