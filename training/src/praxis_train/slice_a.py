"""Slice A: pick the puzzles whose blunders become training examples (plan §11.2).

Run (WSL venv, it needs zstandard):
    python -m praxis_train.slice_a --out data/phase6/slice_a_candidates.tsv

A Lichess puzzle starts one move before the tactic: its FEN is the position
where a player blundered, and the first move in `Moves` is that blunder. So
each puzzle is exactly the thing Praxis explains, a mistake, with the
punishment already known to work.

The file is not shuffled by theme, so taking the first N rows would be badly
skewed. Candidates are drawn per theme group with a seeded reservoir over the
whole prefix. Themes are only a guide: the rules label every graph afterwards,
and the dataset builder balances on the rules' mechanism, not on Lichess's tags.

Nothing here runs the engine. The TSV feeds `DatasetCli graphs`.
"""

from __future__ import annotations

import argparse
import csv
import io
import random
from pathlib import Path

import zstandard

# First matching group wins, in this order. Quotas are candidates, not final
# examples: ~5,000 candidates is ~40 minutes of evidence building.
GROUPS = [
    ("mate", {"mate", "mateIn1", "mateIn2", "mateIn3", "backRankMate", "smotheredMate"}, 700),
    ("fork", {"fork"}, 800),
    ("pin", {"pin"}, 700),
    ("skewer", {"skewer"}, 500),
    ("discovered", {"discoveredAttack", "discoveredCheck"}, 600),
    ("hanging", {"hangingPiece", "trappedPiece"}, 800),
    ("other", set(), 900),
]

# Club-level, well-established puzzles only: the player is ~1280, and a puzzle
# with few plays or a wide rating deviation has an uncertain solution.
MIN_RATING, MAX_RATING = 600, 2200
MIN_PLAYS, MIN_POPULARITY, MAX_DEVIATION = 200, 60, 100


def group_of(themes: set[str]) -> str:
    for name, tags, _ in GROUPS:
        if not tags or themes & tags:
            return name
    return "other"


def ply_of(fen: str) -> int:
    """Our convention: ply index, odd for White (§ EvidenceGraph header)."""
    parts = fen.split()
    full = int(parts[5]) if len(parts) > 5 else 1
    return 2 * full - 1 if parts[1] == "w" else 2 * full


def phase_of(themes: set[str]) -> str:
    for tag, phase in (("opening", "OPENING"), ("endgame", "ENDGAME"), ("middlegame", "MIDDLEGAME")):
        if tag in themes:
            return phase
    return "MIDDLEGAME"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--puzzles", default="data/lichess_puzzle_prefix.csv.zst")
    ap.add_argument("--out", default="data/phase6/slice_a_candidates.tsv")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    quota = {name: q for name, _, q in GROUPS}
    seen = {name: 0 for name in quota}
    chosen: dict[str, list[list[str]]] = {name: [] for name in quota}

    with open(args.puzzles, "rb") as f:
        text = io.TextIOWrapper(zstandard.ZstdDecompressor().stream_reader(f), encoding="utf-8", errors="replace")
        reader = csv.DictReader(text)
        try:
            for row in reader:
                try:
                    rating, plays = int(row["Rating"]), int(row["NbPlays"])
                    pop, dev = int(row["Popularity"]), int(row["RatingDeviation"])
                except (TypeError, ValueError):
                    continue
                if not (MIN_RATING <= rating <= MAX_RATING) or plays < MIN_PLAYS \
                        or pop < MIN_POPULARITY or dev > MAX_DEVIATION:
                    continue
                themes = set(row["Themes"].split())
                g = group_of(themes)
                seen[g] += 1
                # Reservoir sampling: every eligible puzzle in the group has the
                # same chance, wherever it sits in the file.
                item = [row["PuzzleId"], row["FEN"], row["Moves"].split()[0], themes, rating]
                if len(chosen[g]) < quota[g]:
                    chosen[g].append(item)
                else:
                    j = rng.randrange(seen[g])
                    if j < quota[g]:
                        chosen[g][j] = item
        except (EOFError, zstandard.ZstdError):
            pass  # the prefix file ends mid-frame; everything before it is fine

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="") as w:
        w.write("id\tslice\tgroup\tfen\tmove\tply\tphase\tseverity\ttheme_group\trating\n")
        n = 0
        for g, items in chosen.items():
            for pid, fen, move, themes, rating in sorted(items, key=lambda x: x[0]):
                w.write(f"lichess:puzzle:{pid}\tA\t{pid}\t{fen}\t{move}\t{ply_of(fen)}\t{phase_of(themes)}\tBLUNDER\t{g}\t{rating}\n")
                n += 1
    print(f"{n} candidates -> {out}")
    for g in quota:
        print(f"  {g:<11} {len(chosen[g]):>4} of {seen[g]:,} eligible")


if __name__ == "__main__":
    main()
