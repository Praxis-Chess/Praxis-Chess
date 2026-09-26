"""Phase 7 progress, on the rented GPU:   python training/tools/grid_status.py

Finished arms from results/grid_v1/grid_log.tsv, the arm in progress from its
run.log (the trainer's step counter), and a rough time left from the arms done.
"""

from __future__ import annotations

import re
from pathlib import Path

RESULTS = Path("/workspace/results/grid_v1")
ORDER = (Path(__file__).resolve().parents[1] / "config" / "grid_v1" / "ORDER").read_text().split()


def main() -> None:
    rows = []
    log = RESULTS / "grid_log.tsv"
    if log.exists():
        lines = log.read_text().splitlines()[1:]
        rows = [dict(zip(["arm", "started", "finished", "seconds", "rupees", "gpu", "git", "status"], l.split("\t")))
                for l in lines if l.strip()]
    done = {r["arm"]: r for r in rows if r["status"] == "ok"}
    print(f"{len(done)} of {len(ORDER)} arms done")
    for r in rows:
        print(f"  {r['arm']:14s} {r['status']:7s} {int(r['seconds']) // 60:4d} min  Rs {r['rupees']}")
    current = next((a for a in ORDER if a not in done and (RESULTS / a / "run.log").exists()), None)
    if current:
        text = (RESULTS / current / "run.log").read_text(errors="replace")
        steps = re.findall(r"(\d+)/(\d+) \[", text)
        stage = ("exporting" if "convert" in text[-4000:].lower() or "quantiz" in text[-4000:].lower()
                 else "choosing the epoch" if "epoch-" in text[-2000:] and "claim_precision" not in text[-200:]
                 else "training")
        where = f"step {steps[-1][0]}/{steps[-1][1]}" if steps else "starting"
        print(f"now: {current}, {stage}, {where}")
    per_arm = [int(r["seconds"]) for r in done.values()]
    if per_arm:
        left = [a for a in ORDER if a not in done]
        # The 4B arms take about twice a 2B arm.
        weight = sum(2 if a.startswith("4b") else 1 for a in left)
        avg = sum(per_arm) / len(per_arm)
        print(f"rough time left: {weight * avg / 3600:.1f} h ({len(left)} arms), "
              f"spent so far Rs {sum(float(r['rupees']) for r in rows):.0f}")


if __name__ == "__main__":
    main()
