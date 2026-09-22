"""A stand-in evidence graph, built with python-chess alone.

Phase 2 and 3 build the real graph in the Java backend, with Stockfish behind it
(`See`, `ThreatProbe`, depth curves, counterfactuals). Phase 1 only has to prove
the toolchain, so this derives the same *shape* of evidence from what a Lichess
puzzle already states: the blunder, the refutation that punishes it, and the
material that changes hands.

What matters for the spike is that every field here is checkable against the
board. The point of the whole project is a model that cites evidence instead of
producing fluent nothing, so even the stub targets must be grounded, or the
training run proves nothing about the real pipeline.

Not an engine. No evaluation, no depth, no counterfactual. Those are §7 and
arrive with the real builder.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import chess

PIECE_VALUE = {
    chess.PAWN: 1,
    chess.KNIGHT: 3,
    chess.BISHOP: 3,
    chess.ROOK: 5,
    chess.QUEEN: 9,
    chess.KING: 0,
}

PIECE_NAME = {
    chess.PAWN: "pawn",
    chess.KNIGHT: "knight",
    chess.BISHOP: "bishop",
    chess.ROOK: "rook",
    chess.QUEEN: "queen",
    chess.KING: "king",
}


@dataclass(frozen=True)
class Target:
    """An enemy piece the refutation bears down on. Never a king — a king is not
    a capture target, and "the king on g1 (defended)" is not a fact about
    anything. Check is carried by `refutation_gives_check` instead."""

    square: str
    piece: str
    value: int
    defended: bool


@dataclass(frozen=True)
class Capture:
    """A piece the blunderer actually loses in the line, not merely one attacked."""

    square: str
    piece: str
    value: int
    san: str


@dataclass
class StubGraph:
    """Evidence for one blundered move, all of it derivable from the position."""

    fen: str
    mover: str
    """"White" or "Black" — whoever played the blunder."""
    blunder_san: str
    refutation_san: str
    refutation_gives_check: bool
    leads_to_mate: bool
    mate_in: int | None
    targets: list[Target] = field(default_factory=list)
    """Enemy pieces attacked by the refuting piece from its destination square."""
    captures: list[Capture] = field(default_factory=list)
    """What the blunderer actually loses as the line plays out."""
    material_swing: int = 0
    """Material the blunderer loses over the puzzle line, in pawns."""
    phase: str = "middlegame"
    mechanism: str = "other"
    """The Lichess theme tag. A label from the data, NOT a verified claim — see
    `mechanism_supported`."""
    line_san: list[str] = field(default_factory=list)

    def is_double_attack(self) -> bool:
        """Two or more targets worth taking, which is what 'fork' means concretely."""
        return len([t for t in self.targets if t.value >= 3 or not t.defended]) >= 2

    def mechanism_supported(self) -> bool:
        """Whether this position's geometry actually backs the Lichess label.

        A tag is not evidence. Real `TacticGeometry` arrives in Phase 2; until
        then only the handful of mechanisms python-chess can confirm are allowed
        to appear as a claim, and everything else falls back to a sentence that
        states only what the line does. Asserting an unverified 'pins the queen'
        would train the model to do exactly what the baseline caught it doing.
        """
        if self.mechanism == "fork":
            return self.is_double_attack() and (self.refutation_gives_check or len(self.targets) >= 2)
        if self.mechanism == "hangingPiece":
            return bool(self.targets) and not self.targets[0].defended and bool(self.captures)
        if self.mechanism == "backRankMate":
            return self.leads_to_mate
        return False


def _phase_of(board: chess.Board) -> str:
    officers = sum(
        1
        for sq in chess.SQUARES
        if (p := board.piece_at(sq)) and p.piece_type in (chess.KNIGHT, chess.BISHOP, chess.ROOK, chess.QUEEN)
    )
    if board.fullmove_number <= 12:
        return "opening"
    return "endgame" if officers <= 4 else "middlegame"


def _material(board: chess.Board, colour: chess.Color) -> int:
    return sum(
        PIECE_VALUE[p.piece_type]
        for sq in chess.SQUARES
        if (p := board.piece_at(sq)) and p.color == colour
    )


def build(fen: str, moves: list[str], mechanism: str = "other") -> StubGraph:
    """Turn a puzzle row into evidence about the blunder that created it.

    `moves[0]` is the blunder, played from `fen`. `moves[1]` is the refutation,
    and the rest is the punishing line.
    """
    board = chess.Board(fen)
    blunderer = board.turn

    blunder = chess.Move.from_uci(moves[0])
    blunder_san = board.san(blunder)
    board.push(blunder)

    refutation = chess.Move.from_uci(moves[1])
    refutation_san = board.san(refutation)

    before = _material(board, blunderer)

    # Where the refuting piece lands, and what it hits from there.
    after = board.copy()
    after.push(refutation)
    gives_check = after.is_check()

    targets: list[Target] = []
    for sq in after.attacks(refutation.to_square):
        piece = after.piece_at(sq)
        # Only the blunderer's pieces are targets. `after.turn` is the blunderer
        # (it is their move again), so testing against it skips exactly the
        # pieces we are looking for. Kings are excluded: check is a separate fact.
        if piece is None or piece.color != blunderer or piece.piece_type == chess.KING:
            continue
        defenders = after.attackers(blunderer, sq)
        defenders.discard(sq)
        targets.append(
            Target(
                square=chess.square_name(sq),
                piece=PIECE_NAME[piece.piece_type],
                value=PIECE_VALUE[piece.piece_type],
                defended=bool(defenders),
            )
        )
    targets.sort(key=lambda t: (-t.value, t.square))

    # Play the rest of the line to see what it actually costs.
    line = chess.Board(fen)
    line.push(blunder)
    line_san: list[str] = []
    captures: list[Capture] = []
    for uci in moves[1:]:
        mv = chess.Move.from_uci(uci)
        if mv not in line.legal_moves:
            break
        san = line.san(mv)
        # A capture of the blunderer's material, by the side punishing them.
        victim = line.piece_at(mv.to_square)
        if victim is not None and victim.color == blunderer and line.turn != blunderer:
            captures.append(
                Capture(
                    square=chess.square_name(mv.to_square),
                    piece=PIECE_NAME[victim.piece_type],
                    value=PIECE_VALUE[victim.piece_type],
                    san=san,
                )
            )
        line_san.append(san)
        line.push(mv)
    captures.sort(key=lambda c: -c.value)

    mate = line.is_checkmate()
    mate_in = (len(line_san) + 1) // 2 if mate else None
    swing = before - _material(line, blunderer)

    return StubGraph(
        fen=fen,
        mover="White" if blunderer == chess.WHITE else "Black",
        blunder_san=blunder_san,
        refutation_san=refutation_san,
        refutation_gives_check=gives_check,
        leads_to_mate=mate,
        mate_in=mate_in,
        targets=targets,
        captures=captures,
        material_swing=swing,
        phase=_phase_of(chess.Board(fen)),
        mechanism=mechanism,
        line_san=line_san,
    )
