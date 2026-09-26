"""Phase 7: R3 with one evidence component removed, for the H5 ablations.

    python -m praxis_train.ablate train       # data/phase7/ablations/<name>/R3/{train,val}.jsonl
    python -m praxis_train.ablate testsets    # the test sets with the ablated renders added

PREREGISTRATION.md §3: each ablation retrains 2B-R3 with one component removed
from every render, in training AND in test:
    −CF  the [CF1] line        (the counterfactual)
    −T   the [T1] line         (the null-move threat probe)
    −Δ   every [Δ…] line       (what the move changed)
    −D   the [D1] line         (the depth curve)

Only the evidence lines go. The target is the same one every R level trains on
(§11.1), citations included: the ablated model has to learn to do without the
line, not be told a different answer.

The test sets gain render levels "R3-CF", "R3-T", "R3-DELTA", "R3-D" beside
R0–R3, so the Phase 5 harness asks them like any other level. The player's test
set (T_C) is only ever read and written here, on this machine.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

COMPONENTS = {
    "CF": re.compile(r"^\[CF\d+\]"),
    "T": re.compile(r"^\[T\d+\]"),
    "DELTA": re.compile(r"^\[Δ\d+\]"),
    "D": re.compile(r"^\[D\d+\]"),
}
# Folder names, one per ablation arm (config/grid_v1/2b-r3-no<name>.yaml).
FOLDERS = {"CF": "nocf", "T": "not", "DELTA": "nodelta", "D": "nod"}
CHARS_PER_TOKEN = 2.3   # the renderer's own estimate (EvidenceGraph budget)


def strip(text: str, component: str) -> str:
    pattern = COMPONENTS[component]
    return "\n".join(line for line in text.split("\n") if not pattern.match(line))


def load(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def write(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as w:
        for r in rows:
            w.write(json.dumps(r, ensure_ascii=False) + "\n")


def train(src: Path, out: Path) -> None:
    for comp, folder in FOLDERS.items():
        for part in ("train", "val"):
            rows, removed = [], 0
            for r in load(src / "R3" / f"{part}.jsonl"):
                user = r["messages"][1]["content"]
                stripped = strip(user, comp)
                removed += user.count("\n") - stripped.count("\n")
                messages = [dict(m) for m in r["messages"]]
                messages[1]["content"] = stripped
                rows.append({"messages": messages, "meta": dict(r["meta"], R=f"R3-{comp}")})
            write(out / folder / "R3" / f"{part}.jsonl", rows)
            print(f"{folder}/{part}: {len(rows)} rows, {removed} evidence lines removed")


def testsets(files: list[Path]) -> None:
    for path in files:
        rows = load(path)
        for r in rows:
            r3 = r["renders"]["R3"]["text"]
            for comp in COMPONENTS:
                text = strip(r3, comp)
                r["renders"][f"R3-{comp}"] = {"text": text, "tokens": round(len(text) / CHARS_PER_TOKEN)}
        target = path.with_name(path.stem + "_ablations.jsonl")
        write(target, rows)
        print(f"{target}: {len(rows)} items, levels R0–R3 + " + ", ".join(f"R3-{c}" for c in COMPONENTS))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("what", choices=["train", "testsets"])
    ap.add_argument("--src", type=Path, default=Path("data/phase6_v1"))
    ap.add_argument("--out", type=Path, default=Path("data/phase7/ablations"))
    ap.add_argument("--tests", default="data/phase5/testset.jsonl,data/phase6_v1/test_ab.jsonl")
    args = ap.parse_args()
    if args.what == "train":
        train(args.src, args.out)
    else:
        testsets([Path(p) for p in args.tests.split(",")])


if __name__ == "__main__":
    main()
