"""Slice B: public Lichess games at the player's level, scrubbed (plan §11.2, §11.6).

Run (WSL venv, it needs zstandard):
    python -m praxis_train.slice_b --out data/phase6/slice_b_games.pgn

Streams the START of one monthly CC0 export from database.lichess.org and
stops as soon as it has enough games, or after --max-mb compressed megabytes,
whichever comes first. The full file is ~30 GB; this never downloads it.

Kept: rated rapid and blitz games (3 minutes or more), both players rated
1000–1600, ending normally or on time, at least 20 moves each.

Scrubbed before anything is written:
  - player names become "W" and "B" (DatasetCli corpus selects a side by them);
  - no ratings, no dates, no site links;
  - the game gets an opaque id, a hash of its Lichess URL, so it can be split
    by game (§11.5) and never traced back to the players;
  - engine %eval comments are removed, so every game is swept by our own engine
    exactly as the app would; %clk stays, for time-pressure detection.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import re
import urllib.request
from pathlib import Path

import zstandard

SOURCE = "https://database.lichess.org/standard/lichess_db_standard_rated_2024-06.pgn.zst"
HEADER = re.compile(r'^\[(\w+) "(.*)"\]$')
EVAL = re.compile(r"\[%eval [^\]]*\]")


class CountingReader(io.RawIOBase):
    """Counts compressed bytes so the stream can stop at a size limit."""

    def __init__(self, raw):
        self.raw, self.bytes = raw, 0

    def readable(self):
        return True

    def readinto(self, b):
        data = self.raw.read(len(b))
        self.bytes += len(data)
        b[: len(data)] = data
        return len(data)


def keep(h: dict[str, str], moves: str) -> bool:
    event = h.get("Event", "")
    if not event.startswith("Rated") or not ("Rapid" in event or "Blitz" in event):
        return False
    try:
        base = int(h.get("TimeControl", "0+0").split("+")[0])
        we, be = int(h.get("WhiteElo", "0")), int(h.get("BlackElo", "0"))
    except ValueError:
        return False
    if base < 180 or not (1000 <= we <= 1600 and 1000 <= be <= 1600):
        return False
    if h.get("Termination") not in ("Normal", "Time forfeit"):
        return False
    return len(re.findall(r"\b\d+\.(?!\.)", moves)) >= 20


def scrub(h: dict[str, str], moves: str) -> str:
    gid = hashlib.sha1(h.get("Site", "").encode()).hexdigest()[:12]
    kind = "Rapid" if "Rapid" in h.get("Event", "") else "Blitz"
    headers = [("Event", f"Rated {kind} game"), ("LichessId", gid), ("White", "W"), ("Black", "B"),
               ("Result", h.get("Result", "*")), ("ECO", h.get("ECO", "?")),
               ("Opening", h.get("Opening", "?")), ("TimeControl", h.get("TimeControl", "-"))]
    body = re.sub(r"\{\s*\}", "", EVAL.sub("", moves))
    return "\n".join(f'[{k} "{v}"]' for k, v in headers) + "\n\n" + re.sub(r"\s+", " ", body).strip() + "\n"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=SOURCE)
    ap.add_argument("--out", default="data/phase6/slice_b_games.pgn")
    ap.add_argument("--games", type=int, default=800)
    ap.add_argument("--max-mb", type=float, default=50)
    args = ap.parse_args()

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    kept = scanned = 0
    req = urllib.request.Request(args.source, headers={"User-Agent": "praxis-chess dataset build (CC0 export)"})
    with urllib.request.urlopen(req, timeout=60) as resp, out.open("w", encoding="utf-8") as w:
        counter = CountingReader(resp)
        text = io.TextIOWrapper(zstandard.ZstdDecompressor().stream_reader(counter), encoding="utf-8", errors="replace")
        headers: dict[str, str] = {}
        moves: list[str] = []
        for line in text:
            line = line.rstrip("\n")
            m = HEADER.match(line)
            if m:
                if moves:  # a new game's headers: the previous game is complete
                    scanned += 1
                    body = " ".join(moves)
                    if keep(headers, body):
                        w.write(scrub(headers, body) + "\n")
                        kept += 1
                    headers, moves = {}, []
                    if kept >= args.games or counter.bytes > args.max_mb * 1e6:
                        break
                headers[m.group(1)] = m.group(2)
            elif line.strip():
                moves.append(line.strip())
    print(f"kept {kept} of {scanned} games scanned, {counter.bytes / 1e6:.1f} MB read -> {out}")


if __name__ == "__main__":
    main()
