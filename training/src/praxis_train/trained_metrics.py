"""Scores a trained model against the Phase 5 baselines (plan §15.2).

Run: python -m praxis_train.trained_metrics --arm praxis-0p8b-dev \\
         --c-run DIR --baseline-run training/results_private/phase5/full \\
         --ab-run DIR2 --out training/reports/phase6_dev_eval.md

Two test sets, judged by the same verifier as every baseline:
  - the player's own mistakes (C and C′, the Phase 5 test set), where the
    trained model is compared, paired, with the prompted 2B and 4B at R3;
  - held-out Lichess positions (slices A and B, test_ab.jsonl), from the same
    sources as the training data but never trained on. No prompted model was
    run there; S_rules is the reference.

The trained model learned the rules' own answers, so its agreement with the
rules is agreement with its teacher. Claim precision and chain validity (the
verifier) are the numbers that say whether it reads the evidence correctly.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

from praxis_train.baseline_metrics import (
    claim_types, interval, latest, load_jsonl, mcnemar, paired_precision, pct, score_system,
)


def systems_of(verified: list[dict], names: dict[tuple[str, str], str]) -> dict[str, list[dict]]:
    out: dict[str, list[dict]] = {}
    for (arm, level, _), r in latest(verified).items():
        if (arm, level) in names:
            out.setdefault(names[(arm, level)], []).append(r)
    return out


def restrict(rows: list[dict], ids: set) -> list[dict]:
    return [r for r in rows if r["item_id"] in ids]


def citation(rows: list[dict]) -> float | None:
    vals = [1.0 if r.get("citation_passed") else 0.0 for r in rows if r.get("parsed")]
    return round(sum(vals) / len(vals), 3) if vals else None


def table(title: str, note: str, systems: dict[str, dict]) -> list[str]:
    lines = [f"### {title}", "", note, "",
             "| System | Parsed | Claim precision | Chain valid | Citations valid | Consequence vs rules | "
             "Mechanism vs rules | Abstains (NOT_CONCRETE) | Invents a cause | vs your labels (cons / mech) | p50 latency |",
             "|---|---|---|---|---|---|---|---|---|---|---|"]
    for name, m in systems.items():
        latency = "—" if m["latency_p50_ms"] is None else f"{m['latency_p50_ms'] / 1000:.1f} s"
        human = "—" if not m["human_n"] else f"{pct(m['consequence_vs_human'])} / {pct(m['mechanism_vs_human'])} (n={m['human_n']})"
        lines.append(
            f"| {name} | {pct(m['parsed'])} | {pct(m['claim_precision'])}{interval(m['claim_precision_ci'])} "
            f"| {pct(m['chain_validity'])}{interval(m['chain_validity_ci'])} | {pct(m['citation'])} "
            f"| {pct(m['consequence_vs_rules'])} | {pct(m['mechanism_vs_rules_single_cause'])} "
            f"| {pct(m['abstention'])} | {pct(m['invented_cause_rate'])} | {human} | {latency} |")
    return lines


def score(groups: dict[str, list[dict]], items: dict, rng: random.Random) -> dict[str, dict]:
    return {name: {**score_system(rows, items, rng), "citation": citation(rows)} for name, rows in groups.items()}


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser()
    p.add_argument("--arm", required=True)
    p.add_argument("--c-run", required=True, help="run dir: the trained arm on the Phase 5 test set, verified")
    p.add_argument("--baseline-run", default="training/results_private/phase5/full")
    p.add_argument("--c-data", default="training/data/phase5/testset.jsonl")
    p.add_argument("--ab-run", help="run dir: the trained arm on test_ab.jsonl, verified")
    p.add_argument("--ab-data", default="training/data/phase6/test_ab.jsonl")
    p.add_argument("--out", required=True)
    args = p.parse_args()
    rng = random.Random(0)
    me = f"{args.arm} R3"
    metrics: dict = {}
    lines = [f"# Phase 6 — the trained {args.arm} against the prompted baselines", "",
             "Every answer judged by the app's verifier. 95% bootstrap intervals in brackets. The trained "
             "model learned the rules' answers, so its agreement with the rules is agreement with its "
             "teacher; claim precision and chain validity are what say it reads the evidence.", ""]

    # ── the player's own mistakes ────────────────────────────────────────────
    items = {i["id"]: i for i in load_jsonl(Path(args.c_data))}
    verified = load_jsonl(Path(args.baseline_run) / "verified.jsonl") + load_jsonl(Path(args.c_run) / "verified.jsonl")
    groups = systems_of(verified, {(args.arm, "R3"): me, ("qwen3.5-2b", "R3"): "qwen3.5-2b R3 (few-shot)",
                                   ("qwen3.5-4b", "R3"): "qwen3.5-4b R3 (few-shot)", ("rules", "R3"): "S_rules"})
    if me in groups:
        asked = {r["item_id"] for r in groups[me]}
        sample = asked & {r["item_id"] for r in groups.get("qwen3.5-4b R3 (few-shot)", [])}
        full = {n: restrict(rows, asked) for n, rows in groups.items() if n != "qwen3.5-4b R3 (few-shot)"}
        shared = {n: restrict(rows, sample) for n, rows in groups.items()}
        metrics["c_all"] = score(full, items, rng)
        metrics["c_shared"] = score(shared, items, rng)
        lines += ["## Your games (C and C′)", ""]
        lines += table(f"All {len(asked)} questions", "The 4B answered only the shared sample; it is in the next table.",
                       metrics["c_all"])
        lines.append("")
        lines += table(f"The shared sample ({len(sample)} questions)", "The only table with all three models.",
                       metrics["c_shared"])

        prec = {n: {r["item_id"]: (r.get("claims_true", 0), r.get("claims", 0)) for r in rows if r.get("parsed")}
                for n, rows in groups.items()}
        passes = {n: {r["item_id"]: bool(r.get("claim_passed")) for r in rows} for n, rows in groups.items()}
        metrics["paired_precision"] = {f"{me} vs {b}": paired_precision(prec[me], prec[b], rng)
                                       for b in ["qwen3.5-2b R3 (few-shot)", "qwen3.5-4b R3 (few-shot)"] if b in prec}
        metrics["paired_valid"] = {f"{me} vs {b}": mcnemar(passes[me], passes[b])
                                   for b in ["qwen3.5-2b R3 (few-shot)", "qwen3.5-4b R3 (few-shot)"] if b in passes}
        lines += ["", "### Paired: claim precision, trained minus prompted", "",
                  "| A vs B | Questions | Difference | 95% interval | Real? |", "|---|---|---|---|---|"]
        for name, t in metrics["paired_precision"].items():
            lines.append(f"| {name} | {t['items']} | {100 * t['diff']:+.1f} pts | "
                         f"{100 * t['ci'][0]:+.1f} to {100 * t['ci'][1]:+.1f} | {'yes' if t['excludes_zero'] else 'no'} |")
        lines += ["", "### Paired: whole answer valid (McNemar exact)", "",
                  "| A vs B | Questions | A only passes | B only passes | p |", "|---|---|---|---|---|"]
        for name, t in metrics["paired_valid"].items():
            lines.append(f"| {name} | {t['items']} | {t['a_only']} | {t['b_only']} | {t['p']} |")
        ct = claim_types(groups[me])
        metrics["claim_types"] = ct
        lines += ["", f"### Which claims {args.arm} gets right (your games)", "",
                  "| Claim type | Made | True |", "|---|---|---|"]
        for t, (made, true) in sorted(ct.items(), key=lambda kv: -kv[1][0]):
            lines.append(f"| {t} | {made} | {100 * true / made:.0f}% |")

    # ── held-out Lichess ─────────────────────────────────────────────────────
    if args.ab_run:
        ab_items = {i["id"]: i for i in load_jsonl(Path(args.ab_data))}
        ab = systems_of(load_jsonl(Path(args.ab_run) / "verified.jsonl"), {(args.arm, "R3"): me, ("rules", "R3"): "S_rules"})
        if me in ab:
            asked = {r["item_id"] for r in ab[me]}
            metrics["ab"] = {}
            lines += ["", "## Held-out Lichess positions (never trained on)", ""]
            for label, keep in [("All", None), ("Slice A — puzzles", "A"), ("Slice B — rated games", "B")]:
                ids = {i for i in asked if keep is None or ab_items[i]["slice"] == keep}
                if not ids:
                    continue
                m = score({n: restrict(rows, ids) for n, rows in ab.items()}, ab_items, rng)
                metrics["ab"][keep or "all"] = m
                lines += table(f"{label} ({len(ids)} questions)", "", m) + [""]

    Path(args.out).write_text("\n".join(lines) + "\n", encoding="utf-8")
    Path(args.out).with_suffix(".json").write_text(json.dumps(metrics, indent=1), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
