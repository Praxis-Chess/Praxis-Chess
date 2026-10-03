"""The evidence graph as Praxis writes it (EvidenceGraph.java, graph version 2).

A graph is plain JSON with snake_case fields; graph_schema.json in the dataset
describes every field. Only what verification needs lives here: the citable
items' IDs and the squares each one is about, which citation mode checks
claims against. The item texts are the R3 rendering's business, not the
verifier's.
"""

from __future__ import annotations


def section(g: dict, name: str) -> dict:
    """A graph node; Jackson gives a missing record null, read here as empty."""
    return g.get(name) or {}


def lst(x) -> list:
    return x if isinstance(x, list) else []


def _squares(a, b) -> list[str]:
    return [s for s in (a, b) if s is not None]


def items(g: dict) -> list[dict]:
    """Every citable item, {"id", "squares", "priority"}, highest priority first.

    IDs never depend on truncation: Δ3 is the third Δ item whether or not the
    items after it were dropped from a budgeted rendering.
    """
    reply, best, played, t1 = section(g, "reply"), section(g, "best"), section(g, "played"), section(g, "t1")
    out = [{"id": "P0", "squares": [], "priority": 0}]
    loss_squares = [t.get("square") for t in lst(reply.get("losses"))] + [t.get("square") for t in lst(reply.get("gains"))]
    out.append({"id": "PC", "squares": loss_squares, "priority": 1})
    if reply.get("san") is not None:
        out.append({"id": "R1", "squares": _squares(reply.get("from"), reply.get("to")), "priority": 2})
    out.append({"id": "CF1", "squares": _squares(reply.get("from"), reply.get("to")), "priority": 3})
    threat = []
    uci = t1.get("move_uci")
    if uci is not None and len(uci) >= 4:
        threat += [uci[0:2], uci[2:4]]
    threat += _squares(reply.get("from"), reply.get("to"))
    out.append({"id": "T1", "squares": threat, "priority": 4})
    out.append({"id": "B", "squares": _squares(best.get("from"), best.get("to")), "priority": 0})
    out.append({"id": "M", "squares": _squares(played.get("from"), played.get("to")), "priority": 0})
    out.append({"id": "D1", "squares": [], "priority": 5})
    for i, x in enumerate(lst(g.get("geometry")), 1):
        out.append({"id": f"G{i}", "squares": [x.get("by")] + lst(x.get("targets")), "priority": 6})
    for i, d in enumerate(lst(g.get("delta")), 1):
        priority = {"NOW_LOOSE": 7, "DEFENDER_REMOVED": 8, "DEFENDER_GAINED": 10}.get(d.get("kind"), 9)
        sq = [d[k] for k in ("square", "slider", "target") if d.get(k) is not None]
        sq += lst(d.get("defenders_before"))
        out.append({"id": f"Δ{i}", "squares": sq, "priority": priority})
    for i, e in enumerate(lst(g.get("moved_effects")), 1):
        out.append({"id": f"M.e{i}", "squares": lst(e.get("squares")), "priority": 11})
    for i, e in enumerate(lst(g.get("best_effects")), 1):
        out.append({"id": f"B.e{i}", "squares": lst(e.get("squares")), "priority": 12})
    out.sort(key=lambda it: it["priority"])   # stable, as Java's List.sort
    return out
