"""Scores a Phase 5 run from EvalCli's verified answers (plan §15.2, §15.5).

Run: python -m praxis_train.baseline_metrics --run-dir DIR [--data training/data/phase5]

Reads DIR/verified.jsonl (one row per answer, judged by the app's verifier) and
writes DIR/metrics.json and DIR/report.md. Only aggregates leave this module:
the per-item file stays in the private run directory (§11.6).

Every system answered the same items, so comparisons are paired: McNemar's exact
test on per-item pass/fail, and bootstrap 95% intervals over items for rates.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import statistics
import sys
from pathlib import Path

MECHANISM_RULES = {"IGNORED_THREAT", "REMOVED_DEFENDER", "MOVED_INTO_ATTACK",
                   "LOSING_CAPTURE", "CREATED_TACTIC", "MISSED_OPPORTUNITY"}
BOOTSTRAP = 1000


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def latest(rows: list[dict]) -> dict[tuple, dict]:
    """One answer per (arm, level, item): a parsed one if any exists, else the last failure."""
    out: dict[tuple, dict] = {}
    for r in rows:
        key = (r["arm"], r["level"], r["item_id"])
        if key not in out or (r.get("parsed") and not out[key].get("parsed")):
            out[key] = r
    return out


def ci(values: list[float], rng: random.Random) -> list[float] | None:
    """Bootstrap 95% interval of a mean over items."""
    if not values:
        return None
    n = len(values)
    means = sorted(sum(values[rng.randrange(n)] for _ in range(n)) / n for _ in range(BOOTSTRAP))
    return [round(means[int(0.025 * BOOTSTRAP)], 3), round(means[int(0.975 * BOOTSTRAP) - 1], 3)]


def ratio_ci(pairs: list[tuple[int, int]], rng: random.Random) -> list[float] | None:
    """Bootstrap interval of sum(a)/sum(b) over items (claim precision pools claims)."""
    if not pairs or sum(b for _, b in pairs) == 0:
        return None
    n = len(pairs)
    vals = []
    for _ in range(BOOTSTRAP):
        s = [pairs[rng.randrange(n)] for _ in range(n)]
        den = sum(b for _, b in s)
        if den:
            vals.append(sum(a for a, _ in s) / den)
    vals.sort()
    return [round(vals[int(0.025 * len(vals))], 3), round(vals[int(0.975 * len(vals)) - 1], 3)]


def rate(values: list[float]) -> float | None:
    return round(sum(values) / len(values), 3) if values else None


def mcnemar(a: dict[str, bool], b: dict[str, bool]) -> dict:
    """Exact McNemar on the items both systems answered: does A pass where B fails more than the reverse?"""
    common = a.keys() & b.keys()
    only_a = sum(1 for k in common if a[k] and not b[k])
    only_b = sum(1 for k in common if b[k] and not a[k])
    n = only_a + only_b
    if n == 0:
        return {"items": len(common), "a_only": 0, "b_only": 0, "p": 1.0}
    k = min(only_a, only_b)
    p = min(1.0, 2 * sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n)
    return {"items": len(common), "a_only": only_a, "b_only": only_b, "p": round(p, 4)}


def paired_precision(a: dict[str, tuple[int, int]], b: dict[str, tuple[int, int]], rng: random.Random) -> dict:
    """Difference in pooled claim precision, A minus B, bootstrapped over the items both answered.

    The headline paired test. Chain validity sits at the floor for untrained
    models (almost no answer is free of false claims), so McNemar on it cannot
    separate them; claim precision can.
    """
    common = sorted(a.keys() & b.keys())
    if not common:
        return {"items": 0}

    def prec(rows, keys):
        den = sum(rows[k][1] for k in keys)
        return sum(rows[k][0] for k in keys) / den if den else 0.0

    diff = prec(a, common) - prec(b, common)
    n = len(common)
    boots = []
    for _ in range(BOOTSTRAP):
        sample = [common[rng.randrange(n)] for _ in range(n)]
        boots.append(prec(a, sample) - prec(b, sample))
    boots.sort()
    lo, hi = boots[int(0.025 * BOOTSTRAP)], boots[int(0.975 * BOOTSTRAP) - 1]
    return {"items": n, "diff": round(diff, 3), "ci": [round(lo, 3), round(hi, 3)],
            "excludes_zero": lo > 0 or hi < 0}


def claim_types(rows: list[dict]) -> dict[str, list[int]]:
    """Per claim type: [made, true]. A claim is false when the verifier's rule 1 or 2 names its type."""
    out: dict[str, list[int]] = {}
    for r in rows:
        if not r.get("parsed"):
            continue
        made: dict[str, int] = {}
        for t in r.get("chain_types", []):
            made[t] = made.get(t, 0) + 1
        bad: dict[str, int] = {}
        for v in r.get("violations", []):
            if v.startswith("rule 2: ") or v.startswith("rule 1: claim"):
                t = v.split(": ")[1].split(":")[0].strip()
                bad[t] = bad.get(t, 0) + 1
        for t, n in made.items():
            cell = out.setdefault(t, [0, 0])
            cell[0] += n
            cell[1] += max(0, n - bad.get(t, 0))
    return out


def score_system(rows: list[dict], items: dict[str, dict], rng: random.Random) -> dict:
    """Metrics for one (arm, level) over the items it was asked."""
    valid, precision_pairs, cons, mech, parsed = [], [], [], [], []
    human_cons, human_mech = [], []
    abstain, invented = [], []
    comp_cov, comp_prec = [], []
    latencies, tokens = [], []
    for r in rows:
        it = items[r["item_id"]]
        rules = it["rules"]
        ok = bool(r.get("parsed"))
        parsed.append(1.0 if ok else 0.0)
        valid.append(1.0 if ok and r.get("claim_passed") else 0.0)
        tokens.append(r.get("tokens_in", 0))
        if ok:
            precision_pairs.append((r.get("claims_true", 0), r.get("claims", 0)))
            if r.get("latency_ms"):
                latencies.append(r["latency_ms"])
        c, m = r.get("consequence"), r.get("mechanism")
        cons.append(1.0 if ok and c == rules["consequence"] else 0.0)
        if rules["single_cause"]:
            mech.append(1.0 if ok and m == rules["mechanism"] else 0.0)
        if "human" in it:
            human_cons.append(1.0 if ok and c == it["human"]["consequence"] else 0.0)
            human_mech.append(1.0 if ok and m == it["human"]["mechanism"] else 0.0)
        if rules["consequence"] == "NOT_CONCRETE":
            abstain.append(1.0 if ok and c == "NOT_CONCRETE" and m == "NONE" else 0.0)
            invented.append(1.0 if ok and m in MECHANISM_RULES else 0.0)
        if rules["composite"]:
            named = ok and m in set(rules["fired"])
            comp_cov.append(1.0 if named else 0.0)
            if named:
                comp_prec.append(1.0 if r.get("claim_passed") else 0.0)

    claims = sum(b for _, b in precision_pairs)
    return {
        "items": len(rows),
        "parsed": rate(parsed),
        "claim_precision": round(sum(a for a, _ in precision_pairs) / claims, 3) if claims else None,
        "claim_precision_ci": ratio_ci(precision_pairs, rng),
        "claims_per_answer": round(claims / len(precision_pairs), 2) if precision_pairs else None,
        "chain_validity": rate(valid), "chain_validity_ci": ci(valid, rng),
        "consequence_vs_rules": rate(cons), "consequence_vs_rules_ci": ci(cons, rng),
        "mechanism_vs_rules_single_cause": rate(mech), "mechanism_ci": ci(mech, rng), "single_cause_n": len(mech),
        "consequence_vs_human": rate(human_cons), "mechanism_vs_human": rate(human_mech), "human_n": len(human_cons),
        "abstention": rate(abstain), "abstention_ci": ci(abstain, rng), "invented_cause_rate": rate(invented),
        "not_concrete_n": len(abstain),
        "composite_coverage": rate(comp_cov), "composite_precision": rate(comp_prec), "composite_n": len(comp_cov),
        "latency_p50_ms": round(statistics.median(latencies)) if latencies else None,
        "latency_p95_ms": round(sorted(latencies)[int(0.95 * (len(latencies) - 1))]) if latencies else None,
        "tokens_in_mean": round(statistics.mean(tokens)) if tokens else None,
    }


def pct(x) -> str:
    return "—" if x is None else f"{100 * x:.0f}%"


def interval(x) -> str:
    return "" if not x else f" ({100 * x[0]:.0f}–{100 * x[1]:.0f})"


def table(title: str, note: str, systems: dict) -> list[str]:
    lines = [f"## {title}", "", note, "",
             "| System | Claim precision | Chain valid | Consequence vs rules | Mechanism vs rules | "
             "Abstains (NOT_CONCRETE) | Invents a cause | vs your labels (cons / mech) | p50 latency | Tokens in |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for name, m in systems.items():
        latency = "—" if m["latency_p50_ms"] is None else f"{m['latency_p50_ms'] / 1000:.1f} s"
        lines.append(
            f"| {name} | {pct(m['claim_precision'])}{interval(m['claim_precision_ci'])} "
            f"| {pct(m['chain_validity'])}{interval(m['chain_validity_ci'])} "
            f"| {pct(m['consequence_vs_rules'])} | {pct(m['mechanism_vs_rules_single_cause'])} "
            f"| {pct(m['abstention'])} | {pct(m['invented_cause_rate'])} "
            f"| {pct(m['consequence_vs_human'])} / {pct(m['mechanism_vs_human'])} (n={m['human_n']}) "
            f"| {latency} "
            f"| {m['tokens_in_mean']} |")
    return lines


def report(metrics: dict) -> str:
    lines = ["# Phase 5 — prompted baselines", "",
             "The player's own mistakes: C = blunders and mistakes, C′ = inaccuracies. 95% bootstrap "
             "intervals in brackets. S_rules agrees with the rules by construction, so its "
             "rule-agreement cells say nothing; its cells against your hand labels do.", ""]
    c = metrics["common"]
    lines += table(f"Every system, the shared sample ({c['items']} items: {c['slices']['C']} C, "
                   f"{c['slices']['C_PRIME']} C′)",
                   "The only table where systems are compared with each other.", c["systems"])
    lines.append("")
    a = metrics["all"]
    lines += table(f"Each system on everything it answered (up to {a['items']} items)",
                   "Qwen3.5-2B answered every item at every level; this is the R0–R3 comparison "
                   "at full size.", a["systems"])
    if metrics.get("paired_precision"):
        lines += ["", "## Paired comparisons: claim precision (the headline test)", "",
                  "A minus B on the questions both answered; paired bootstrap 95% interval. "
                  "A difference is real only when the interval excludes zero.", "",
                  "| A vs B | Questions | Difference | 95% interval | Real? |", "|---|---|---|---|---|"]
        for name, t in metrics["paired_precision"].items():
            lines.append(f"| {name} | {t['items']} | {100 * t['diff']:+.1f} pts | "
                         f"{100 * t['ci'][0]:+.1f} to {100 * t['ci'][1]:+.1f} | {'yes' if t['excludes_zero'] else 'no'} |")
    if metrics.get("claim_types"):
        lines += ["", "## Which claims come out true (Qwen3.5-2B, all 890 questions)", "",
                  "| Claim type | R0 | R1 | R2 | R3 |", "|---|---|---|---|---|"]
        ct = metrics["claim_types"]
        types = sorted({t for lvl in ct.values() for t in lvl}, key=lambda t: -sum(ct[l].get(t, [0, 0])[0] for l in ct))
        for t in types:
            cells = []
            for lvl in ["R0", "R1", "R2", "R3"]:
                made, true = ct.get(lvl, {}).get(t, [0, 0])
                cells.append("—" if not made else f"{100 * true / made:.0f}% of {made}")
            lines.append(f"| {t} | " + " | ".join(cells) + " |")
    if metrics.get("paired"):
        lines += ["", "## Paired comparisons: whole answer valid (McNemar exact)", "",
                  "Untrained models almost never produce an answer with zero false claims, so this "
                  "test sits at the floor and cannot separate them. Reported for completeness.", "",
                  "| A vs B | Items | A only passes | B only passes | p |", "|---|---|---|---|---|"]
        for name, t in metrics["paired"].items():
            lines.append(f"| {name} | {t['items']} | {t['a_only']} | {t['b_only']} | {t['p']} |")
    return "\n".join(lines) + "\n"


def main() -> None:
    # The Windows console is cp1252; the report has "′" and "–" in it.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser()
    p.add_argument("--run-dir", required=True)
    p.add_argument("--data", default="training/data/phase5")
    args = p.parse_args()
    run_dir = Path(args.run_dir)
    items = {i["id"]: i for i in load_jsonl(Path(args.data) / "testset.jsonl")}
    answers = latest(load_jsonl(run_dir / "verified.jsonl"))
    asked = {k[2] for k in answers if k[0] != "rules"}
    groups: dict[str, list[dict]] = {}
    for (arm, level, item_id), r in answers.items():
        if item_id in asked:
            groups.setdefault(f"{arm} {level}" if arm != "rules" else "S_rules", []).append(r)
    # The items every model system answered: the 4B and 7B ran on a shared
    # sample, so cross-system comparisons are made on it and nowhere else.
    model_sets = [{r["item_id"] for r in rows} for name, rows in groups.items() if name != "S_rules"]
    common = set.intersection(*model_sets) if model_sets else set()

    def slices(ids: set) -> dict:
        out = {"C": 0, "C_PRIME": 0}
        for i in ids:
            out[items[i]["slice"]] += 1
        return out

    rng = random.Random(0)
    systems_all = {name: score_system(rows, items, rng) for name, rows in sorted(groups.items())}
    systems_common = {name: score_system([r for r in rows if r["item_id"] in common], items, rng)
                      for name, rows in sorted(groups.items())}

    passes = {name: {r["item_id"]: bool(r.get("claim_passed")) for r in rows} for name, rows in groups.items()}
    paired = {}
    for a, b in [("qwen3.5-2b R3", "qwen3.5-2b R1"), ("qwen3.5-2b R3", "qwen3.5-2b R0"),
                 ("qwen3.5-2b R3", "qwen3.5-2b R2"), ("qwen3.5-4b R3", "qwen3.5-2b R3"),
                 ("qwen3.5-2b R3", "qwen2.5-7b R1"), ("qwen3.5-4b R3", "qwen3.5-4b R0")]:
        if a in passes and b in passes:
            paired[f"{a} vs {b}"] = mcnemar(passes[a], passes[b])

    precision_rows = {name: {r["item_id"]: (r.get("claims_true", 0), r.get("claims", 0))
                             for r in rows if r.get("parsed")} for name, rows in groups.items()}
    pairs = [("qwen3.5-2b R3", "qwen3.5-2b R2"), ("qwen3.5-2b R2", "qwen3.5-2b R1"),
             ("qwen3.5-2b R1", "qwen3.5-2b R0"), ("qwen3.5-2b R3", "qwen3.5-2b R0"),
             ("qwen3.5-4b R3", "qwen3.5-4b R0"), ("qwen3.5-4b R3", "qwen3.5-2b R3"),
             ("qwen3.5-4b R0", "qwen3.5-2b R0"), ("qwen3.5-2b R1", "qwen2.5-7b R1"),
             ("qwen3.5-2b R3", "qwen2.5-7b R1"), ("qwen3.5-4b R3", "qwen2.5-7b R1")]
    precision_pairs = {f"{a} vs {b}": paired_precision(precision_rows[a], precision_rows[b], rng)
                        for a, b in pairs if a in precision_rows and b in precision_rows}
    types = {name.split()[-1]: claim_types(rows) for name, rows in groups.items() if name.startswith("qwen3.5-2b")}
    metrics = {"common": {"items": len(common), "slices": slices(common), "systems": systems_common},
               "paired_precision": precision_pairs, "claim_types": types,
               "all": {"items": len(asked), "slices": slices(asked), "systems": systems_all},
               "paired": paired}
    (run_dir / "metrics.json").write_text(json.dumps(metrics, indent=1), encoding="utf-8")
    (run_dir / "report.md").write_text(report(metrics), encoding="utf-8")
    print(report(metrics))


if __name__ == "__main__":
    main()
