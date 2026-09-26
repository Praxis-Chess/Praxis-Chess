"""How far the Phase 6 evidence builds have got.

Run from the repository root:
    python training/tools/phase6_status.py

Reads the progress files DatasetCli rewrites after every item
(training/data/phase6/*.progress.json). Never touches the runs themselves.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timedelta
from pathlib import Path

DATA = Path("training/data/phase6")


def hms(seconds: float) -> str:
    seconds = int(seconds)
    h, rest = divmod(seconds, 3600)
    return f"{h} h {rest // 60:02d} m" if h else f"{rest // 60} m"


def main() -> None:
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else DATA
    files = sorted(folder.glob("*.progress.json"))
    if not files:
        print(f"No Phase 6 run has started yet (no progress files in {folder}).")
        return
    for f in files:
        p = json.loads(f.read_text(encoding="utf-8"))
        total, done = p["total"], p["done"]
        pct = 100 * done / total if total else 0
        print(f"{p['what']}")
        print(f"  done       {done:,} / {total:,}  ({pct:.0f}%)")
        if p["current"] == "finished":
            print("  remaining  none: finished")
        elif p.get("seconds_per_item"):
            left = (total - done) * p["seconds_per_item"]
            finish = datetime.now() + timedelta(seconds=left)
            print(f"  speed      {p['seconds_per_item']:.1f} s per item")
            print(f"  remaining  ~{hms(left)}  -> finishes around {finish:%a %d %b, %H:%M}")
        else:
            print("  remaining  measuring speed...")
        print(f"  failed     {p['failed']}")
        updated = datetime.fromisoformat(p["updated"])
        idle = (datetime.now() - updated).total_seconds()
        if p["current"] != "finished" and idle > 600:
            print(f"  WARNING    no progress for {hms(idle)}: did the laptop sleep, or did the run stop?")
        print()


if __name__ == "__main__":
    main()
