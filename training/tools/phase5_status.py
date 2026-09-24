"""How far the Phase 5 baseline run has got, and roughly when it will finish.

Run from the repository root:
    python training/tools/phase5_status.py [RUN_DIR]

Reads the progress file the runner rewrites after every answer; it never
touches the run itself, so it is safe to call as often as you like.
"""

from __future__ import annotations

import json
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

DEFAULT = Path("training/results_private/phase5/full")


def hms(seconds: float) -> str:
    seconds = int(seconds)
    h, rest = divmod(seconds, 3600)
    m = rest // 60
    return f"{h} h {m:02d} m" if h else f"{m} m"


def main() -> None:
    run_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT
    f = run_dir / "progress.json"
    if not f.exists():
        print(f"No run found at {run_dir} (no progress.json yet).")
        return
    p = json.loads(f.read_text(encoding="utf-8"))
    total, done = p["total"], p["done"]
    pct = 100 * done / total if total else 0

    print("Phase 5 baseline run")
    print(f"  done       {done:,} / {total:,}  ({pct:.0f}%)")
    # Plain separators: cmd.exe and Git Bash both mangle "·".
    print(f"  now        {p['current'].replace(' · ', ' | ')}")
    for arm, ms in p.get("avg_ms_last50", {}).items():
        print(f"  speed      {arm}: {ms / 1000:.1f} s per answer (last 50)")
    if p["current"] == "finished":
        print("  remaining  none: the run has finished")
    elif p.get("seconds_left") is not None:
        finish = datetime.now() + timedelta(seconds=p["seconds_left"])
        rough = " (rough: some models not measured yet)" if p.get("eta_rough") else ""
        print(f"  remaining  ~{hms(p['seconds_left'])}  -> finishes around {finish:%a %d %b, %H:%M}{rough}")
    else:
        print("  remaining  measuring speed...")
    print(f"  failures   {p['failures']} (retried at the end)")

    # A stalled run looks exactly like a running one in the numbers above.
    updated = time.mktime(time.strptime(p["updated"], "%Y-%m-%d %H:%M:%S"))
    idle = time.time() - updated
    if p["current"] != "finished" and idle > 600:
        print(f"  WARNING    no progress for {hms(idle)}: is Ollama running, or did the laptop sleep?")


if __name__ == "__main__":
    main()
