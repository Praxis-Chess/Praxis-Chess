#!/usr/bin/env python3
"""
Prax tool-use evaluation.

Judging an agent by demos measures the demos. This runs a fixed question set and
scores the mechanics of the run — which tools were chosen, which were missed,
whether arguments were well-formed, how long the loop ran, and whether the prose
is backed by citations. The output is a scorecard you can diff between changes.

Requires the backend on :8086 with Ollama and Stockfish up. Nothing is mocked;
the point is to measure the real thing.

    python eval/prax_eval.py                      # full set
    python eval/prax_eval.py --category blunders  # one group
    python eval/prax_eval.py --limit 5            # smoke test
    python eval/prax_eval.py --out runs/after.json
    python eval/prax_eval.py --compare runs/before.json runs/after.json
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict

BASE = os.environ.get("PRAX_BASE", "http://localhost:8086")
HERE = os.path.dirname(os.path.abspath(__file__))

# Tools whose result is a specific object rather than a population; a second
# identical call is waste, not corroboration.
ENGINE_TOOLS = {"analyze_position"}


def post(path, payload, timeout=180):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read().decode()
        return json.loads(body) if body else {}


def reset():
    """Conversation history leaks between questions — a greeting once replayed
    the previous answer's statistics verbatim. Every case starts clean."""
    try:
        post("/api/prax/reset", {})
    except Exception:
        pass


# ── metric helpers ────────────────────────────────────────────────────────────

NUM = re.compile(r"-?\d+(?:\.\d+)?")


def numbers_in(text):
    """Figures the answer asserts. Move numbers like '28...' and SAN are noise,
    so drop anything glued to a letter."""
    if not text:
        return []
    cleaned = re.sub(r"\b[NBRQK]?[a-h]?[1-8]?x?[a-h][1-8](?:=[NBRQ])?[+#]?\b", " ", text)
    cleaned = re.sub(r"\b\d+\s*\.\.\.", " ", cleaned)
    cleaned = re.sub(r"\b\d+\s*\.", " ", cleaned)
    return NUM.findall(cleaned)


def grounded(answer, evidence, findings):
    """Every figure in the prose should also appear in a citation or a rendered
    fact. Returns (cited, total) — total 0 means the answer asserted nothing
    numeric, which is not a failure."""
    nums = numbers_in(answer)
    if not nums:
        return 0, 0
    backing = " ".join(
        [str(e.get("value", "")) for e in evidence]
        + [str(e.get("label", "")) for e in evidence]
        + [str(f) for f in findings]
    )
    backing_nums = set(NUM.findall(backing))
    cited = sum(1 for n in nums if n in backing_nums)
    return cited, len(nums)


def bad_args(steps):
    """Argument mistakes we can check deterministically."""
    problems = []
    for s in steps:
        tool, args = s.get("tool"), s.get("args") or {}
        if tool == "analyze_position":
            if not args.get("fen"):
                problems.append("analyze_position without fen")
            if not args.get("playedMove"):
                problems.append("analyze_position without playedMove")
        if tool == "get_game" and not args.get("gameId"):
            problems.append("get_game without gameId")
        for key in ("color", "result"):
            v = args.get(key)
            if isinstance(v, str) and v.lower() in ("both", "all", "any", "either"):
                problems.append(f"{tool}: {key}='{v}' (should be omitted)")
        if tool == "find_mistakes":
            sev = args.get("severity")
            if isinstance(sev, str) and sev.upper() not in ("BLUNDER", "MISTAKE", "INACCURACY"):
                problems.append(f"find_mistakes: severity='{sev}'")
    return problems


def repeated(steps):
    seen, dupes = set(), []
    for s in steps:
        key = (s.get("tool"), json.dumps(s.get("args") or {}, sort_keys=True))
        if key in seen:
            dupes.append(s.get("tool"))
        seen.add(key)
    # An engine call on the same position twice is always waste.
    return dupes


# ── running one case ──────────────────────────────────────────────────────────


def run_case(case):
    reset()
    t0 = time.time()
    try:
        res = post("/api/prax/ask", {"question": case["question"]})
        err = None
    except Exception as e:  # noqa: BLE001 — a failed request is a result
        res, err = {}, str(e)

    elapsed = time.time() - t0
    steps = res.get("steps") or []
    called = [s.get("tool") for s in steps]
    answer = res.get("answer") or ""
    evidence = res.get("evidence") or []
    findings = res.get("findings") or []

    expect = set(case.get("expectTools") or [])
    allow = set(case.get("allowTools") or [])
    forbid = set(case.get("forbidTools") or [])
    called_set = set(called)

    missed = sorted(expect - called_set)
    unnecessary = sorted(called_set - expect - allow)
    forbidden = sorted(called_set & forbid)
    argprobs = bad_args(steps)
    dupes = repeated(steps)
    cited, total_nums = grounded(answer, evidence, findings)

    said = [p for p in (case.get("mustNotSay") or []) if p.lower() in answer.lower()]

    failures = []
    if err:
        failures.append(f"request failed: {err}")
    if missed:
        failures.append("missed tools: " + ", ".join(missed))
    if forbidden:
        failures.append("forbidden tools: " + ", ".join(forbidden))
    if unnecessary:
        failures.append("unnecessary tools: " + ", ".join(unnecessary))
    if argprobs:
        failures.append("bad args: " + "; ".join(sorted(set(argprobs))))
    if dupes:
        failures.append("repeated calls: " + ", ".join(sorted(set(dupes))))
    if len(steps) > case.get("maxCalls", 99):
        failures.append(f"loop too long: {len(steps)} > {case['maxCalls']}")
    if case.get("requireEvidence") and not evidence:
        failures.append("no validated evidence")
    if case.get("requireFindings") and not findings:
        failures.append("no verbatim findings")
    if total_nums and cited < total_nums:
        failures.append(f"ungrounded figures: {total_nums - cited}/{total_nums}")
    if said:
        failures.append("said: " + "; ".join(said))
    if not answer.strip():
        failures.append("empty answer")

    return {
        "id": case["id"],
        "category": case["category"],
        "question": case["question"],
        "pass": not failures,
        "failures": failures,
        "calls": called,
        "callCount": len(steps),
        "evidenceCount": len(evidence),
        "findingCount": len(findings),
        "groundedFigures": [cited, total_nums],
        "seconds": round(elapsed, 1),
        "answer": answer,
    }


# ── scorecard ─────────────────────────────────────────────────────────────────


def scorecard(results):
    n = len(results)
    if not n:
        return {}
    agg = {
        "cases": n,
        "passed": sum(1 for r in results if r["pass"]),
        "toolSelectionAccuracy": sum(
            1 for r in results if not any(f.startswith(("missed", "forbidden")) for f in r["failures"])
        ),
        "missedCallCases": sum(1 for r in results if any(f.startswith("missed") for f in r["failures"])),
        "unnecessaryCallCases": sum(1 for r in results if any(f.startswith("unnecessary") for f in r["failures"])),
        "badArgCases": sum(1 for r in results if any(f.startswith("bad args") for f in r["failures"])),
        "repeatedCallCases": sum(1 for r in results if any(f.startswith("repeated") for f in r["failures"])),
        "overLongLoops": sum(1 for r in results if any(f.startswith("loop too long") for f in r["failures"])),
        "ungroundedCases": sum(1 for r in results if any(f.startswith("ungrounded") for f in r["failures"])),
        "avgLoopLength": round(sum(r["callCount"] for r in results) / n, 2),
        "avgSeconds": round(sum(r["seconds"] for r in results) / n, 1),
    }
    cited = sum(r["groundedFigures"][0] for r in results)
    total = sum(r["groundedFigures"][1] for r in results)
    agg["groundedness"] = f"{cited}/{total}" + (f" ({100*cited//total}%)" if total else "")
    agg["passRate"] = f"{100 * agg['passed'] // n}%"
    agg["toolSelectionRate"] = f"{100 * agg['toolSelectionAccuracy'] // n}%"
    return agg


def print_report(results, agg):
    by_cat = defaultdict(list)
    for r in results:
        by_cat[r["category"]].append(r)

    for cat in sorted(by_cat):
        rows = by_cat[cat]
        ok = sum(1 for r in rows if r["pass"])
        print(f"\n{cat.upper()}  ({ok}/{len(rows)})")
        for r in rows:
            mark = "ok  " if r["pass"] else "FAIL"
            calls = ",".join(r["calls"]) or "-"
            print(f"  {mark} {r['id']:<28} {r['callCount']}c {r['seconds']:>5}s  [{calls}]")
            for f in r["failures"]:
                print(f"         · {f}")

    print("\n" + "=" * 60)
    print("SCORECARD")
    print("=" * 60)
    for k in ("cases", "passed", "passRate", "toolSelectionRate", "missedCallCases",
              "unnecessaryCallCases", "badArgCases", "repeatedCallCases",
              "overLongLoops", "ungroundedCases", "groundedness",
              "avgLoopLength", "avgSeconds"):
        print(f"  {k:<22} {agg.get(k)}")


def compare(a_path, b_path):
    a = json.load(open(a_path))
    b = json.load(open(b_path))
    am = {r["id"]: r for r in a["results"]}
    bm = {r["id"]: r for r in b["results"]}
    print(f"{'case':<30} {'before':>8} {'after':>8}")
    print("-" * 50)
    fixed = broke = 0
    for cid in sorted(set(am) | set(bm)):
        pa = am.get(cid, {}).get("pass")
        pb = bm.get(cid, {}).get("pass")
        if pa == pb:
            continue
        if pb:
            fixed += 1
        else:
            broke += 1
        print(f"{cid:<30} {str(pa):>8} {str(pb):>8}   {'FIXED' if pb else 'REGRESSED'}")
    print("-" * 50)
    print(f"fixed: {fixed}   regressed: {broke}")
    print(f"pass rate: {a['scorecard'].get('passRate')} -> {b['scorecard'].get('passRate')}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--category")
    ap.add_argument("--id")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--out")
    ap.add_argument("--compare", nargs=2, metavar=("BEFORE", "AFTER"))
    args = ap.parse_args()

    if args.compare:
        compare(*args.compare)
        return 0

    spec = json.load(open(os.path.join(HERE, "questions.json"), encoding="utf-8"))
    cases = spec["cases"]
    if args.category:
        cases = [c for c in cases if c["category"] == args.category]
    if args.id:
        cases = [c for c in cases if c["id"] == args.id]
    if args.limit:
        cases = cases[: args.limit]

    if not cases:
        print("no cases matched")
        return 2

    try:
        urllib.request.urlopen(BASE + "/api/prax/status", timeout=5)
    except Exception as e:  # noqa: BLE001
        print(f"backend not reachable at {BASE}: {e}")
        return 2

    print(f"running {len(cases)} cases against {BASE}\n")
    results = []
    for i, c in enumerate(cases, 1):
        r = run_case(c)
        results.append(r)
        mark = "ok  " if r["pass"] else "FAIL"
        print(f"  [{i}/{len(cases)}] {mark} {r['id']} ({r['seconds']}s)")

    agg = scorecard(results)
    print_report(results, agg)

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        json.dump({"scorecard": agg, "results": results}, open(args.out, "w"), indent=2)
        print(f"\nwritten to {args.out}")

    return 0 if agg["passed"] == agg["cases"] else 1


if __name__ == "__main__":
    sys.exit(main())
