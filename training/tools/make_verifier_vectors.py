"""Phase 11: verifier test vectors, judged by the Java verifier itself.

    python tools/make_verifier_vectors.py            (from training/; needs JDK 26, see javacli.sh)

Writes tests/verifier_test_vectors.jsonl.gz (gzip, one JSON object per line): graph + answer text + the verdict the
Java verifier (EvalCli verify) gave. Two kinds of answer:

  real     a stratified sample of the published model answers on the public
           test set, so every violation rule seen in practice is covered;
  mutated  real answers broken on purpose: invalid JSON, wrong types, unknown
           claim types, missing arguments, chains too long, bad citations,
           causal words without a counterfactual, move numbers and check
           marks, ATTACK_DEFENSE claims built from the board. Real answers
           never reach these paths because decoding was schema-constrained.

A port of the verifier is correct when it reproduces every expected verdict;
tests/test_parity.py checks praxis_eval against this file.
"""

from __future__ import annotations

import gzip
import io
import json
import os
import random
import subprocess
import sys
from pathlib import Path

import chess

ROOT = Path(__file__).resolve().parents[1]
TESTSET = ROOT / "data/phase6_v1/test_ab_ablations.jsonl"
VERIFIED = ROOT / "results_private/phase8/grid_ab/verified.jsonl"
OUTPUTS = ROOT / "results_private/phase8/grid_ab/outputs.jsonl"
OUT = ROOT / "tests/verifier_test_vectors.jsonl.gz"
WORK = ROOT / "results_private/phase11_vectors"
# On Windows a bare "bash" can resolve to WSL's; javacli.sh wants Git Bash.
GIT_BASH = "C:/Program Files/Git/bin/bash.exe"
BASH = GIT_BASH if os.name == "nt" and Path(GIT_BASH).exists() else "bash"


def load(p: Path) -> list[dict]:
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def real_sample(rng: random.Random, verified: list[dict], outputs: dict) -> list[dict]:
    """Up to 12 answers per (arm, first violation rule or 'pass'): every rule seen, every arm."""
    groups: dict[tuple, list] = {}
    for r in verified:
        if r["arm"] == "rules":
            continue
        rules = sorted({v.split(":")[0] for v in r.get("violations", [])}) or ["pass"]
        for rule in rules:
            groups.setdefault((r["arm"], r["level"], rule), []).append(r)
    picked = {}
    for key in sorted(groups):
        for r in rng.sample(groups[key], min(12, len(groups[key]))):
            k = (r["arm"], r["level"], r["item_id"])
            picked[k] = {"item_id": r["item_id"], "arm": r["arm"], "level": r["level"],
                         "raw": outputs[k]["raw"], "latency_ms": 0}
    return list(picked.values())


def attack_defense(item: dict, position: str, honest: bool) -> dict | None:
    """An ATTACK_DEFENSE claim about the played move's destination, true by the board unless `honest` is False."""
    g = item["graph"]
    board = chess.Board(g["header"]["fen"])
    sq_name = g["played"]["to"] if position == "PP" else g["played"]["from"]
    if position == "PP":
        board.push_san(g["played"]["san"])
    sq = chess.parse_square(sq_name)
    piece = board.piece_at(sq)
    if piece is None:
        return None
    att = sorted(chess.square_name(s) for s in board.attackers(not piece.color, sq))
    dfn = sorted(chess.square_name(s) for s in board.attackers(piece.color, sq))
    if not honest:
        att = att + ["a1"] if "a1" not in att else att[1:]
    args = {"square": sq_name, "attackers": att, "defenders": dfn}
    if position == "PP":
        args["position"] = "PP"
    return {"type": "ATTACK_DEFENSE", "args": args, "cites": ["M"], "text": "Attackers and defenders."}


def mutations(rng: random.Random, items: dict, base: list[dict]) -> list[dict]:
    out = []

    def add(name: str, src: dict, raw: str) -> None:
        out.append({"item_id": src["item_id"], "arm": f"mut-{name}", "level": src["level"], "raw": raw,
                    "latency_ms": 0})

    def edit(name: str, src: dict, fn) -> None:
        d = json.loads(src["raw"])
        fn(d)
        add(name, src, json.dumps(d, ensure_ascii=False))

    for src in base:
        raw = src["raw"]
        add("truncated", src, raw[: len(raw) // 2])
        add("trailing-text", src, raw + "\nThat is my answer.")
        add("leading-space", src, "\n  " + raw)
        edit("chain-too-long", src, lambda d: d.update(reasoning_chain=(d["reasoning_chain"] * 8)[:9]))
        edit("bad-vocab", src, lambda d: d.update(consequence="BOGUS", motif=None, visibility=3))
        edit("no-mechanism", src, lambda d: d.pop("mechanism", None))
        edit("unknown-claim", src, lambda d: d["reasoning_chain"].insert(0, {"type": "FOO", "args": {}, "cites": [],
                                                                             "text": "x"}))
        edit("missing-arg", src, lambda d: [c["args"].clear() for c in d["reasoning_chain"][:1]])
        edit("args-null", src, lambda d: [c.update(args=None) for c in d["reasoning_chain"][:1]])
        edit("null-claim", src, lambda d: d["reasoning_chain"].append(None))
        edit("bad-effect", src, lambda d: d["reasoning_chain"].append(
            {"type": "COUNTERFACTUAL", "args": {"alt_move": "e4", "effect": "WINS"}, "cites": [], "text": "x"}))
        edit("no-cites", src, lambda d: [c.update(cites=[]) for c in d["reasoning_chain"]])
        edit("ghost-cite", src, lambda d: [c.update(cites=["Z9", "Δ99"]) for c in d["reasoning_chain"]])
        edit("cites-null", src, lambda d: [c.pop("cites", None) for c in d["reasoning_chain"]])
        edit("causal-words", src, lambda d: d.update(explanation=(d.get("explanation") or "")
                                                     + " This happened because the threat was already there."))
        edit("extra-moves", src, lambda d: d.update(explanation=(d.get("explanation") or "")
                                                    + " Compare 12...Qxd5+, Nf3, h8=Q, O-O-O and e4."))
        edit("move-number-reply", src, lambda d: d.update(
            critical_response=f"1... {d.get('critical_response')}+" if d.get("critical_response") else "1. e4"))
        edit("string-numbers", src, lambda d: [c["args"].update({k: str(v) for k, v in c["args"].items()
                                                                 if isinstance(v, int) and not isinstance(v, bool)})
                                               for c in d["reasoning_chain"]])
        edit("float-numbers", src, lambda d: [c["args"].update({k: v + 0.5 for k, v in c["args"].items()
                                                                if isinstance(v, int) and not isinstance(v, bool)})
                                              for c in d["reasoning_chain"]])
        edit("bool-args", src, lambda d: [c["args"].update(ply=True, depth=False) for c in d["reasoning_chain"]])
        edit("padded-san", src, lambda d: [c["args"].update({k: f" {v}! " for k, v in c["args"].items()
                                                             if k in ("move", "alt_move", "reply", "threat")
                                                             and isinstance(v, str)})
                                           for c in d["reasoning_chain"]])
        edit("nested-args", src, lambda d: [c["args"].update(note={"a": [1, 2.5, None], "b": True})
                                            for c in d["reasoning_chain"][:1]])
        item = items[src["item_id"]]
        for position in ("P0", "PP"):
            for honest in (True, False):
                claim = attack_defense(item, position, honest)
                if claim:
                    edit(f"attack-defense-{position}-{'true' if honest else 'false'}", src,
                         lambda d, c=claim: d["reasoning_chain"].insert(0, c))

    # Whole-answer shapes Jackson rejects or reads as nothing, on a few items.
    for src in base[:6]:
        add("empty", src, "")
        add("json-null", src, "null")
        add("json-array", src, "[]")
        add("json-string", src, "\"diagnosis\"")
        add("nan", src, src["raw"].replace("{", "{\"x\": NaN, ", 1))
        edit("chain-object", src, lambda d: d.update(reasoning_chain={"type": "MATE_IN"}))
        edit("claim-string", src, lambda d: d.update(reasoning_chain=["MATE_IN 2"]))
        edit("cites-string", src, lambda d: [c.update(cites="T1") for c in d["reasoning_chain"][:1]])
        edit("consequence-list", src, lambda d: d.update(consequence=["MATED"]))
        edit("consequence-number", src, lambda d: d.update(consequence=1.50))
        edit("args-list", src, lambda d: [c.update(args=["e4"]) for c in d["reasoning_chain"][:1]])
    return out


def main() -> None:
    rng = random.Random(0)
    items = {i["id"]: i for i in load(TESTSET)}
    outputs = {(o["arm"], o["level"], o["item_id"]): o for o in load(OUTPUTS) if "raw" in o}
    real = real_sample(rng, load(VERIFIED), outputs)
    # Mutate R3 answers from both trained sizes, and a few from the other levels.
    r3 = [r for r in real if r["level"] == "R3"]
    other = [r for r in real if r["level"] != "R3"]
    base = rng.sample(r3, min(40, len(r3))) + rng.sample(other, min(20, len(other)))
    answers = real + mutations(rng, items, base)

    WORK.mkdir(parents=True, exist_ok=True)
    outputs_file, verified_file = WORK / "outputs.jsonl", WORK / "verified.jsonl"
    outputs_file.write_text("".join(json.dumps(a, ensure_ascii=False) + "\n" for a in answers), encoding="utf-8")
    subprocess.run([BASH, "tools/javacli.sh", "EvalCli", "verify", "--testset", str(TESTSET),
                    "--outputs", str(outputs_file), "--out", str(verified_file)], cwd=ROOT, check=True)

    by_key = {(r["arm"], r["level"], r["item_id"]): r for r in load(verified_file) if r["arm"] != "rules"}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    # mtime=0: the same vectors give the same bytes, so a rebuild shows no diff.
    with gzip.GzipFile(OUT, "wb", mtime=0) as gz, io.TextIOWrapper(gz, encoding="utf-8", newline="\n") as w:
        for a in answers:
            r = by_key[(a["arm"], a["level"], a["item_id"])]
            expected = {"parsed": r["parsed"], "parse_error": "error" in r}
            if r["parsed"]:
                expected.update(claim_passed=r["claim_passed"], claims=r["claims"], claims_true=r["claims_true"],
                                violation_rules=sorted(int(v.split(":")[0][5:]) for v in r["violations"]))
                if "citation_passed" in r:
                    expected["citation_passed"] = r["citation_passed"]
            vector = {"id": f"{a['arm']}|{a['level']}|{a['item_id']}", "kind": "mutated" if a["arm"].startswith("mut-")
                      else "real", "level": a["level"], "graph": items[a["item_id"]]["graph"], "answer": a["raw"],
                      "expected": expected}
            w.write(json.dumps(vector, ensure_ascii=False) + "\n")
            n += 1
    print(f"{n} vectors -> {OUT.relative_to(ROOT)}", file=sys.stderr)


if __name__ == "__main__":
    main()
