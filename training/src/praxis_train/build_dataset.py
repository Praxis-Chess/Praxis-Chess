"""Puzzles -> stub graphs -> prompt/target pairs -> JSONL, with a manifest.

Run: python -m praxis_train.build_dataset --out data/phase1

Every example carries its source id so a split never puts two views of the same
position on both sides of it, and so any row can be traced back to the CC0 row
it came from.
"""

from __future__ import annotations

import argparse
import json
import random
from dataclasses import asdict
from pathlib import Path

import chess

from . import lichess_puzzles as lp
from . import render
from . import stub_graph


def build_rows(puzzles: list[lp.Puzzle]) -> tuple[list[dict], list[str]]:
    """Returns (rows, rejections). A puzzle whose line does not play is dropped."""
    rows: list[dict] = []
    rejected: list[str] = []

    for p in puzzles:
        try:
            g = stub_graph.build(p.fen, list(p.moves), mechanism=p.mechanisms()[0])
        except (ValueError, AssertionError, IndexError, chess.IllegalMoveError) as e:
            rejected.append(f"{p.puzzle_id}: {type(e).__name__} {e}")
            continue

        # A blunder that costs nothing and mates nobody has no consequence to
        # explain. Keeping it would teach the model to assert a cost that the
        # evidence does not show.
        if not g.leads_to_mate and g.material_swing <= 0:
            rejected.append(f"{p.puzzle_id}: no measurable consequence")
            continue

        rows.append(
            {
                "messages": [
                    {"role": "user", "content": render.render_prompt(g)},
                    {"role": "assistant", "content": render.render_target(g)},
                ],
                "meta": {
                    "slice": "A",
                    "source_id": f"lichess:puzzle:{p.puzzle_id}",
                    "fen_key": " ".join(p.fen.split()[:2]),
                    "mechanism": g.mechanism,
                    "rating": p.rating,
                },
                "graph": asdict(g),
            }
        )
    return rows, rejected


def split(rows: list[dict], val_fraction: float = 0.1, seed: int = 17) -> tuple[list[dict], list[dict]]:
    """Split by position, not by row: identical placements never straddle it."""
    keys = sorted({r["meta"]["fen_key"] for r in rows})
    rng = random.Random(seed)
    rng.shuffle(keys)
    cut = max(1, int(len(keys) * val_fraction))
    val_keys = set(keys[:cut])
    train = [r for r in rows if r["meta"]["fen_key"] not in val_keys]
    val = [r for r in rows if r["meta"]["fen_key"] in val_keys]
    return train, val


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps({"messages": r["messages"], "meta": r["meta"]}) + "\n")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=Path("data/phase1"))
    ap.add_argument("--count", type=int, default=200)
    ap.add_argument("--cache", type=Path, default=Path("data/lichess_puzzle_prefix.csv.zst"))
    ap.add_argument("--prefix-mb", type=int, default=24)
    args = ap.parse_args()

    path = lp.download_prefix(args.cache, megabytes=args.prefix_mb)
    sample = lp.balanced_sample(lp.stream_rows(path), total=args.count)
    print(lp.describe(sample))

    rows, rejected = build_rows(sample)
    train, val = split(rows)
    write_jsonl(args.out / "train.jsonl", train)
    write_jsonl(args.out / "val.jsonl", val)

    targets = [r["messages"][1]["content"] for r in rows]
    manifest = {
        "puzzles_sampled": len(sample),
        "rows": len(rows),
        "rejected": len(rejected),
        "train": len(train),
        "val": len(val),
        "mechanisms": {
            m: sum(1 for r in rows if r["meta"]["mechanism"] == m) for m in lp.MECHANISMS
        },
        "target_chars_mean": round(sum(len(t) for t in targets) / max(1, len(targets))),
        # The baseline's boilerplate measure, on our own targets. 95% of the
        # shipped explanations open with the same five words; a target set that
        # did the same would teach the model the habit we are trying to remove.
        "stock_opening_rate": round(render.stock_opening_rate(targets), 3),
        "distinct_targets": len(set(targets)),
    }
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    (args.out / "rejected.txt").write_text("\n".join(rejected), encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
