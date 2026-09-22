"""Lichess puzzle CSV (CC0) -> a balanced sample.

The full export is ~6.1M rows and several hundred MB compressed. Phase 1 needs
200 puzzles, so this streams a prefix of the zstd stream and stops, rather than
downloading the whole file to throw 99.99% of it away.

The rows are not shuffled by theme, so a naive head -200 would be badly skewed.
`balanced_sample` fills per-mechanism quotas from the streamed prefix instead.

Licence: the CSV is CC0. `lichess-puzzler` (AGPL) is the tool that produced the
theme tags; its code is never vendored here, only the CC0 data it emitted.
"""

from __future__ import annotations

import csv
import io
import urllib.request
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

import zstandard as zstd

PUZZLE_URL = "https://database.lichess.org/lichess_db_puzzle.csv.zst"

#: Themes that name a *mechanism* we want the model to be able to cite. Lichess
#: also tags phase, length and difficulty; those are not mechanisms and are
#: deliberately excluded from the balancing key.
MECHANISMS = (
    "fork",
    "pin",
    "skewer",
    "hangingPiece",
    "discoveredAttack",
    "backRankMate",
    "deflection",
    "trappedPiece",
)


@dataclass(frozen=True)
class Puzzle:
    """One CC0 puzzle row, trimmed to what the stub graph needs."""

    puzzle_id: str
    fen: str
    """Position *before* the blunder. The first UCI move is the blunder itself."""
    moves: tuple[str, ...]
    rating: int
    themes: tuple[str, ...]

    @property
    def blunder_uci(self) -> str:
        return self.moves[0]

    @property
    def refutation_uci(self) -> str:
        return self.moves[1]

    def mechanisms(self) -> tuple[str, ...]:
        return tuple(t for t in self.themes if t in MECHANISMS)


def download_prefix(dest: Path, megabytes: int = 24) -> Path:
    """Fetch the first `megabytes` of the compressed CSV via an HTTP range request.

    A zstd stream decompresses happily from the front; the truncated tail raises,
    which `stream_rows` swallows after it has all the whole rows it needs.
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size >= megabytes * 1024 * 1024:
        return dest
    req = urllib.request.Request(
        PUZZLE_URL, headers={"Range": f"bytes=0-{megabytes * 1024 * 1024 - 1}"}
    )
    with urllib.request.urlopen(req, timeout=300) as r, dest.open("wb") as out:
        out.write(r.read())
    return dest


def stream_rows(path: Path, limit: int = 400_000) -> Iterator[Puzzle]:
    """Decompress and parse rows until the prefix runs out or `limit` is reached."""
    dctx = zstd.ZstdDecompressor()
    with path.open("rb") as fh, dctx.stream_reader(fh) as reader:
        text = io.TextIOWrapper(reader, encoding="utf-8", errors="replace")
        try:
            for i, row in enumerate(csv.DictReader(text)):
                if i >= limit:
                    return
                try:
                    yield Puzzle(
                        puzzle_id=row["PuzzleId"],
                        fen=row["FEN"],
                        moves=tuple(row["Moves"].split()),
                        rating=int(row["Rating"]),
                        themes=tuple(row["Themes"].split()),
                    )
                except (KeyError, ValueError):
                    continue
        except (zstd.ZstdError, EOFError):
            # Expected: the prefix ends mid-frame. Every whole row already yielded.
            return


def balanced_sample(
    rows: Iterable[Puzzle],
    total: int = 200,
    rating_range: tuple[int, int] = (800, 1800),
) -> list[Puzzle]:
    """Fill an equal quota per mechanism, first come first served.

    Rating is capped because a 2400-rated puzzle is a ten-move combination whose
    explanation is nothing like the one-move blunders Praxis flags in a 1000-rated
    game. The point of the sample is the *shape* of the task, not its difficulty.
    """
    per = max(1, total // len(MECHANISMS))
    picked: dict[str, list[Puzzle]] = {m: [] for m in MECHANISMS}
    seen: set[str] = set()

    for p in rows:
        if not (rating_range[0] <= p.rating <= rating_range[1]):
            continue
        if len(p.moves) < 2 or p.puzzle_id in seen:
            continue
        mechs = p.mechanisms()
        if len(mechs) != 1:
            # Multi-mechanism puzzles are composite cases; Phase 3 handles chains.
            # A single clean mechanism keeps the stub target honest.
            continue
        m = mechs[0]
        if len(picked[m]) >= per:
            continue
        picked[m].append(p)
        seen.add(p.puzzle_id)
        if sum(len(v) for v in picked.values()) >= per * len(MECHANISMS):
            break

    out = [p for m in MECHANISMS for p in picked[m]]
    return out[:total]


def describe(sample: list[Puzzle]) -> str:
    counts = Counter(p.mechanisms()[0] for p in sample)
    ratings = sorted(p.rating for p in sample)
    mid = ratings[len(ratings) // 2] if ratings else 0
    return (
        f"{len(sample)} puzzles, median rating {mid}, "
        + ", ".join(f"{k}={v}" for k, v in sorted(counts.items()))
    )
