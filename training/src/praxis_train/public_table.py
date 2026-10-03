"""Phase 11: the headline table on the public test set -> reports/public_test_v1.{json,md}.

    python -m praxis_train.public_table                (run from training/)

T_AB is the 838 held-out Lichess positions (slices A and B), the only test set
anyone outside this laptop can run. Reads verified answers only (EvalCli verify
output), scored with the same metric code as the Phase 8 report.

Each system is scored with a fresh Random(0), so its intervals do not depend on
which other systems were scored before it: a reproduction that runs one adapter
gets the same interval as this table. (grid_v1.md shares one generator across
T_C and T_AB, so its T_AB intervals differ from these in the last digit; the
point values are identical.)
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

from praxis_train.baseline_metrics import load_jsonl, pct, score_system
from praxis_train.grid_report import SEED, systems

ORDER = ["S_rules", "lora-2b-r0", "lora-2b-r1", "lora-2b-r2", "lora-2b-r3", "lora-4b-r0", "lora-4b-r3"]
LABELS = {"S_rules": "Rules (no model)", "lora-2b-r0": "2B LoRA, R0 (board only)",
          "lora-2b-r1": "2B LoRA, R1 (+ engine facts)", "lora-2b-r2": "2B LoRA, R2 (+ tactics)",
          "lora-2b-r3": "2B LoRA, R3 (full evidence graph)", "lora-4b-r0": "4B LoRA, R0 (board only)",
          "lora-4b-r3": "4B LoRA, R3 (full evidence graph)"}


def table(verified: Path, testset: Path) -> dict:
    items = {i["id"]: i for i in load_jsonl(testset)}
    found = systems(load_jsonl(verified))
    out = {}
    for name in [n for n in ORDER if n in found]:
        rows = list(found[name].values())
        s = score_system(rows, items, random.Random(SEED))
        s["by_slice"] = {}
        for sl in sorted({items[r["item_id"]].get("slice", "?") for r in rows}):
            part = [r for r in rows if items[r["item_id"]].get("slice", "?") == sl]
            p = score_system(part, items, random.Random(SEED))
            s["by_slice"][sl] = {k: p[k] for k in ("items", "claim_precision", "chain_validity")}
        out[name] = s
    return out


def pct1(x) -> str:
    return "—" if x is None else f"{100 * x:.1f}%"


def interval1(x) -> str:
    return "" if not x else f" ({100 * x[0]:.1f}–{100 * x[1]:.1f})"


def markdown(t: dict) -> list[str]:
    L = ["| System | Items | Parsed | Claim precision (95% CI) | Chain valid (95% CI) | Mechanism vs rules | "
         "Invents a cause | Median latency |", "|---|---|---|---|---|---|---|---|"]
    for name, s in t.items():
        lat = "—" if s["latency_p50_ms"] is None else f"{s['latency_p50_ms'] / 1000:.1f} s"
        L.append(f"| {LABELS[name]} | {s['items']} | {pct(s['parsed'])} | "
                 f"{pct1(s['claim_precision'])}{interval1(s['claim_precision_ci'])} | "
                 f"{pct1(s['chain_validity'])}{interval1(s['chain_validity_ci'])} | "
                 f"{pct(s['mechanism_vs_rules_single_cause'])} | {pct(s['invented_cause_rate'])} | {lat} |")
    return L


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verified", type=Path, default=Path("results_private/phase8/grid_ab/verified.jsonl"))
    ap.add_argument("--testset", type=Path, default=Path("data/phase6_v1/test_ab_ablations.jsonl"))
    ap.add_argument("--out", type=Path, default=Path("reports/public_test_v1"))
    args = ap.parse_args()
    t = table(args.verified, args.testset)
    args.out.with_suffix(".json").write_text(json.dumps(t, indent=1), encoding="utf-8")
    md = ["# Public test set (T_AB, 838 held-out Lichess positions)", "",
          "Every answer checked claim by claim by the Java verifier. Latency is on a 4 GB RTX 3050 laptop GPU "
          "(q4_K_M, Ollama), so read it as relative.", "", *markdown(t), ""]
    args.out.with_suffix(".md").write_text("\n".join(md), encoding="utf-8")
    print("\n".join(md))


if __name__ == "__main__":
    main()
