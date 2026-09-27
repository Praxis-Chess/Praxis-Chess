"""Phase 8: the hand labels made after the pre-registration tag.

    python -m praxis_train.fresh_labels [--backend http://localhost:8086]

PREREGISTRATION.md §9 (b): the ship decision compares the model with the rules
on FRESH labels only, those whose labelled_at is later than the prereg-v1 tag,
because the first hundred tuned the rules. Reads the backend's training export
(it needs praxis-chess.training.enabled=true, as in Phase 5) and writes
data/phase8/fresh_labels.jsonl. The labels are the player's own and stay on
this machine (results_private rules apply).
"""

from __future__ import annotations

import argparse
import json
import subprocess
import urllib.request
from datetime import datetime
from pathlib import Path


def tag_time(tag: str) -> datetime:
    out = subprocess.run(["git", "log", "-1", "--format=%cI", tag], capture_output=True, text=True, check=True)
    return datetime.fromisoformat(out.stdout.strip())


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://localhost:8086")
    ap.add_argument("--tag", default="prereg-v1")
    ap.add_argument("--testset", default="data/phase5/testset.jsonl")
    ap.add_argument("--out", type=Path, default=Path("data/phase8/fresh_labels.jsonl"))
    args = ap.parse_args()
    since = tag_time(args.tag)
    with urllib.request.urlopen(f"{args.backend}/api/admin/training/export", timeout=120) as r:
        rows = [json.loads(l) for l in r.read().decode("utf-8").splitlines() if l.strip()]
    if rows and "labelled_at" not in rows[0]:
        raise SystemExit("the backend's export has no labelled_at: restart the backend on the current code")
    test_ids = {json.loads(l)["id"] for l in open(args.testset, encoding="utf-8") if l.strip()}
    fresh = []
    for r in rows:
        if not r.get("human_mechanism") or not r.get("labelled_at"):
            continue
        if datetime.fromisoformat(r["labelled_at"].replace("Z", "+00:00")) > since:
            fresh.append({"id": r["id"], "consequence": r["human_consequence"], "mechanism": r["human_mechanism"],
                          "labelled_at": r["labelled_at"], "in_test_set": r["id"] in test_ids})
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as w:
        for f in fresh:
            w.write(json.dumps(f) + "\n")
    inside = sum(f["in_test_set"] for f in fresh)
    print(f"{len(fresh)} labels made after {args.tag} ({since.isoformat()}); {inside} are T_C items -> {args.out}")
    if len(fresh) > inside:
        print(f"  {len(fresh) - inside} are outside T_C: the models never answered them, so they cannot count")


if __name__ == "__main__":
    main()
