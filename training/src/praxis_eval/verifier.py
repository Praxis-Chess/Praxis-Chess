"""Checks a diagnosis against its evidence graph (DiagnosisVerifier.java).

The same eight rules, in the same order, with the same messages, so a verdict
here is the verdict Praxis gives at runtime and gave every number in the
published tables. Parity with the Java is tested on every published answer and
on verifier_test_vectors.jsonl (tests/test_parity.py).

Claim mode checks every typed claim against the full graph and ignores
citations: the headline metric, fair to every rendering R0-R3. Citation mode
also requires each cited ID to exist and to be about the squares the claim
names; it is reported separately, for R3.

What this cannot catch: a chain made entirely of true claims can still tell
the wrong story. Rules 3 and 4 close the common cases; the rest is human
evaluation.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

import chess

from praxis_eval.graph import items, lst, section
from praxis_eval.jackson import java_str
from praxis_eval.rules import label, threat_carried_out

CONSEQUENCES = {"MATED", "LOST_MATERIAL", "MISSED_MATE", "MISSED_MATERIAL", "NOT_CONCRETE"}
MECHANISMS = {"IGNORED_THREAT", "REMOVED_DEFENDER", "MOVED_INTO_ATTACK", "LOSING_CAPTURE",
              "CREATED_TACTIC", "MISSED_OPPORTUNITY", "UNCLEAR", "NONE"}
MOTIFS = {"FORK", "PIN", "SKEWER", "BACK_RANK", "DISCOVERED_ATTACK", "HANGING_PIECE", "POSITIONAL", "OTHER"}
VISIBILITIES = {"SHALLOW", "MEDIUM", "DEEP", "NEVER"}
REQUIRED_ARGS = {
    "THREAT_EXISTS": ["move"], "ATTACK_DEFENSE": ["square", "attackers", "defenders"],
    "DEFENDER_REMOVED": ["square", "defender"], "MOVED_INTO_ATTACK": ["square"], "LOSING_CAPTURE": ["move"],
    "BLOCKS_LINE": ["slider", "target"], "OPENS_LINE": ["slider", "target"],
    "DOES_NOT_ADDRESS": ["move", "threat"], "CRITICAL_REPLY": ["move"], "MATERIAL_CHANGE": ["amount"],
    "MATE_IN": ["n"], "COUNTERFACTUAL": ["alt_move", "effect"], "TACTIC_GEOMETRY": ["kind", "by", "targets"],
    "VISIBILITY": ["band"],
}
CLAIM_TYPES = set(REQUIRED_ARGS)
COUNTERFACTUAL_EFFECTS = {"REPLY_ILLEGAL", "REPLY_FAILS", "WINS_MATERIAL", "MATES"}
MAX_CHAIN = 7

# Java's \b, \d and \s are ASCII here (JDK 19+), so these are compiled ASCII too.
CAUSAL = re.compile(r"\b(allow|allows|allowed|enable|enables|enabled|because|create|creates|created|lets)\b",
                    re.IGNORECASE | re.ASCII)
THREAT_WORDS = re.compile(r"\b(already|ignore|ignores|ignored)\b", re.IGNORECASE | re.ASCII)
MOVE_OR_SQUARE = re.compile(
    r"(?<![A-Za-z0-9])(O-O-O|O-O|[KQRBN][a-h]?[1-8]?x?[a-h][1-8]|[a-h]x[a-h][1-8](?:=[QRBN])?|[a-h][1-8](?:=[QRBN])?)"
    r"[+#]?(?![A-Za-z0-9])", re.ASCII)
MOVE_NUMBER = re.compile(r"\b\d+\.(\.\.)?\s*", re.ASCII)
LEADING_MOVE_NUMBER = re.compile(r"^\d+\.(\.\.)?\s*", re.ASCII)
SQUARE = re.compile(r"[a-h][1-8]")
SQUARE_KEYS = {"square", "defender", "by", "targets", "slider", "target", "attackers", "defenders"}
CAUSE_CLAIMS = {"THREAT_EXISTS", "DEFENDER_REMOVED", "MOVED_INTO_ATTACK", "LOSING_CAPTURE",
                "DOES_NOT_ADDRESS", "COUNTERFACTUAL", "TACTIC_GEOMETRY"}
INT_MIN = -2 ** 31


@dataclass
class Violation:
    rule: int
    claim: int | None
    message: str


@dataclass
class Report:
    passed: bool
    violations: list[Violation] = field(default_factory=list)
    claims: int = 0
    claims_true: int = 0


def verify(g: dict, d: dict | None, mode: str = "CLAIM") -> Report:
    """mode: "CLAIM" (the headline metric) or "CITATION" (claim mode plus rule 7)."""
    v: list[Violation] = []
    if d is None:
        return Report(False, [Violation(1, None, "no diagnosis")], 0, 0)
    chain = d.get("reasoning_chain") or []
    labels = label(g)
    reply, played = section(g, "reply"), section(g, "played")

    # rule 1: schema, closed vocabularies, chain length
    if len(chain) > MAX_CHAIN:
        v.append(Violation(1, None, f"chain has {len(chain)} steps; the limit is {MAX_CHAIN}"))
    _closed(v, "consequence", d.get("consequence"), CONSEQUENCES)
    _closed(v, "mechanism", d.get("mechanism"), MECHANISMS)
    _closed(v, "motif", d.get("motif"), MOTIFS)
    _closed(v, "visibility", d.get("visibility"), VISIBILITIES)
    well_formed = [_schema(v, i, c) for i, c in enumerate(chain)]

    # rule 2: each claim is true of the graph and the board
    truth = [False] * len(chain)
    true_count = 0
    for i, c in enumerate(chain):
        if not well_formed[i]:
            continue
        why = falsity(g, c)
        if why is None:
            truth[i] = True
            true_count += 1
        else:
            v.append(Violation(2, i, f"{c['type']}: {why}"))

    # rule 3: labels agree with the rules, or satisfy their preconditions
    cons, mech, motif, vis = d.get("consequence"), d.get("mechanism"), d.get("motif"), d.get("visibility")
    if cons is not None and cons != labels["consequence"]:
        v.append(Violation(3, None, f"consequence {cons}, evidence says {labels['consequence']}"))
    if vis is not None and vis != labels["visibility"]:
        v.append(Violation(3, None, f"visibility {vis}, evidence says {_j(labels['visibility'])}"))
    if labels["single_cause"]:
        if mech != labels["mechanism"]:
            v.append(Violation(3, None, f"mechanism {_j(mech)}, the single rule that fires is {labels['mechanism']}"))
        if motif != labels["motif"]:
            v.append(Violation(3, None, f"motif {_j(motif)}, evidence says {_j(labels['motif'])}"))
    elif labels["composite"]:
        if mech is not None and mech != "UNCLEAR" and mech not in labels["fired"]:
            v.append(Violation(3, None, f"mechanism {mech} — its preconditions do not hold; rules that do: "
                                        f"[{', '.join(labels['fired'])}]"))
        allowed = {labels["motif"], "OTHER"} | {x.get("kind") for x in lst(g.get("geometry"))}
        if motif is not None and motif not in allowed:
            v.append(Violation(3, None, f"motif {motif} is not supported by the geometry"))

    # rule 4: causal words need a counterfactual; "already" needs a threat
    prose = d.get("explanation") or ""
    if CAUSAL.search(prose) and not _has_true(chain, truth, "COUNTERFACTUAL"):
        v.append(Violation(4, None, "causal language without a verified COUNTERFACTUAL claim"))
    if THREAT_WORDS.search(prose) and not _has_true(chain, truth, "THREAT_EXISTS"):
        v.append(Violation(4, None, "\"already\"/\"ignored\" without a verified THREAT_EXISTS claim"))

    # rule 5: every move and square in the prose was earned by the chain
    earned = _earned_text(chain, truth)
    for token in tokens(prose):
        if token not in earned:
            v.append(Violation(5, None, f"\"{token}\" appears in the explanation but in no verified claim"))

    # rule 6: the critical response is R1
    if norm(d.get("critical_response")) != norm(reply.get("san")):
        v.append(Violation(6, None, f"critical_response {_j(d.get('critical_response'))}, "
                                    f"the critical reply is {_j(reply.get('san'))}"))

    # rule 7: citations exist and are about the claim's squares
    if mode == "CITATION":
        by_id = {it["id"]: it for it in items(g)}
        for i, c in enumerate(chain):
            cites = None if c is None else c.get("cites")
            if not cites:
                v.append(Violation(7, i, "no citations"))
                continue
            cited = set()
            for cid in cites:
                it = by_id.get(cid)
                if it is None:
                    v.append(Violation(7, i, f"cites {_j(cid)}, which does not exist"))
                else:
                    cited.update(it["squares"])
            for sq in _claim_squares(c):
                if sq not in cited:
                    v.append(Violation(7, i, f"names {sq}, which no cited item is about"))

    # rule 8: no invented causes for positional mistakes
    if labels["consequence"] == "NOT_CONCRETE":
        if mech is not None and mech != "NONE":
            v.append(Violation(8, None, f"mechanism {mech} asserted for a NOT_CONCRETE mistake"))
        for i, c in enumerate(chain):
            if c is not None and c.get("type") in CAUSE_CLAIMS:
                v.append(Violation(8, i, f"{c['type']} in a NOT_CONCRETE diagnosis"))

    return Report(not v, v, len(chain), true_count)


# ── rule 1 ───────────────────────────────────────────────────────────────────

def _closed(v, field_: str, value, allowed: set) -> None:
    if value is None or value not in allowed:
        v.append(Violation(1, None, f"{field_} {_j(value)} is not in the closed vocabulary"))


def _schema(v, i: int, c: dict | None) -> bool:
    if c is None or c.get("type") is None or c["type"] not in CLAIM_TYPES:
        v.append(Violation(1, i, f"claim type {_j(None if c is None else c.get('type'))} is not in the vocabulary"))
        return False
    args = c.get("args") or {}
    # Java iterates a Set.of(...), whose order is unspecified: the first missing
    # key it reports may differ, the verdict cannot.
    for key in REQUIRED_ARGS[c["type"]]:
        if args.get(key) is None:
            v.append(Violation(1, i, f"{c['type']} is missing \"{key}\""))
            return False
    if c["type"] == "COUNTERFACTUAL" and java_str(args.get("effect")) not in COUNTERFACTUAL_EFFECTS:
        v.append(Violation(1, i, f"COUNTERFACTUAL effect {java_str(args.get('effect'))} is not in the vocabulary"))
        return False
    return True


# ── rule 2: claim truth ──────────────────────────────────────────────────────

def falsity(g: dict, c: dict) -> str | None:
    """None when the claim is true of the graph; otherwise why not."""
    a = c.get("args") or {}
    r, t1, cf = section(g, "reply"), section(g, "t1"), section(g, "cf1")
    played, best = section(g, "played"), section(g, "best")
    t = c["type"]

    if t == "THREAT_EXISTS":
        move = _str(a.get("move"))
        reply_was_threat = t1.get("reply_is_threat", False) and same_move(move, r.get("san"))
        probe_threat = t1.get("threatened", False) and same_move(move, t1.get("move_san"))
        if t1.get("probed", False) and (reply_was_threat or probe_threat):
            return None
        return f"{move} was not a threat before the move (probe: {_j(t1.get('move_san'))} worth {_j(t1.get('cost'))})"

    if t == "ATTACK_DEFENSE":
        return _attack_defense(g, a)

    if t == "DEFENDER_REMOVED":
        square, defender = _str(a.get("square")), _str(a.get("defender"))
        found = any(square == d.get("square") and d.get("defenders_before") is not None
                    and defender in d["defenders_before"]
                    and (d.get("defenders_after") is None or defender not in d["defenders_after"])
                    for d in lst(g.get("delta")))
        if not found:
            return f"{square} did not lose {defender} as a defender"
        if "move" in a and not same_move(_str(a.get("move")), played.get("san")):
            return f"the move is {_j(played.get('san'))}, not {_j(a.get('move'))}"
        return None

    if t == "MOVED_INTO_ATTACK":
        if _str(a.get("square")) == played.get("to") and played.get("landing_see", 0) < 0:
            return None
        return f"the moved piece is not losing on {_j(a.get('square'))}"

    if t == "LOSING_CAPTURE":
        if (same_move(_str(a.get("move")), played.get("san")) and played.get("captured") is not None
                and played.get("capture_see") is not None and played["capture_see"] < 0):
            return None
        return f"{_j(a.get('move'))} is not a losing capture"

    if t == "BLOCKS_LINE":
        return _line_claim(g, a, False)
    if t == "OPENS_LINE":
        return _line_claim(g, a, True)

    if t == "DOES_NOT_ADDRESS":
        threat = _str(a.get("threat"))
        punishes = ((same_move(threat, r.get("san"))
                     and (r.get("mate", False) or r.get("net_loss", 0) > 0
                          or section(g, "played_line").get("mate_against_player", False)))
                    or (same_move(threat, t1.get("move_san")) and threat_carried_out(g)))
        if same_move(_str(a.get("move")), played.get("san")) and punishes:
            return None
        return f"after {_j(a.get('move'))}, {_j(a.get('threat'))} is not what punishes it"

    if t == "CRITICAL_REPLY":
        if not same_move(_str(a.get("move")), r.get("san")):
            return f"the critical reply is {_j(r.get('san'))}"
        if "after" in a and not same_move(_str(a.get("after")), played.get("san")):
            return f"the move played was {_j(played.get('san'))}"
        if "result" in a:
            mate = r.get("mate", False) or section(g, "played_line").get("mate_against_player", False)
            actual = "MATE" if mate else "MATERIAL" if r.get("net_loss", 0) > 0 else "NONE"
            if actual != _str(a.get("result")):
                return f"result is {actual}"
        if "ply" in a and num(a.get("ply")) != r.get("consequence_ply", 0):
            return f"consequence ply is {r.get('consequence_ply', 0)}"
        return None

    if t == "MATERIAL_CHANGE":
        actual = section(g, "best_line").get("net_loss", 0) if _str(a.get("line")) == "BEST" else r.get("net_loss", 0)
        return None if num(a.get("amount")) == actual else f"net material is {actual}"

    if t == "MATE_IN":
        if _str(a.get("line")) == "BEST":
            actual = section(g, "best_line").get("mate_in")
        else:
            actual = r.get("mate_in") if r.get("mate", False) else None
        return None if actual is not None and num(a.get("n")) == actual else f"mate in {_j(actual)}"

    if t == "COUNTERFACTUAL":
        if not same_move(_str(a.get("alt_move")), best.get("san")):
            return f"the engine's move is {_j(best.get('san'))}"
        if "reply" in a and not same_move(_str(a.get("reply")), r.get("san")):
            return f"the reply is {_j(r.get('san'))}"
        if "played" in a and not same_move(_str(a.get("played")), played.get("san")):
            return f"the move played was {_j(played.get('san'))}"
        effect = _str(a.get("effect"))
        bl = section(g, "best_line")
        holds = {
            "REPLY_ILLEGAL": r.get("san") is not None and not cf.get("best_is_played", False)
                             and not cf.get("legal_after_best", False),
            "REPLY_FAILS": cf.get("legal_after_best", False) and cf.get("parries", False),
            "WINS_MATERIAL": bl.get("net_loss", 0) <= -1,
            "MATES": bl.get("mate_for_player", False),
        }.get(effect, False)
        return None if holds else f"{_j(a.get('effect'))} does not hold after {_j(best.get('san'))}"

    if t == "TACTIC_GEOMETRY":
        kind, by = _str(a.get("kind")), _str(a.get("by"))
        targets = set(strings(a.get("targets")))
        found = any(x.get("kind") == kind and x.get("by") == by and set(lst(x.get("targets"))) == targets
                    for x in lst(g.get("geometry")))
        if not found:
            return f"no {kind} by {by} on {_java_set(targets)}"
        if "move" in a and not same_move(_str(a.get("move")), r.get("san")):
            return f"the geometry belongs to {_j(r.get('san'))}"
        return None

    if t == "VISIBILITY":
        d1 = section(g, "d1")
        if d1.get("band") != _str(a.get("band")):
            return f"visibility is {_j(d1.get('band'))}"
        if a.get("depth") is not None and num(a.get("depth")) != d1.get("depth"):
            return f"visibility depth is {_j(d1.get('depth'))}"
        return None

    return "unknown claim type"


def _attack_defense(g: dict, a: dict) -> str | None:
    """ATTACK_DEFENSE, checked on the board itself: P0 by default, PP on request."""
    board = chess.Board(section(g, "header").get("fen"))
    if _str(a.get("position")) == "PP":
        try:
            board.push_san(section(g, "played").get("san"))
        except Exception:
            return "cannot replay the played move"
    name = _str(a.get("square")).upper()
    if name not in _SQUARES:
        return f"not a square: {_j(a.get('square'))}"
    square = chess.parse_square(name.lower())
    piece = board.piece_at(square)
    if piece is None:
        return f"nothing stands on {_j(a.get('square'))}"
    attackers = {chess.square_name(s) for s in board.attackers(not piece.color, square)}
    defenders = {chess.square_name(s) for s in board.attackers(piece.color, square)} - {chess.square_name(square)}
    if squares_in(strings(a.get("attackers"))) != attackers:
        return f"attackers of {name} are {_java_set(attackers)}"
    if squares_in(strings(a.get("defenders"))) != defenders:
        return f"defenders of {name} are {_java_set(defenders)}"
    return None


_SQUARES = {f + r for f in "ABCDEFGH" for r in "12345678"}


def _line_claim(g: dict, a: dict, opened: bool) -> str | None:
    """A line claim matches M's Δ, or one of B's effects: "g6 blocks the diagonal" is about B."""
    slider, target = _str(a.get("slider")), _str(a.get("target"))
    kind = "LINE_OPENED" if opened else "LINE_BLOCKED"
    in_delta = any(d.get("kind") == kind and slider == d.get("slider") and target == d.get("target")
                   for d in lst(g.get("delta")))
    in_effects = not opened and any(
        e.get("kind") == "BLOCKS_LINE" and len(lst(e.get("squares"))) >= 2
        and e["squares"][0] == slider and e["squares"][1] == target for e in lst(g.get("best_effects")))
    if in_delta or in_effects:
        return None
    return f"no line {'opened' if opened else 'blocked'} from {slider} to {target}"


# ── rules 4, 5 and 7 helpers ─────────────────────────────────────────────────

def _has_true(chain: list, truth: list[bool], type_: str) -> bool:
    return any(truth[i] and chain[i].get("type") == type_ for i in range(len(chain)))


def _earned_text(chain: list, truth: list[bool]) -> str:
    """Every argument value of every verified claim, normalised, as one searchable string."""
    s = [" "]
    for i, c in enumerate(chain):
        if not truth[i] or c.get("args") is None:
            continue
        for value in c["args"].values():
            for part in strings(value):
                s.append(norm(part) + " ")
    return "".join(s)


def tokens(prose: str) -> list[str]:
    """Moves and squares named in prose, move numbers stripped, check marks dropped."""
    stripped = MOVE_NUMBER.sub("", prose)
    out: dict[str, None] = {}
    for m in MOVE_OR_SQUARE.finditer(stripped):
        out[norm(m.group(1))] = None
    return list(out)


def _claim_squares(c: dict) -> list[str]:
    out: dict[str, None] = {}
    for key, value in (c.get("args") or {}).items():
        if key in SQUARE_KEYS:
            for sq in squares_in(strings(value)):
                out[sq] = None
    return list(out)


# ── small helpers ────────────────────────────────────────────────────────────

def squares_in(toks: list[str]) -> set[str]:
    """The square each token refers to: "Qh5" -> h5, "e8" -> e8."""
    out = set()
    for t in toks:
        found = SQUARE.findall(t)
        if found:
            out.add(found[-1])
    return out


def _java_trim(s: str) -> str:
    """String.trim(): strips every char <= U+0020, not Unicode whitespace."""
    i, j = 0, len(s)
    while i < j and s[i] <= " ":
        i += 1
    while j > i and s[j - 1] <= " ":
        j -= 1
    return s[i:j]


def norm(san: str | None) -> str | None:
    """SAN compared without check marks, annotations or a leading move number."""
    if san is None:
        return None
    return re.sub(r"[+#!?]", "", LEADING_MOVE_NUMBER.sub("", _java_trim(san), count=1))


def same_move(a: str | None, b: str | None) -> bool:
    return a is not None and b is not None and norm(a) == norm(b)


def _str(o) -> str:
    return "" if o is None else java_str(o)


def _j(o) -> str:
    """A value as Java string concatenation prints it."""
    return java_str(o)


def _java_set(s: set) -> str:
    # Java prints a HashSet in hash order; only the members matter, so sorted here.
    return "[" + ", ".join(sorted(s)) + "]"


def num(o) -> int:
    """Number.intValue(), or Integer.parseInt of the text; MIN_VALUE when neither works."""
    if isinstance(o, bool) or o is None:
        return INT_MIN
    if isinstance(o, int):
        return ((o + 2 ** 31) % 2 ** 32) - 2 ** 31
    if isinstance(o, float):
        if o != o:
            return 0
        return max(INT_MIN, min(2 ** 31 - 1, int(o)))
    s = _java_trim(java_str(o))
    if re.fullmatch(r"[+-]?[0-9]+", s, re.ASCII):
        n = int(s)
        if INT_MIN <= n <= 2 ** 31 - 1:
            return n
    return INT_MIN


def strings(o) -> list[str]:
    if o is None:
        return []
    if isinstance(o, list):
        return [java_str(x) for x in o]
    return [java_str(o)]
