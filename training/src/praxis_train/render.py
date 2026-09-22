"""Prompt and target rendering for the Phase 1 spike.

In the finished system the backend owns both sides of this (§17.3): Python asks
it to render R0-R3 and to verify a claim, so the training prompt and the
production prompt cannot drift apart. The backend has no graph yet, so Phase 1
renders locally and the modules stay small and replaceable.

Targets are templates, not a teacher model. Phase 4 brings the teacher in. What
matters now is that a target only ever states something its own evidence block
supports. A Lichess theme tag is data, not a verified claim, so the mechanism
verbs ("pins", "forks") appear only where python-chess can confirm the geometry
— see `StubGraph.mechanism_supported`. Everything else falls back to a sentence
about what the line demonstrably does. A target set that asserted the tag
regardless would teach the model to invent motifs, which is the exact failure
the baseline measured (67% of shipped motifs content-free, `Bg5` called "a fork").
"""

from __future__ import annotations

from .stub_graph import StubGraph, Target

PROMPT_INSTRUCTION = (
    "State in one sentence what the move allows. "
    "Name the squares. Use only the evidence given."
)


def _target_phrase(t: Target) -> str:
    return f"the {t.piece} on {t.square}" + ("" if t.defended else " (undefended)")


def render_prompt(g: StubGraph) -> str:
    """The evidence block the model sees. Everything it may cite is in here."""
    lines = [
        f"FEN: {g.fen}",
        f"Player: {g.mover}",
        f"Played: {g.blunder_san}",
        f"Reply: {g.refutation_san}" + (" (check)" if g.refutation_gives_check else ""),
    ]
    if g.line_san:
        lines.append("Line: " + " ".join(g.line_san))
    if g.targets:
        lines.append(
            "Attacked: "
            + ", ".join(
                f"{t.piece} on {t.square} ({'defended' if t.defended else 'undefended'})"
                for t in g.targets
            )
        )
    if g.captures:
        lines.append(
            "Lost in the line: "
            + ", ".join(f"{c.piece} on {c.square} ({c.san})" for c in g.captures)
        )
    if g.leads_to_mate:
        lines.append(f"Outcome: mate in {g.mate_in}")
    elif g.material_swing > 0:
        lines.append(f"Outcome: {g.mover} loses {g.material_swing} points of material")
    # The tag comes from the CC0 puzzle data. It is labelled as such so the model
    # is not taught that an unverified label is itself evidence.
    lines.append(f"Tagged: {g.mechanism}")
    lines.append(f"Phase: {g.phase}")
    return "\n".join(lines) + "\n\n" + PROMPT_INSTRUCTION


def _pts(n: int) -> str:
    return f"{n} point" if n == 1 else f"{n} points"


def _cost(g: StubGraph) -> str:
    if g.leads_to_mate:
        return f"mating in {g.mate_in}"
    if g.material_swing > 0:
        return f"costing {_pts(g.material_swing)}"
    return "leaving the position lost"


def _loss_clause(g: StubGraph) -> str:
    """What is actually lost, preferring the piece the line takes over a total."""
    if g.leads_to_mate:
        return f"mate in {g.mate_in}"
    if g.captures:
        c = g.captures[0]
        return f"the {c.piece} on {c.square} falls to {c.san}"
    if g.material_swing > 0:
        return f"{_pts(g.material_swing)} go"
    return "the position collapses"


def render_target(g: StubGraph) -> str:
    """Deterministic prose, every clause traceable to a field of the graph.

    Sentence shape varies by what the evidence contains, so a model that learns
    these targets learns to follow the evidence rather than one stem. The
    baseline's failure was 95% of explanations opening with the same five words;
    a target set with that property would train it straight back in.
    """
    reply = g.refutation_san
    hit = g.targets

    # ── verified geometry: the mechanism may be named ────────────────────────
    if g.mechanism_supported():
        if g.mechanism == "fork":
            a, b = hit[0], hit[1]
            return (
                f"{reply} hits {_target_phrase(a)} and {_target_phrase(b)} at once, "
                f"so {g.blunder_san} drops one of them — {_cost(g)}."
            )
        if g.mechanism == "hangingPiece":
            a = hit[0]
            return (
                f"{g.blunder_san} leaves {_target_phrase(a)}, and {reply} takes it: "
                f"{_loss_clause(g)}."
            )
        if g.mechanism == "backRankMate":
            return (
                f"{g.blunder_san} leaves the back rank unguarded, and {reply} forces "
                f"mate in {g.mate_in}."
            )

    # ── unverified tag: say only what the line shows ─────────────────────────
    if g.leads_to_mate:
        if g.refutation_gives_check:
            return f"{g.blunder_san} allows {reply}, and the checks do not stop: mate in {g.mate_in}."
        return f"{g.blunder_san} walks into {reply}, mating in {g.mate_in}."

    if g.captures and hit:
        c, a = g.captures[0], hit[0]
        if c.square == a.square:
            return (
                f"After {g.blunder_san}, {reply} wins {_target_phrase(a)} — "
                f"{_pts(g.material_swing)}."
            )
        return (
            f"{reply} answers {g.blunder_san} by hitting {_target_phrase(a)}, and "
            f"{_loss_clause(g)} for {_pts(g.material_swing)}."
        )

    if g.captures:
        return f"{g.blunder_san} runs into {reply}, after which {_loss_clause(g)}."

    if hit:
        return (
            f"{reply} punishes {g.blunder_san} by attacking {_target_phrase(hit[0])}, "
            f"{_cost(g)}."
        )

    return f"{g.blunder_san} runs into {reply}, {_cost(g)}."


def stock_opening_rate(texts: list[str], words: int = 5) -> float:
    """Share of texts that begin with the single most common opening phrase.

    The baseline's boilerplate figure, computed the same way, so a target set can
    be checked against the thing it is meant to improve on.
    """
    if not texts:
        return 0.0
    from collections import Counter

    stems = Counter(" ".join(t.split()[:words]).lower() for t in texts)
    return stems.most_common(1)[0][1] / len(texts)
