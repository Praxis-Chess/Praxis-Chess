"""Fingerprints of everything the pre-registration commits to (PREREGISTRATION.md §5).

    python training/tools/prereg_hashes.py            # print the table
    python training/tools/prereg_hashes.py --check    # compare with the registered values

The data files are gitignored (they hold engine output and, for the test set,
the player's own games), so the tag cannot contain them. It contains their
SHA-256 instead: a later run proves it used the registered data by matching
these, and --check fails loudly if anything moved.
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FILES = [
    # The training data (v1) and its manifest, which hashes every per-R file.
    "training/data/phase6_v1/manifest.json",
    "training/data/phase6_v1/R0/train.jsonl",
    "training/data/phase6_v1/R1/train.jsonl",
    "training/data/phase6_v1/R2/train.jsonl",
    "training/data/phase6_v1/R3/train.jsonl",
    "training/data/phase6_v1/R3/val.jsonl",
    # The test sets.
    "training/data/phase5/testset.jsonl",
    "training/data/phase6_v1/test_ab.jsonl",
    "training/data/phase5/fewshot.jsonl",
    # The measuring instrument: the answer schema and the verifier's source.
    "training/data/phase5/schema.json",
    "backend/src/main/java/com/praxis/evidence/diagnosis/DiagnosisVerifier.java",
    "backend/src/main/java/com/praxis/evidence/diagnosis/DiagnosisRules.java",
    "backend/src/main/java/com/praxis/evidence/graph/EvidenceGraphBuilder.java",
    # The frozen prompted baselines (Phase 5), reused rather than re-run.
    "training/results_private/phase5/full/verified.jsonl",
]
REGISTERED = ROOT / "training" / "PREREGISTRATION.md"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main() -> None:
    table = {f: sha256(ROOT / f) if (ROOT / f).exists() else "MISSING" for f in FILES}
    if "--check" not in sys.argv:
        for f, h in table.items():
            print(f"| `{f}` | `{h}` |")
        return
    registered = dict(re.findall(r"\| `([^`]+)` \| `([0-9a-f]{64}|MISSING)` \|", REGISTERED.read_text(encoding="utf-8")))
    bad = [f for f in FILES if registered.get(f) != table[f]]
    for f in bad:
        print(f"CHANGED  {f}\n  registered {registered.get(f)}\n  now        {table[f]}")
    print("all files match the registration" if not bad else f"{len(bad)} file(s) differ from the registration")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
