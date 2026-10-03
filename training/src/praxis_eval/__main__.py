"""Verify model answers over a test set (EvalCli verify, row for row).

    python -m praxis_eval verify --testset test.jsonl --outputs outputs.jsonl --out verified.jsonl

testset: one item per line, each with "id", "slice", "graph" and "renders".
outputs: one answer per line, {"item_id", "arm", "level", "raw", "latency_ms"}
         or {"item_id", "arm", "level", "error"} for a call that failed.
out:     the rules' own diagnosis of every item (arm "rules", the S_rules
         baseline), then every answer with its verdict, in the shape the
         metric code (praxis_train.baseline_metrics.score_system) reads.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from praxis_eval.jackson import NotADiagnosis, parse_diagnosis
from praxis_eval.rules import diagnose
from praxis_eval.verifier import verify


def result(item: dict, arm: str, level: str, g: dict, d: dict | None, parsed: bool,
           error: str | None, latency_ms: int) -> dict:
    r = {"item_id": item["id"], "arm": arm, "level": level, "slice": item["slice"], "parsed": parsed}
    if error is not None:
        r["error"] = error[:300]
    r["latency_ms"] = latency_ms
    r["tokens_in"] = ((item.get("renders") or {}).get(level) or {}).get("tokens", 0)
    if d is not None:
        claim = verify(g, d, "CLAIM")
        r["claim_passed"] = claim.passed
        r["claims"] = claim.claims
        r["claims_true"] = claim.claims_true
        r["violations"] = [f"rule {x.rule}: {x.message}" for x in claim.violations]
        if level == "R3":
            r["citation_passed"] = verify(g, d, "CITATION").passed
        for k in ("consequence", "mechanism", "motif", "visibility"):
            if d.get(k) is not None:
                r[k] = d[k]
        r["chain_types"] = [None if c is None else c.get("type") for c in (d.get("reasoning_chain") or [])]
        if d.get("explanation") is not None:
            r["explanation"] = d["explanation"]
    return r


def verify_file(testset: Path, outputs: Path, out: Path) -> int:
    items = {}
    for line in testset.read_text(encoding="utf-8").splitlines():
        if line.strip():
            it = json.loads(line)
            items[it["id"]] = it
    rows = 0
    with out.open("w", encoding="utf-8", newline="\n") as w:
        for it in items.values():
            w.write(json.dumps(result(it, "rules", "R3", it["graph"], diagnose(it["graph"]), True, None, 0),
                               ensure_ascii=False, separators=(",", ":")) + "\n")
            rows += 1
        if outputs.exists():
            for line in outputs.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                o = json.loads(line)
                it = items.get(o["item_id"])
                if it is None:
                    continue
                d, error = None, o.get("error")
                if error is None:
                    try:
                        d = parse_diagnosis(o["raw"])
                    except NotADiagnosis as e:
                        error = f"unparseable: not a diagnosis: {e}"
                w.write(json.dumps(result(it, o["arm"], o["level"], it["graph"], d, d is not None, error,
                                          int(o.get("latency_ms") or 0)),
                                   ensure_ascii=False, separators=(",", ":")) + "\n")
                rows += 1
    return rows


def main() -> None:
    ap = argparse.ArgumentParser(prog="python -m praxis_eval", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("verify", help="verify answers; writes the rules' baseline too")
    v.add_argument("--testset", type=Path, required=True)
    v.add_argument("--outputs", type=Path, required=True)
    v.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    if args.cmd == "verify":
        print(f"verified {verify_file(args.testset, args.outputs, args.out)} answers", file=sys.stderr)


if __name__ == "__main__":
    main()
