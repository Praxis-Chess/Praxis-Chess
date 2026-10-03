"""The rule composer (DiagnosisRules.java): labels, and the rules' own diagnosis.

Three jobs in Praxis, two of them here. The labels are what the verifier's
rule 3 compares a model's answer with. The rules' own diagnosis is the S_rules
baseline every table includes. Each rule reads only fields of the graph; the
comments in DiagnosisRules.java give the reasoning behind each interpretation.
"""

from __future__ import annotations

from praxis_eval.graph import lst, section

MECHANISM_RULES = ["IGNORED_THREAT", "REMOVED_DEFENDER", "MOVED_INTO_ATTACK",
                   "LOSING_CAPTURE", "CREATED_TACTIC", "MISSED_OPPORTUNITY"]


def label(g: dict) -> dict:
    consequence = _consequence(g)
    fired = _fired(g, consequence)
    concrete = consequence != "NOT_CONCRETE"
    mechanism = "NONE" if not concrete else fired[0] if len(fired) == 1 else "UNCLEAR"
    return {"consequence": consequence, "fired": fired, "composite": concrete and len(fired) != 1,
            "mechanism": mechanism, "motif": _motif(g, consequence, mechanism),
            "visibility": section(g, "d1").get("band"),
            "single_cause": len(fired) == 1 and consequence != "NOT_CONCRETE"}


def _consequence(g: dict) -> str:
    played, best = section(g, "played_line"), section(g, "best_line")
    if played.get("mate_against_player", False) and not best.get("mate_against_player", False):
        return "MATED"
    if best.get("mate_for_player", False) and not played.get("mate_for_player", False):
        return "MISSED_MATE"
    p_loss, b_loss = played.get("net_loss", 0), best.get("net_loss", 0)
    gap = p_loss - b_loss
    if p_loss >= 1 and gap >= 1:
        return "LOST_MATERIAL"
    if b_loss <= -1 and gap >= 1 and p_loss < 1:
        return "MISSED_MATERIAL"
    return "NOT_CONCRETE"


def _fired(g: dict, consequence: str) -> list[str]:
    out = []
    bad = consequence in ("MATED", "LOST_MATERIAL")
    missed = consequence in ("MISSED_MATE", "MISSED_MATERIAL")
    t1, cf1, played, reply = section(g, "t1"), section(g, "cf1"), section(g, "played"), section(g, "reply")
    no_threat = t1.get("probed", False) and not t1.get("reply_is_threat", False)
    if bad:
        if (t1.get("reply_is_threat", False) and cf1.get("parries", False)) or threat_carried_out(g):
            out.append("IGNORED_THREAT")
        if no_threat and removed_defender(g) is not None:
            out.append("REMOVED_DEFENDER")
        if played.get("captured") is None and played.get("landing_see", 0) < 0:
            out.append("MOVED_INTO_ATTACK")
        if played.get("captured") is not None and played.get("capture_see") is not None and played["capture_see"] < 0:
            out.append("LOSING_CAPTURE")
        takes_the_mover = played.get("to") == reply.get("to") and \
            ("MOVED_INTO_ATTACK" in out or "LOSING_CAPTURE" in out)
        if no_threat and lst(g.get("geometry")) and cf1.get("parries", False) and not takes_the_mover:
            out.append("CREATED_TACTIC")
    if missed:
        out.append("MISSED_OPPORTUNITY")
    return out


def threat_carried_out(g: dict) -> bool:
    """T1's threat is not R1, but the opponent plays it later in the played line and it takes material there."""
    t1, reply = section(g, "t1"), section(g, "reply")
    threat = t1.get("move_san")
    if not t1.get("probed", False) or not t1.get("threatened", False) or threat is None:
        return False
    if threat == reply.get("san"):
        return False
    if not _opponent_plays(lst(section(g, "played_line").get("line_san")), threat):
        return False
    if _opponent_plays(lst(section(g, "best_line").get("line_san")), threat):
        return False
    return any(threat == l.get("san") for l in lst(reply.get("losses")))


def _opponent_plays(line: list, san: str) -> bool:
    return any(san == line[i] for i in range(1, len(line), 2))


def removed_defender(g: dict) -> dict | None:
    frm = section(g, "played").get("from")
    for d in lst(g.get("delta")):
        if d.get("kind") != "NOW_LOOSE":
            continue
        before, after = d.get("defenders_before"), d.get("defenders_after")
        if before is not None and frm in before and (after is None or frm not in after):
            return d
    return None


def _motif(g: dict, consequence: str, mechanism: str) -> str:
    if consequence == "NOT_CONCRETE":
        return "POSITIONAL"
    if consequence == "MATED" and g.get("back_rank_mate", False):
        return "BACK_RANK"
    geometry = lst(g.get("geometry"))
    if geometry and not consequence.startswith("MISSED"):
        return geometry[0].get("kind")
    if mechanism in ("MOVED_INTO_ATTACK", "LOSING_CAPTURE", "REMOVED_DEFENDER"):
        return "HANGING_PIECE"
    return "OTHER"


# ── the rules' own diagnosis (S_rules) ───────────────────────────────────────

def _args(*kv) -> dict:
    """Ordered arguments; nulls dropped so an absent fact is absent, not "null"."""
    return {kv[i]: kv[i + 1] for i in range(0, len(kv) - 1, 2) if kv[i + 1] is not None}


def _claim(type_: str, args: dict, cites: list[str], text: str) -> dict:
    return {"type": type_, "args": args, "cites": cites, "text": text}


def _cap(side: str | None) -> str:
    return "" if side is None else side[0] + side[1:].lower()


def diagnose(g: dict) -> dict:
    """A full typed chain for single-cause mistakes, facts without a cause when composite, abstention when NOT_CONCRETE."""
    labels = label(g)
    reply, played, best, t1, cf1 = (section(g, k) for k in ("reply", "played", "best", "t1", "cf1"))
    r1 = reply.get("san")
    chain = []

    if labels["consequence"] == "NOT_CONCRETE":
        chain.append(_visibility(g))
        return _diag(chain, labels, "NONE", r1, "No material or mate changes hands within the horizon; the "
                     "difference is positional, which this diagnosis does not cover.")

    player = _cap(section(g, "header").get("player"))
    opponent = "Black" if player == "White" else "White"
    m, b = played.get("san"), best.get("san")
    mech = labels["mechanism"]

    if mech == "IGNORED_THREAT":
        if not (t1.get("reply_is_threat", False) and cf1.get("parries", False)):
            threat = t1.get("move_san")
            chain.append(_claim("THREAT_EXISTS", _args("move", threat, "cost", t1.get("cost")), ["T1"],
                                f"Before {m}, {opponent} already threatened {threat}."))
            chain.append(_claim("DOES_NOT_ADDRESS", _args("move", m, "threat", threat), ["M", "T1"],
                                f"{m} does not deal with it."))
            chain += [_critical(g), _outcome(g)]
            explanation = (f"Before {m}, {opponent} already threatened {threat}. {m} does not deal with it: "
                           f"after {r1}, {threat} still comes, and {_outcome_phrase(g, player)}.")
        else:
            chain.append(_claim("THREAT_EXISTS", _args("move", r1, "cost", t1.get("reply_cost")), ["T1"],
                                f"Before {m}, {opponent} already threatened {r1}."))
            chain.append(_claim("DOES_NOT_ADDRESS", _args("move", m, "threat", r1), ["M", "T1"],
                                f"{m} does not deal with it."))
            chain += [_critical(g), _outcome(g), _counterfactual(g)]
            explanation = (f"Before {m}, {opponent} already threatened {r1}. {m} does not deal with it: "
                           f"{r1} follows, and {_outcome_phrase(g, player)}. {_after_best(g)}.")
    elif mech == "REMOVED_DEFENDER":
        d = removed_defender(g)
        idx = lst(g.get("delta")).index(d) + 1
        chain.append(_claim("DEFENDER_REMOVED",
                            _args("square", d.get("square"), "piece", d.get("piece"), "defender", played.get("from"),
                                  "move", m), [f"Δ{idx}"],
                            f"{m} moves the {played.get('piece')} off {played.get('from')}, the defender of the "
                            f"{d.get('piece')} on {d.get('square')}."))
        chain += [_critical(g), _outcome(g)]
        if cf1.get("parries", False):
            chain.append(_counterfactual(g))
        explanation = (f"{m} moves the {played.get('piece')} off {played.get('from')}, where it was defending the "
                       f"{d.get('piece')} on {d.get('square')}. {r1} follows, and {_outcome_phrase(g, player)}."
                       + (f" {_after_best(g)}." if cf1.get("parries", False) else ""))
    elif mech == "MOVED_INTO_ATTACK":
        chain.append(_claim("MOVED_INTO_ATTACK",
                            _args("square", played.get("to"), "move", m, "see", played.get("landing_see", 0)),
                            [_effect_id(g, "LANDS")], f"{m} puts the {played.get('piece')} where it can be won."))
        chain += [_critical(g), _outcome(g)]
        if cf1.get("parries", False):
            chain.append(_counterfactual(g))
        explanation = (f"{m} puts the {played.get('piece')} on {played.get('to')}, where it can be won. "
                       f"{r1} follows, and {_outcome_phrase(g, player)}."
                       + (f" {_after_best(g)}." if cf1.get("parries", False) else ""))
    elif mech == "LOSING_CAPTURE":
        chain.append(_claim("LOSING_CAPTURE",
                            _args("move", m, "square", played.get("to"), "see", played.get("capture_see")),
                            [_effect_id(g, "CAPTURES")], f"{m} starts an exchange that loses material."))
        chain += [_critical(g), _outcome(g)]
        explanation = (f"{m} starts an exchange on {played.get('to')} that loses material. "
                       f"{r1} follows, and {_outcome_phrase(g, player)}.")
    elif mech == "CREATED_TACTIC":
        t = lst(g.get("geometry"))[0]
        kind = t.get("kind").lower().replace("_", " ")
        chain.append(_claim("TACTIC_GEOMETRY",
                            _args("kind", t.get("kind"), "by", t.get("by"), "targets", t.get("targets"), "move", r1),
                            ["G1"], f"{r1} is a {kind}."))
        chain += [_critical(g), _outcome(g), _counterfactual(g)]
        explanation = (f"{m} allows {r1}, a {kind} by the {t.get('piece')} on {t.get('by')} against "
                       f"{' and '.join(lst(t.get('targets')))}, and {_outcome_phrase(g, player)}. {_after_best(g)}.")
    elif mech == "MISSED_OPPORTUNITY":
        bl = section(g, "best_line")
        mates = bl.get("mate_for_player", False)
        net = bl.get("net_loss", 0)
        chain.append(_claim("COUNTERFACTUAL", _args("alt_move", b, "effect", "MATES" if mates else "WINS_MATERIAL"),
                            ["B"], b + (" forces mate." if mates else " wins material.")))
        if mates and bl.get("mate_in") is not None:
            chain.append(_claim("MATE_IN", _args("n", bl["mate_in"], "line", "BEST"), ["B"],
                                f"Mate in {bl['mate_in']}."))
        elif not mates:
            chain.append(_claim("MATERIAL_CHANGE", _args("amount", net, "line", "BEST"), ["B"],
                                f"{player} would win {-net} net."))
        explanation = (b + (" would have forced mate" if mates else
                            f" would have won {-net}{' pawn' if net == -1 else ' pawns'} net")
                       + ", and the move played misses it.")
    else:
        chain += [_critical(g), _outcome(g)]
        explanation = f"{r1} follows {m}, and {_outcome_phrase(g, player)}."
    chain.append(_visibility(g))
    return _diag(chain, labels, mech, r1, explanation)


def _diag(chain, labels, mechanism, r1, explanation) -> dict:
    return {"reasoning_chain": chain, "consequence": labels["consequence"], "mechanism": mechanism,
            "motif": labels["motif"], "critical_response": r1, "visibility": labels["visibility"],
            "explanation": explanation}


def _critical(g: dict) -> dict:
    r = section(g, "reply")
    result = "MATE" if r.get("mate", False) or section(g, "played_line").get("mate_against_player", False) \
        else "MATERIAL" if r.get("net_loss", 0) > 0 else "NONE"
    return _claim("CRITICAL_REPLY", _args("move", r.get("san"), "after", section(g, "played").get("san"),
                                          "result", result, "ply", r.get("consequence_ply", 0)),
                  ["R1"], f"{r.get('san')} is the critical reply.")


def _outcome(g: dict) -> dict:
    r = section(g, "reply")
    if r.get("mate", False) and r.get("mate_in") is not None:
        return _claim("MATE_IN", _args("n", r["mate_in"], "line", "PLAYED"), ["PC"], f"Mate in {r['mate_in']}.")
    return _claim("MATERIAL_CHANGE", _args("amount", r.get("net_loss", 0), "line", "PLAYED"), ["PC"],
                  f"Net material lost: {r.get('net_loss', 0)}.")


def _counterfactual(g: dict) -> dict:
    cf, best, played, reply = section(g, "cf1"), section(g, "best"), section(g, "played"), section(g, "reply")
    legal = cf.get("legal_after_best", False)
    return _claim("COUNTERFACTUAL",
                  _args("alt_move", best.get("san"), "played", played.get("san"),
                        "effect", "REPLY_FAILS" if legal else "REPLY_ILLEGAL", "reply", reply.get("san")),
                  ["B", "CF1"],
                  f"After {best.get('san')}, {reply.get('san')}" + (" does not work." if legal else " is not possible."))


def _visibility(g: dict) -> dict:
    d1 = section(g, "d1")
    return _claim("VISIBILITY", _args("band", d1.get("band"), "depth", d1.get("depth")), ["D1"],
                  f"Visibility {d1.get('band').lower()}.")


def _after_best(g: dict) -> str:
    legal = section(g, "cf1").get("legal_after_best", False)
    return (f"After {section(g, 'best').get('san')}, {section(g, 'reply').get('san')}"
            + (" would not work" if legal else " would not be possible"))


def _outcome_phrase(g: dict, player: str) -> str:
    r = section(g, "reply")
    if r.get("mate", False) and r.get("mate_in") is not None:
        return f"it is mate in {r['mate_in']}"
    if section(g, "played_line").get("mate_against_player", False):
        return "it leads to a forced mate"
    net = r.get("net_loss", 0)
    if net > 0:
        return f"{player} loses {net}{' pawn' if net == 1 else ' pawns'} net"
    return "no material changes hands within the horizon"


def _effect_id(g: dict, kind: str) -> str:
    for i, e in enumerate(lst(g.get("moved_effects")), 1):
        if e.get("kind") == kind:
            return f"M.e{i}"
    return "M"
