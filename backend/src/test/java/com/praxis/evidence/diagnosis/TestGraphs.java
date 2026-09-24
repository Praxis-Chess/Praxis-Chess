package com.praxis.evidence.diagnosis;

import com.praxis.evidence.graph.EvidenceGraph;
import com.praxis.evidence.graph.EvidenceGraph.*;

import java.util.List;

/**
 * Hand-built graphs, so the rules and the verifier can be tested without an
 * engine — and so they keep being tested in CI, which has no Stockfish.
 *
 * Each one is shaped like a real builder output for a real position, and says
 * which facts it asserts. The engine-backed tests check the builder produces
 * graphs like these; these check what the rules and verifier do with them.
 */
public final class TestGraphs {

    private TestGraphs() {}

    public static final String SCHOLAR_FEN = "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3";

    /**
     * §6.7: after 1.e4 e5 2.Bc4 Nc6 3.Qh5, Black plays 3...Nf6?? and 4.Qxf7# is
     * mate. The mate was threatened before the move; 3...g6 would have blocked it.
     */
    public static EvidenceGraph scholarsMate() {
        return new EvidenceGraph(1,
                new Header(SCHOLAR_FEN, "BLACK", 6, "OPENING", "BLUNDER", 14, "test"),
                new PositionNode(0.3, 47, 39, 39, "BLACK"),
                new MoveNode("g7g6", "g6", "g7", "g6", "pawn", 0.3, 47.0, null, null, 0, false),
                new MoveNode("g8f6", "Nf6", "g8", "f6", "knight", 100.0, 0.0, null, null, 0, false),
                new Reply("h5f7", "Qxf7#", "h5", "f7", true, List.of("Qxf7#"),
                        List.of(new Taken("pawn", "f7", "Qxf7#", 1, 1)), List.of(), 1, true, 1, 1),
                new LineOutcome(List.of("Nf6", "Qxf7#"), 1, false, true, 1),
                new LineOutcome(List.of("g6", "Qf3", "Nf6"), 0, false, false, null),
                new Counterfactual("h5f7", "Qxf7#", false, false, null, true),
                new ThreatNode(true, null, "h5f7", "Qxf7#", 99.7, 99.7, true, true),
                List.of(),
                List.of(),
                List.of(new EffectItem("ATTACKS", List.of("g6", "h5"), "g6 attacks the queen on h5"),
                        new EffectItem("BLOCKS_LINE", List.of("h5", "f7"), "g6 blocks the queen on h5 from f7")),
                List.of(new EffectItem("LANDS", List.of("f6"), "Nf6 lands on f6 · SEE there 0"),
                        new EffectItem("ATTACKS", List.of("f6", "h5"), "Nf6 attacks the queen on h5")),
                new VisibilityNode(1, "SHALLOW", 99.7, 49.85),
                false);
    }

    /**
     * White plays Nd5 where a pawn takes it for nothing. No threat before the
     * move; exd5 was not even possible before it, so it cannot have been one.
     */
    public static EvidenceGraph hangingKnight() {
        return new EvidenceGraph(1,
                new Header("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3",
                        "WHITE", 5, "OPENING", "BLUNDER", 14, "test"),
                new PositionNode(0.2, 52, 39, 39, "WHITE"),
                new MoveNode("g1f3", "Nf3", "g1", "f3", "knight", 0.2, 52.0, null, null, 0, false),
                new MoveNode("c3d5", "Nd5", "c3", "d5", "knight", -2.8, 18.0, null, null, -3, false),
                new Reply("e6d5", "exd5", "e6", "d5", false, List.of("exd5", "exd5"),
                        List.of(new Taken("knight", "d5", "exd5", 1, 3)), List.of(), 3, false, null, 1),
                new LineOutcome(List.of("Nd5", "exd5", "exd5"), 3, false, false, null),
                new LineOutcome(List.of("Nf3", "Nf6"), 0, false, false, null),
                new Counterfactual("e6d5", "exd5", false, false, null, true),
                new ThreatNode(true, null, "d7d6", "d6", 0.2, null, false, false),
                List.of(),
                List.of(),
                List.of(),
                List.of(new EffectItem("LANDS", List.of("d5"), "Nd5 lands on d5 · SEE there -3 (can be won)")),
                new VisibilityNode(2, "SHALLOW", 3.0, 1.5),
                false);
    }

    /** No material and no mate within the horizon: positional. V1 abstains. */
    public static EvidenceGraph positional() {
        var g = hangingKnight();
        return new EvidenceGraph(1, g.header(), g.p0(), g.best(),
                new MoveNode("a2a3", "a3", "a2", "a3", "pawn", -0.4, 44.0, null, null, 0, false),
                new Reply("d7d5", "d5", "d7", "d5", false, List.of("d5", "exd5", "Nxd5"),
                        List.of(), List.of(), 0, false, null, 0),
                new LineOutcome(List.of("a3", "d5"), 0, false, false, null),
                g.bestLine(),
                new Counterfactual("d7d5", "d5", false, true, 0.3, true),
                g.t1(), List.of(), List.of(), List.of(), List.of(),
                new VisibilityNode(9, "MEDIUM", 0.6, 0.3), false);
    }

    /**
     * Both MOVED_INTO_ATTACK and REMOVED_DEFENDER hold: the knight left c3, where
     * it was the bishop on b5's only defender, and landed where it can be won.
     * Two causes — the composite subset, where the rules name neither.
     */
    public static EvidenceGraph twoCauses() {
        var g = hangingKnight();
        var delta = List.of(new DeltaItem("NOW_LOOSE", "b5", "bishop", "WHITE", 0, -3,
                List.of("c3"), List.of(), null, null));
        return new EvidenceGraph(1, g.header(), g.p0(), g.best(), g.played(), g.reply(),
                g.playedLine(), g.bestLine(), g.cf1(), g.t1(), delta, g.geometry(),
                g.bestEffects(), g.movedEffects(), g.d1(), false);
    }

    /** The engine's move wins a knight; the move played does not. */
    public static EvidenceGraph missedWin() {
        var g = hangingKnight();
        return new EvidenceGraph(1, g.header(), g.p0(),
                new MoveNode("f3e5", "Nxe5", "f3", "e5", "knight", 3.1, 88.0, "knight", 3, 0, false),
                new MoveNode("h2h3", "h3", "h2", "h3", "pawn", 0.1, 51.0, null, null, 0, false),
                new Reply("g8f6", "Nf6", "g8", "f6", false, List.of("Nf6"), List.of(), List.of(), 0, false, null, 0),
                new LineOutcome(List.of("h3", "Nf6"), 0, false, false, null),
                new LineOutcome(List.of("Nxe5", "Nxe5"), -3, false, false, null),
                new Counterfactual("g8f6", "Nf6", false, true, 0.0, true),
                g.t1(), List.of(), List.of(), List.of(), List.of(),
                new VisibilityNode(3, "SHALLOW", 3.0, 1.5), false);
    }

    /**
     * The opponent already threatened something else — a mate the move deals with
     * — and the move walks into a new fork. Shaped on the real `Kf8`/`Nf6` cases
     * from the first survey, where "a threat existed" wrongly blocked "the move
     * created this one".
     */
    public static EvidenceGraph differentThreat() {
        var g = hangingKnight();
        return new EvidenceGraph(1, g.header(), g.p0(), g.best(),
                new MoveNode("c3e2", "Ne2", "c3", "e2", "knight", -2.8, 18.0, null, null, 0, false),
                new Reply("c6d4", "Nd4", "c6", "d4", false, List.of("Nd4", "O-O", "Nxc2"),
                        List.of(new Taken("pawn", "c2", "Nxc2", 3, 1)), List.of(), 1, false, null, 3),
                new LineOutcome(List.of("Ne2", "Nd4", "O-O", "Nxc2"), 1, false, false, null),
                g.bestLine(),
                new Counterfactual("c6d4", "Nd4", false, true, 0.2, true),
                // A real threat, worth a lot — but it is a different move, and Nd4
                // as a free move was worth little.
                new ThreatNode(true, null, "d8h4", "Qh4", 4.0, 0.3, true, false),
                List.of(),
                List.of(new GeometryItem("FORK", "d4", "knight", List.of("c2", "e2"))),
                List.of(), List.of(new EffectItem("LANDS", List.of("e2"), "Ne2 lands on e2 · SEE there 0")),
                new VisibilityNode(4, "SHALLOW", 1.2, 0.6), false);
    }

    /**
     * Shaped on the hand-labelled {@code 16...Qc3}: White already threatened
     * cxd5, winning the knight. Black's move ignores it. The engine plays a quiet
     * king move first and takes the knight later in the line. R1 was not the
     * threat, so version 1 of the rules missed it.
     */
    public static EvidenceGraph threatCarriedOutLater() {
        String fen = "r4rk1/pp3p1p/2pp2p1/q2n4/2P4Q/1P1BPP1P/P2N1P2/R3K1R1 b Q - 0 16";
        return new EvidenceGraph(1,
                new Header(fen, "BLACK", 32, "MIDDLEGAME", "MISTAKE", 14, "test"),
                new PositionNode(0.5, 45, 38, 38, "BLACK"),
                new MoveNode("d5b4", "Nb4", "d5", "b4", "knight", 0.5, 45.0, null, null, 0, false),
                new MoveNode("a5c3", "Qc3", "a5", "c3", "queen", 3.4, 16.0, null, null, 0, false),
                new Reply("e1e2", "Ke2", "e1", "e2", false, List.of("Ke2", "Qa5", "Rac1", "Rfd8", "cxd5"),
                        List.of(new Taken("knight", "d5", "cxd5", 5, 3)), List.of(), 3, false, null, 5),
                new LineOutcome(List.of("Qc3", "Ke2", "Qa5", "Rac1", "Rfd8", "cxd5"), 3, false, false, null),
                new LineOutcome(List.of("Nb4", "Bb1", "Qa3", "Ke2"), 0, false, false, null),
                new Counterfactual("e1e2", "Ke2", false, true, 0.4, true),
                // The threat is real; Ke2 as a free move is worth little.
                new ThreatNode(true, null, "c4d5", "cxd5", 2.8, 0.4, true, false),
                List.of(), List.of(), List.of(),
                List.of(new EffectItem("LANDS", List.of("c3"), "Qc3 lands on c3 · SEE there 0")),
                new VisibilityNode(3, "SHALLOW", 2.9, 1.45), false);
    }

    /** As {@link #threatCarriedOutLater()}, but the engine's line takes on d5 too. */
    public static EvidenceGraph threatTakenInBothLines() {
        var g = threatCarriedOutLater();
        return new EvidenceGraph(1, g.header(), g.p0(), g.best(), g.played(), g.reply(),
                g.playedLine(), new LineOutcome(List.of("Nb4", "cxd5", "Nxd5"), 0, false, false, null),
                g.cf1(), g.t1(), g.delta(), g.geometry(), g.bestEffects(), g.movedEffects(), g.d1(), false);
    }

    /**
     * As {@link #hangingKnight()}, but taking the knight on d5 also opens a line:
     * a discovered attack. Shaped on the hand-labelled Rc7+ Bxc7, Nd4 Bxd4 and
     * Re5 Bxe5, which were each labelled "moved into attack".
     *
     * @param landingSee -3: the knight can simply be won. 0: it is protected, and
     *                   only the tactic wins it.
     */
    public static EvidenceGraph moverTakenWithGeometry(int landingSee) {
        var g = hangingKnight();
        var played = g.played();
        return new EvidenceGraph(1, g.header(), g.p0(), g.best(),
                new MoveNode(played.uci(), played.san(), played.from(), played.to(), played.piece(),
                        played.evalWhiteAfter(), played.winPctPlayerAfter(), null, null, landingSee, false),
                g.reply(), g.playedLine(), g.bestLine(), g.cf1(), g.t1(), g.delta(),
                List.of(new GeometryItem("DISCOVERED_ATTACK", "d5", "pawn", List.of("c3", "e4"))),
                g.bestEffects(), g.movedEffects(), g.d1(), false);
    }

    /** As {@link #twoCauses()}, but the player was in check, so no probe ran. */
    public static EvidenceGraph inCheck() {
        var g = twoCauses();
        return new EvidenceGraph(1, g.header(), g.p0(), g.best(), g.played(), g.reply(),
                g.playedLine(), g.bestLine(), g.cf1(),
                new ThreatNode(false, "player is in check", null, null, null, null, false, false),
                g.delta(), g.geometry(), g.bestEffects(), g.movedEffects(), g.d1(), false);
    }
}
