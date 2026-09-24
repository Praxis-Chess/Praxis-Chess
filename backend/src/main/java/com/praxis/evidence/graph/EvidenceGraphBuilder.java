package com.praxis.evidence.graph;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.praxis.evidence.Attacks;
import com.praxis.evidence.ConsequenceWalk;
import com.praxis.evidence.DepthCurve;
import com.praxis.evidence.PositionDiff;
import com.praxis.evidence.See;
import com.praxis.evidence.TacticGeometry;
import com.praxis.evidence.ThreatProbe;
import com.praxis.service.analysis.MistakeCandidateFilter;
import com.praxis.service.analysis.StockfishService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds the evidence graph for one mistake: FEN + move + engine in, graph out.
 *
 * <p><b>Database-free on purpose</b> (§17.1). Nothing here knows about games,
 * users or Spring, so the same code runs inside Praxis, in the headless
 * {@link GraphCli}, and — through the dev endpoint — for the Python training
 * pipeline. One implementation is what makes train/serve skew impossible.
 *
 * <p>Five searches, all at the same depth on a deterministic engine:
 * <ol>
 *   <li>P0 unrestricted — its value, the best move B, and B's line.</li>
 *   <li>P0 restricted to the played move — its value, its depth curve (D1), and
 *       the refutation R1 as the tail of its line.</li>
 *   <li>The null-move probe at P0 (T1).</li>
 *   <li>R1 played from the null-move position — was it already a threat?</li>
 *   <li>R1 played after B (CF1) — does the engine's move stop it?</li>
 * </ol>
 * Everything else is board arithmetic on the results.
 *
 * <p>The thresholds are the ones Phase 2 settled on against real games; the
 * reasons for each are in {@code EvidenceExplainService}.
 */
public final class EvidenceGraphBuilder {

    /**
     * 2: T1's "reply was already a threat" test changed (see
     * {@link #replyIsThreat}). Graphs of an older version are rebuilt, not reread.
     */
    public static final int GRAPH_VERSION = 2;
    public static final int DEFAULT_DEPTH = 14;

    /** A free move worth less than this to the opponent is not a threat. */
    public static final double THREAT_PAWNS = 1.0;
    /**
     * A quiet R1 must be worth at least this share of the opponent's best free
     * move to count as a threat of its own.
     */
    public static final double QUIET_REPLY_SHARE = 0.5;
    /** The engine reports mate as ±100; anything past this is a mate score. */
    public static final double MATE_SCORE = 90.0;

    private final StockfishService engine;
    private final int depth;

    public EvidenceGraphBuilder(StockfishService engine, int depth) {
        this.engine = engine;
        this.depth = depth;
    }

    /**
     * The graph, plus the in-memory objects the Phase 2 lab still renders from.
     */
    public record Built(EvidenceGraph graph, ConsequenceWalk.Consequence replyConsequence,
                        List<TacticGeometry.Tactic> replyTactics, ThreatProbe.Threat threat,
                        long engineMs) {}

    /**
     * @param moveToken the played move, UCI or SAN
     * @param ply       ply index of the move in its game (odd is White)
     */
    public Optional<Built> build(String fen, String moveToken, int ply, String phase, String severity) {
        Board p0;
        try {
            p0 = Attacks.at(fen);
        } catch (Exception e) {
            return Optional.empty();
        }
        Side player = p0.getSideToMove();
        boolean white = player == Side.WHITE;
        Move m = resolve(p0, moveToken);
        if (m == null) return Optional.empty();
        String mUci = uci(m);
        String mSan = Attacks.san(p0, m);

        Board pp = Attacks.at(fen);
        if (!pp.doMove(m)) return Optional.empty();

        StockfishService.Search s0;
        StockfishService.Search sM;
        ThreatProbe.Threat threat;
        StockfishService.Search replyAsThreat = null;
        StockfishService.Search cf = null;
        List<String> replyLine;
        long engineStart = System.currentTimeMillis();

        // One engine, several callers: the whole sequence is one critical section,
        // with the hash cleared first so this mistake's evidence cannot depend on
        // what was searched before it.
        synchronized (engine) {
            engine.clearHash();

            s0 = engine.search(fen, depth);
            if (s0.bestMoveUci() == null || s0.score() == null) return Optional.empty();

            sM = engine.search(fen, depth, List.of(mUci));
            if (sM.score() == null) return Optional.empty();

            replyLine = tail(sM.pv(), mUci);
            if (replyLine.isEmpty() && !pp.isMated() && !pp.isDraw()) {
                replyLine = engine.search(pp.getFen(), depth).pv();
            }
            String replyUci = replyLine.isEmpty() ? null : replyLine.get(0);

            threat = new ThreatProbe(engine).probe(fen, depth);
            if (replyUci != null && threat.probed()) {
                String nullFen = ThreatProbe.pass(fen);
                if (nullFen != null && legalIn(nullFen, replyUci)) {
                    replyAsThreat = engine.search(nullFen, depth, List.of(replyUci));
                }
            }

            Move b = resolve(p0, s0.bestMoveUci());
            if (b != null && replyUci != null && !uci(b).equals(mUci)) {
                Board pb = Attacks.at(fen);
                pb.doMove(b);
                if (legalIn(pb.getFen(), replyUci)) {
                    cf = engine.search(pb.getFen(), depth, List.of(replyUci));
                }
            }
        }
        long engineMs = System.currentTimeMillis() - engineStart;

        double parent = s0.score();
        double playedScore = sM.score();
        Move b = resolve(p0, s0.bestMoveUci());
        if (b == null) return Optional.empty();

        // ── nodes ────────────────────────────────────────────────────────────
        var header = new EvidenceGraph.Header(fen, white ? "WHITE" : "BLACK", ply, phase, severity,
                depth, "stockfish · deterministic · depth " + depth);
        var p0Node = new EvidenceGraph.PositionNode(parent, winPct(parent, white),
                material(p0, Side.WHITE), material(p0, Side.BLACK), white ? "WHITE" : "BLACK");

        var bestNode = moveNode(p0, b, parent, white);
        var playedNode = moveNode(p0, m, playedScore, white);

        // R1 and PC, counted from after the played move with its own capture at ply 0.
        Piece capturedByM = p0.getPiece(m.getTo());
        var replyC = ConsequenceWalk.of(pp, replyLine, player, capturedByM, m.getTo(), mSan);
        Move r1 = replyLine.isEmpty() ? null : resolve(pp, replyLine.get(0));
        var reply = new EvidenceGraph.Reply(
                r1 == null ? null : uci(r1), replyC.replySan(),
                r1 == null ? null : sq(r1.getFrom()), r1 == null ? null : sq(r1.getTo()),
                replyC.replyGivesCheck(), replyC.lineSan(),
                taken(replyC.losses()), taken(replyC.gains()),
                replyC.materialSwing(), replyC.mate(), replyC.mateIn(), replyC.consequencePly());

        // The two lines the labels compare, built the same way: a move plus eight
        // plies, the move's own capture counted at ply 0. The played side IS the
        // reply walk above, so the consequence label and the MATERIAL_CHANGE claim
        // read one number and cannot disagree.
        List<String> playedFull = new ArrayList<>();
        playedFull.add(mUci);
        playedFull.addAll(replyLine);
        var playedLine = new EvidenceGraph.LineOutcome(prepend(mSan, replyC.lineSan()),
                replyC.materialSwing(), mateFor(playedScore, white), mateFor(-playedScore, white),
                replyC.mateIn());

        String bSan = Attacks.san(p0, b);
        Board pb = Attacks.at(fen);
        pb.doMove(b);
        var bestC = ConsequenceWalk.of(pb, tail(s0.pv(), uci(b)), player,
                p0.getPiece(b.getTo()), b.getTo(), bSan);
        var bestLine = new EvidenceGraph.LineOutcome(prepend(bSan, bestC.lineSan()),
                bestC.materialSwing(), mateFor(parent, white), mateFor(-parent, white),
                bestC.mateIn());

        // CF1.
        boolean bestIsPlayed = uci(b).equals(mUci);
        Double lossAfterBest = cf == null || cf.score() == null ? null : loss(white, parent, cf.score());
        boolean legalAfterBest = cf != null;
        boolean parries = !bestIsPlayed && r1 != null
                && (!legalAfterBest || (lossAfterBest != null && lossAfterBest < THREAT_PAWNS));
        var cf1 = new EvidenceGraph.Counterfactual(reply.uci(), reply.san(), bestIsPlayed,
                legalAfterBest, round(lossAfterBest), parries);

        // T1.
        Double threatCost = threat.probed() && threat.score() != null
                ? loss(white, parent, threat.score()) : null;
        Double replyCost = replyAsThreat == null || replyAsThreat.score() == null
                ? null : loss(white, parent, replyAsThreat.score());
        boolean threatened = threatCost != null && threatCost >= THREAT_PAWNS;
        // When R1 IS the probe's move, identity answers the question, not a second
        // search. On a real game the probe valued d5 at 1.1 and the restricted
        // search valued the same d5 at 0.9 — search noise on either side of the
        // one-pawn bar deciding whether a move was the threat it literally was.
        boolean sameMove = threat.moveUci() != null && r1 != null
                && threat.moveUci().equalsIgnoreCase(uci(r1));
        boolean replyIsThreat = sameMove ? threatened
                : replyIsThreat(replyCost, threatCost, replyC.replySan() != null && replyC.replySan().contains("x"));
        String threatSan = null;
        if (threat.moveUci() != null) {
            String nullFen = ThreatProbe.pass(fen);
            Board nb = nullFen == null ? null : Attacks.at(nullFen);
            Move tm = nb == null ? null : resolve(nb, threat.moveUci());
            threatSan = tm == null ? threat.moveUci() : Attacks.san(nb, tm);
        }
        var t1 = new EvidenceGraph.ThreatNode(threat.probed(), threat.skipReason(), threat.moveUci(),
                threatSan, round(threatCost), round(replyCost), threatened, replyIsThreat);

        // Δ, geometry, effects.
        var delta = delta(p0, m);
        List<TacticGeometry.Tactic> tactics = r1 == null ? List.of() : TacticGeometry.after(pp, r1);
        List<EvidenceGraph.GeometryItem> geometry = new ArrayList<>();
        for (var t : tactics) {
            geometry.add(new EvidenceGraph.GeometryItem(t.kind().name(), sq(t.by()),
                    pieceName(t.piece()), t.targets().stream().map(EvidenceGraphBuilder::sq).toList()));
        }
        var bestEffects = effects(p0, b);
        var movedEffects = effects(p0, m);

        // D1: like for like, against a bar scaled to how bad the move finally is.
        double finalLoss = loss(white, parent, playedScore);
        var vis = DepthCurve.visibilityDepth(sM.depthCurve(), s0.depthCurve(), white,
                DepthCurve.threshold(finalLoss));
        var d1 = new EvidenceGraph.VisibilityNode(vis.isPresent() ? vis.getAsInt() : null,
                DepthCurve.classify(vis).name(), round(finalLoss), round(DepthCurve.threshold(finalLoss)));

        var graph = new EvidenceGraph(GRAPH_VERSION, header, p0Node, bestNode, playedNode, reply,
                playedLine, bestLine, cf1, t1, delta, geometry, bestEffects, movedEffects, d1,
                backRankMate(p0, playedFull));

        return Optional.of(new Built(graph, replyC, tactics, threat, engineMs));
    }

    /**
     * Was R1 already a threat before the move, when it is not the probe's own move?
     *
     * <p>It must be worth {@link #THREAT_PAWNS} as a free move. A quiet R1 must
     * also be worth {@link #QUIET_REPLY_SHARE} of the opponent's best free move. A
     * free tempo flatters almost any quiet move in a position that is already going
     * wrong: in Phase 2, {@code g3} cleared the bar when the real threat was
     * {@code Bxb7}, worth 3.1. A capture that wins a pawn as a free move is a
     * threat whatever else is on the board, so it needs no share.
     *
     * <p>Version 1 asked R1 to come within half a pawn of the best free move
     * instead. That made it "the" threat, not "a" threat. On 100 hand-labelled
     * mistakes it missed real threats whenever a bigger one was also on the board:
     * {@code Qxc1} worth 2.8 beside {@code Nxb3} worth 4.4, and {@code exf2+}
     * beside {@code exd2+}. Those mistakes were then called CREATED_TACTIC, for
     * tactics that were already there.
     */
    static boolean replyIsThreat(Double replyCost, Double threatCost, boolean capture) {
        if (replyCost == null || threatCost == null || replyCost < THREAT_PAWNS) return false;
        return capture || replyCost >= threatCost * QUIET_REPLY_SHARE;
    }

    // ── nodes ────────────────────────────────────────────────────────────────

    private static EvidenceGraph.MoveNode moveNode(Board p0, Move move, double scoreAfter, boolean white) {
        Piece moving = p0.getPiece(move.getFrom());
        Piece victim = p0.getPiece(move.getTo());
        boolean capture = victim != null && victim != Piece.NONE;
        Board after = Attacks.at(p0.getFen());
        after.doMove(move);
        return new EvidenceGraph.MoveNode(uci(move), Attacks.san(p0, move),
                sq(move.getFrom()), sq(move.getTo()), pieceName(moving),
                round(scoreAfter), round(winPct(scoreAfter, white)),
                capture ? pieceName(victim) : null,
                capture ? See.capture(p0, move.getFrom(), move.getTo()) : null,
                See.onSquare(after, move.getTo()),
                after.isKingAttacked());
    }

    /**
     * True when {@code scoreWhite} is a forced mate for the player. Pass the
     * negated score to ask about a mate against them.
     */
    private static boolean mateFor(double scoreWhite, boolean white) {
        return white ? scoreWhite >= MATE_SCORE : scoreWhite <= -MATE_SCORE;
    }

    private static List<String> prepend(String first, List<String> rest) {
        List<String> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    /**
     * Δ for the pieces that did not move, plus lines. The moved piece's own
     * fate goes in M.e*, where MOVED_INTO_ATTACK reads it.
     */
    private static List<EvidenceGraph.DeltaItem> delta(Board p0, Move m) {
        var diff = PositionDiff.of(p0, m);
        List<EvidenceGraph.DeltaItem> out = new ArrayList<>();
        for (var change : diff.pieces()) {
            if (change.square() == m.getTo()) continue;   // the mover
            String kind;
            if (change.nowLoose()) kind = "NOW_LOOSE";
            else if (!change.defendersLost().isEmpty()) kind = "DEFENDER_REMOVED";
            else if (!change.defendersGained().isEmpty()) kind = "DEFENDER_GAINED";
            else continue;   // SEE moved without a defender change: shown via NOW_LOOSE when it matters
            out.add(new EvidenceGraph.DeltaItem(kind, sq(change.square()), pieceName(change.piece()),
                    side(change.piece()), change.seeBefore(), change.seeAfter(),
                    change.defendersBefore().stream().map(EvidenceGraphBuilder::sq).toList(),
                    change.defendersAfter().stream().map(EvidenceGraphBuilder::sq).toList(),
                    null, null));
        }
        for (var line : diff.lines()) {
            out.add(new EvidenceGraph.DeltaItem(line.opened() ? "LINE_OPENED" : "LINE_BLOCKED",
                    null, pieceName(line.piece()), side(line.piece()), null, null, null, null,
                    sq(line.slider()), sq(line.target())));
        }
        return out;
    }

    /** What a move does in its own right: lands, captures, attacks, checks, lines. */
    private static List<EvidenceGraph.EffectItem> effects(Board p0, Move move) {
        var diff = PositionDiff.of(p0, move);
        List<EvidenceGraph.EffectItem> out = new ArrayList<>();
        String san = Attacks.san(p0, move);
        String to = sq(move.getTo());
        var mover = diff.pieces().get(0);

        if (diff.captured() != null && diff.captured() != Piece.NONE) {
            int see = See.capture(p0, move.getFrom(), move.getTo());
            out.add(new EvidenceGraph.EffectItem("CAPTURES", List.of(to),
                    san + " captures the " + pieceName(diff.captured()) + " on " + to + " · exchange " + signed(see)));
        }
        out.add(new EvidenceGraph.EffectItem("LANDS", List.of(to),
                san + " lands on " + to + " · SEE there " + signed(mover.seeAfter())
                        + (mover.seeAfter() < 0 ? " (can be won)" : "")));
        if (diff.givesCheck()) {
            out.add(new EvidenceGraph.EffectItem("CHECK", List.of(to), san + " gives check"));
        }
        for (Square target : diff.newAttacks()) {
            Piece piece = p0.getPiece(target);
            out.add(new EvidenceGraph.EffectItem("ATTACKS", List.of(to, sq(target)),
                    san + " attacks the " + pieceName(piece) + " on " + sq(target)));
        }
        for (var line : diff.lines()) {
            // Only the lines the move closes for the OTHER side: that is what
            // "blocks the diagonal" means as a reason.
            if (line.opened() || line.piece().getPieceSide() == p0.getSideToMove()) continue;
            out.add(new EvidenceGraph.EffectItem("BLOCKS_LINE",
                    List.of(sq(line.slider()), sq(line.target())),
                    san + " blocks the " + pieceName(line.piece()) + " on " + sq(line.slider())
                            + " from " + sq(line.target())));
        }
        return out;
    }

    /** Mate delivered on the back rank by a rook or queen on that rank. */
    private static boolean backRankMate(Board p0, List<String> line) {
        Board board = Attacks.at(p0.getFen());
        Move last = null;
        for (String uci : line) {
            Move move = resolve(board, uci);
            if (move == null || !board.doMove(move)) return false;
            last = move;
            if (board.isMated()) break;
        }
        if (last == null || !board.isMated()) return false;
        Side mated = board.getSideToMove();
        Square king = board.getKingSquare(mated);
        int backRank = mated == Side.WHITE ? 0 : 7;
        Piece mater = board.getPiece(last.getTo());
        boolean heavy = mater != null && (mater.getPieceType() == PieceType.ROOK
                || mater.getPieceType() == PieceType.QUEEN);
        return king.getRank().ordinal() == backRank && heavy
                && last.getTo().getRank().ordinal() == backRank;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** UCI or SAN to a legal move. */
    public static Move resolve(Board board, String token) {
        if (token == null || token.isBlank()) return null;
        String t = token.trim();
        for (Move move : board.legalMoves()) {
            if (uci(move).equalsIgnoreCase(t)) return move;
        }
        String bare = t.replaceAll("[+#!?]", "");
        for (Move move : board.legalMoves()) {
            if (Attacks.san(board, move).replaceAll("[+#!?]", "").equals(bare)) return move;
        }
        return null;
    }

    /** UCI with a lowercase promotion letter, which is what the engine expects. */
    static String uci(Move move) {
        return move.toString().toLowerCase(Locale.ROOT);
    }

    private static List<String> tail(List<String> pv, String first) {
        if (pv.size() > 1 && pv.get(0).equalsIgnoreCase(first)) return pv.subList(1, pv.size());
        return List.of();
    }

    private static boolean legalIn(String fen, String uci) {
        try {
            return resolve(Attacks.at(fen), uci) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<EvidenceGraph.Taken> taken(List<ConsequenceWalk.Loss> list) {
        return list.stream()
                .map(l -> new EvidenceGraph.Taken(pieceName(l.piece()), sq(l.square()), l.san(), l.ply(), l.value()))
                .toList();
    }

    private static int material(Board board, Side side) {
        int total = 0;
        for (Square s : Square.values()) {
            if (s == Square.NONE) continue;
            Piece piece = board.getPiece(s);
            if (piece == null || piece == Piece.NONE || piece.getPieceSide() != side) continue;
            int value = See.value(piece);
            if (value < 1_000) total += value;
        }
        return total;
    }

    static double loss(boolean white, double before, double after) {
        return white ? before - after : after - before;
    }

    private static double winPct(double scoreWhite, boolean white) {
        double w = MistakeCandidateFilter.winPct(scoreWhite);
        return white ? w : 100 - w;
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 100) / 100.0;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    static String sq(Square square) {
        return square == null ? null : square.name().toLowerCase(Locale.ROOT);
    }

    private static String pieceName(Piece piece) {
        return piece == null || piece == Piece.NONE ? null
                : piece.getPieceType().name().toLowerCase(Locale.ROOT);
    }

    private static String side(Piece piece) {
        return piece == null || piece == Piece.NONE ? null : piece.getPieceSide().name();
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }
}
