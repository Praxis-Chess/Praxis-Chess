"""How far the Phase 5 baseline run has got, and roughly when it will finish.

Run from the repository root:
    python training/tools/phase5_status.py [RUN_DIR]

Reads the progress file the runner rewrites after every answer, and the answers
themselves for timing; it never touches the run, so it is safe to call as often
as you like.

The time left is estimated here, from the MEDIAN of each model's recent answers.
A mean was used first, and a handful of very slow answers (the laptop busy with
something else) doubled it: 29 s per answer shown, 15.5 s typical.
"""

from __future__ import annotations

import json
import statistics
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

DEFAULT = Path("training/results_private/phase5/full")

# Seconds per answer measured before the run, for models it has not reached yet.
MEASURED_S = {"qwen3.5-2b": 15.5, "qwen3.5-4b": 37.0, "qwen2.5-7b": 51.0}


def recent_medians(run_dir: Path) -> dict[str, float]:
    by_arm: dict[str, list[float]] = {}
    f = run_dir / "outputs.jsonl"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.strip():
                o = json.loads(line)
                if not o.get("error") and o.get("latency_ms"):
                    by_arm.setdefault(o["arm"], []).append(o["latency_ms"] / 1000)
    return {arm: statistics.median(v[-50:]) for arm, v in by_arm.items()}


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
    medians = recent_medians(run_dir)
    for arm, sec in medians.items():
        print(f"  speed      {arm}: {sec:.1f} s per answer (median of last 50)")
    if p["current"] == "finished":
        print("  remaining  none: the run has finished")
    else:
        left = p.get("left_per_arm", {})
        seconds = sum(n * medians.get(arm, MEASURED_S.get(arm, 30.0)) for arm, n in left.items())
        finish = datetime.now() + timedelta(seconds=seconds)
        pending = [arm for arm in left if arm not in medians]
        note = f" (using pre-run timing for {', '.join(pending)})" if pending else ""
        print(f"  remaining  ~{hms(seconds)}  -> finishes around {finish:%a %d %b, %H:%M}{note}")
    print(f"  failures   {p['failures']} (retried at the end)")

    # A stalled run looks exactly like a running one in the numbers above.
    updated = time.mktime(time.strptime(p["updated"], "%Y-%m-%d %H:%M:%S"))
    idle = time.time() - updated
    if p["current"] != "finished" and idle > 600:
        print(f"  WARNING    no progress for {hms(idle)}: is Ollama running, or did the laptop sleep?")


if __name__ == "__main__":
    main()
