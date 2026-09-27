"""Phase 8: the bf16 half of the quantisation delta (PREREGISTRATION.md §3, §6).

Run ON THE RENTED GPU after the grid (a few minutes), with test_ab.jsonl uploaded:
    python -m praxis_train.quant_delta --adapter /workspace/results/grid_v1/2b-r3/adapter \\
        --testset data/phase6_v1/test_ab.jsonl --out /workspace/results/grid_v1/quant_delta_bf16.jsonl

The delta asks one question: does the number a Hub card would report (the
adapter in bf16, through Transformers) match the number users get (the q4_K_M
GGUF through Ollama)? Both halves answer the same 300 public items (the fixed
Phase 5 shuffle of T_AB), in the training format, greedy, and WITHOUT the JSON
schema, because Transformers' generate cannot apply it. The GGUF half is the
baselines arm "gguf-2b-r3-free" on the laptop. The player's games never go to
the pod, which is why this uses T_AB. Descriptive only: never a verdict.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from praxis_train.baselines import ordered_items
from praxis_train.build_dataset6 import SYSTEM
from praxis_train.select_checkpoint import generate


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--adapter", type=Path, required=True)
    ap.add_argument("--base", default="Qwen/Qwen3.5-2B")
    ap.add_argument("--revision", default="main")
    ap.add_argument("--testset", type=Path, default=Path("data/phase6_v1/test_ab.jsonl"))
    ap.add_argument("--n", type=int, default=300)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    items = ordered_items([json.loads(l) for l in args.testset.read_text(encoding="utf-8").splitlines() if l.strip()])
    items = items[: args.n]
    rows = [{"messages": [{"role": "system", "content": SYSTEM},
                          {"role": "user", "content": it["renders"]["R3"]["text"]},
                          {"role": "assistant", "content": ""}]} for it in items]
    answers = generate(args.base, args.revision, args.adapter, rows, batch=16, max_new=1000)
    with args.out.open("w", encoding="utf-8") as w:
        for it, (text, ms) in zip(items, answers):
            w.write(json.dumps({"item_id": it["id"], "arm": "bf16-2b-r3-free", "level": "R3",
                                "raw": text, "latency_ms": ms}, ensure_ascii=False) + "\n")
    print(f"{len(answers)} bf16 answers -> {args.out}")


if __name__ == "__main__":
    main()
