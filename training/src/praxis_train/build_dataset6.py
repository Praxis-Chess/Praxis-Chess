"""Phase 6: verified gold rows -> per-R training data, held-out test, manifest (plan §11.4, §11.5).

Run (plain Python, no dependencies):
    python -m praxis_train.build_dataset6 --gold data/phase6/gold.jsonl --out data/phase6

Input is `DatasetCli gold` output: every row already carries its four renders,
the rules' labels and, where the rules can write one, a verified target.

  leakage   any position (placement + side to move) that is also one of the
            player's test mistakes (Phase 5, slices C and C′) or a few-shot
            example is dropped. Your games are test-only, always.
  dedupe    one row per position.
  split     by SOURCE, never by row: a puzzle id (slice A) or a game (slice B)
            lands wholly in train, validation or test, so no game leaks
            positions across the line.
  balance   each slice is capped per mechanism, so no single cause dominates:
            800 public games gave 14,410 mistakes, and uncapped slice B alone
            would be ~7,000 training rows, mostly two causes.
            NOT_CONCRETE is capped at a share of train: enough to learn to
            abstain, not so much that abstaining is the easy answer.
  targets   version 0 uses the rules' own diagnosis, prose included. Composite
            rows need a teacher (§11.3) and go to teacher_queue.jsonl instead,
            so v0 has no composite training rows; v1 adds them.
  teacher   with --teacher (v1): answers that PASSED the verifier, from the
            given run folders (outputs.jsonl + verified.jsonl; the first
            passing answer per item wins, so list the main run before its
            retry). Prose answers replace the rules' explanation on rows v0
            already has; composite answers add rows, capped per slice and
            mechanism like everything else. Every other choice is v0's, same
            seed, so v1 differs from v0 only by what the teacher contributed.

    python -m praxis_train.build_dataset6 --out data/phase6_v1 --name praxis-phase6-v1 \\
        --teacher data/phase6/teacher_qwen27b_v3_composite,data/phase6/teacher_qwen27b_v3_composite_retry,\\
                  data/phase6/teacher_qwen27b_v3_prose,data/phase6/teacher_qwen27b_v3_prose_retry

The SAME target is paired with each of R0–R3 (§11.1), so R is the only thing
that differs between the four training sets.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import subprocess
from collections import Counter
from pathlib import Path

SYSTEM = ("You are Prax. Diagnose the mistake using ONLY the evidence given. "
          "Every claim must be typed. Output JSON.")
LEVELS = ["R0", "R1", "R2", "R3"]


def load(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def split_of(group: str, val_pct: int, test_pct: int) -> str:
    h = int(hashlib.sha1(group.encode()).hexdigest(), 16) % 100
    if h < test_pct:
        return "test"
    if h < test_pct + val_pct:
        return "val"
    return "train"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_teacher(folders: str) -> tuple[dict[str, tuple[dict, str]], dict]:
    """item id -> (the diagnosis, the teacher model), passing answers only."""
    answers: dict[str, tuple[dict, str]] = {}
    runs = {}
    for folder in filter(None, (f.strip() for f in folders.split(","))):
        d = Path(folder)
        raw = {o["item_id"]: o for o in load(d / "outputs.jsonl") if "error" not in o}
        passed = {v["item_id"] for v in load(d / "verified.jsonl")
                  if v["arm"].startswith("teacher:") and v.get("claim_passed")}
        usage = json.loads((d / "usage.json").read_text(encoding="utf-8"))
        runs[d.name] = {"model": usage["model"], "task": usage["task"], "answers": len(raw), "passed": len(passed),
                        "prompt": next(iter(raw.values()), {}).get("prompt")}
        for item in sorted(passed):
            if item in raw and item not in answers:
                answers[item] = (json.loads(raw[item]["raw"]), usage["model"])
    return answers, runs


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="data/phase6/gold.jsonl")
    ap.add_argument("--out", default="data/phase6")
    ap.add_argument("--exclude", default="data/phase5/testset.jsonl,data/phase5/fewshot.jsonl",
                    help="files whose fen_key positions must never appear in training")
    ap.add_argument("--val-pct", type=int, default=5)
    ap.add_argument("--test-pct", type=int, default=5)
    ap.add_argument("--cap-per-mechanism", type=int, default=600,
                    help="max train rows per rules mechanism, per slice")
    ap.add_argument("--not-concrete-share", type=float, default=0.25)
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--teacher", default="", help="comma-separated teacher run folders (v1)")
    ap.add_argument("--name", default="praxis-phase6-v0")
    args = ap.parse_args()

    out = Path(args.out)
    rng = random.Random(args.seed)
    rows = load(Path(args.gold))
    report = Counter()

    # ── leakage: the player's positions and the few-shot examples ──
    banned = set()
    for f in args.exclude.split(","):
        banned |= {r["fen_key"] for r in load(Path(f.strip()))}

    kept, teacher_queue, seen = [], [], set()
    for r in sorted(rows, key=lambda r: r["id"]):
        if r["fen_key"] in banned:
            report["dropped: position in the player's test set or few-shot"] += 1
            continue
        if r["fen_key"] in seen:
            report["dropped: duplicate position"] += 1
            continue
        seen.add(r["fen_key"])
        if r.get("needs_teacher"):
            teacher_queue.append(r)
            continue
        if not r.get("gold_verified"):
            report["dropped: rules target failed the verifier"] += 1
            continue
        r["split"] = split_of(r["group"], args.val_pct, args.test_pct)
        kept.append(r)

    # ── test: held-out A+B, in the Phase 5 test-set format, composite included ──
    test = [r for r in kept if r["split"] == "test"]
    test += [dict(r, split="test") for r in teacher_queue
             if split_of(r["group"], args.val_pct, args.test_pct) == "test"]
    teacher_queue = [r for r in teacher_queue if split_of(r["group"], args.val_pct, args.test_pct) != "test"]

    # ── balance train (validation mirrors train's rules, uncapped) ──
    train = [r for r in kept if r["split"] == "train"]
    val = [r for r in kept if r["split"] == "val"]
    rng.shuffle(train)
    per_mech = Counter()
    balanced = []
    for r in train:
        key = (r["slice"], r["rules"]["mechanism"])
        # NONE (abstention) is balanced by its own share cap below, not here.
        if key[1] != "NONE" and per_mech[key] >= args.cap_per_mechanism:
            report[f"capped: slice {key[0]} mechanism quota"] += 1
            continue
        per_mech[key] += 1
        balanced.append(r)
    concrete = [r for r in balanced if r["rules"]["consequence"] != "NOT_CONCRETE"]
    abstain = [r for r in balanced if r["rules"]["consequence"] == "NOT_CONCRETE"]
    limit = int(args.not_concrete_share / (1 - args.not_concrete_share) * len(concrete))
    if len(abstain) > limit:
        report["capped: NOT_CONCRETE share"] += len(abstain) - limit
        abstain = abstain[:limit]
    train = concrete + abstain
    rng.shuffle(train)

    # ── v1: the teacher's verified answers ──
    teacher, runs = load_teacher(args.teacher) if args.teacher else ({}, {})
    if teacher:
        def taught(r):
            diagnosis, model = teacher[r["id"]]
            return dict(r, gold=diagnosis, teacher=model)
        train = [taught(r) if r["id"] in teacher else r for r in train]
        val = [taught(r) if r["id"] in teacher else r for r in val]
        report["teacher: prose replaced (train+val)"] = sum(1 for r in train + val if r.get("teacher"))
        # Composite rows: capped per slice and (teacher-named) mechanism, like
        # every other row, so "UNCLEAR" does not become the easy answer.
        comp_mech = Counter()
        waiting = []
        for r in teacher_queue:
            if r["id"] not in teacher:
                waiting.append(r)
                continue
            r = dict(taught(r), split=split_of(r["group"], args.val_pct, args.test_pct))
            key = (r["slice"], r["gold"]["mechanism"])
            if r["split"] == "train" and comp_mech[key] >= args.cap_per_mechanism:
                report[f"capped: composite slice {key[0]} {key[1]}"] += 1
                continue
            if r["split"] == "train":
                comp_mech[key] += 1
                train.append(r)
            else:
                val.append(r)
        report["teacher: composite rows added (train+val)"] = sum(1 for r in train + val if r["rules"]["composite"])
        teacher_queue = waiting
        rng.shuffle(train)

    # ── write per-R chat JSONL ──
    files = []
    for level in LEVELS:
        d = out / level
        d.mkdir(parents=True, exist_ok=True)
        for name, part in (("train", train), ("val", val)):
            p = d / f"{name}.jsonl"
            with p.open("w", encoding="utf-8") as w:
                for r in part:
                    w.write(json.dumps({
                        "messages": [
                            {"role": "system", "content": SYSTEM},
                            {"role": "user", "content": r["renders"][level]["text"]},
                            {"role": "assistant", "content": json.dumps(r["gold"], ensure_ascii=False, separators=(",", ":"))},
                        ],
                        "meta": {"slice": r["slice"], "source_id": r["id"], "fen_key": r["fen_key"], "R": level,
                                 "rules_fired": r["rules"]["fired"], "composite": r["rules"]["composite"],
                                 "consequence": r["rules"]["consequence"], "mechanism": r["rules"]["mechanism"],
                                 "target_mechanism": r["gold"]["mechanism"],
                                 "tokens_in": r["renders"][level]["tokens"], "teacher": r.get("teacher"),
                                 "verified": True},
                    }, ensure_ascii=False) + "\n")
            files.append(p)

    test_path = out / "test_ab.jsonl"
    with test_path.open("w", encoding="utf-8") as w:
        for r in test:
            w.write(json.dumps({k: r[k] for k in ("id", "slice", "severity", "fen_key", "rules", "renders", "graph")},
                               ensure_ascii=False) + "\n")
    files.append(test_path)
    queue_path = out / "teacher_queue.jsonl"
    with queue_path.open("w", encoding="utf-8") as w:
        for r in teacher_queue:
            w.write(json.dumps(r, ensure_ascii=False) + "\n")
    files.append(queue_path)

    # ── manifest ──
    def counts(part):
        return {
            "rows": len(part),
            "slice": dict(Counter(r["slice"] for r in part)),
            "consequence": dict(Counter(r["rules"]["consequence"] for r in part)),
            "mechanism": dict(Counter(r["rules"]["mechanism"] for r in part)),
            "composite": sum(1 for r in part if r["rules"]["composite"]),
            "target_mechanism": dict(Counter(r["gold"]["mechanism"] for r in part if r.get("gold"))),
            "teacher_targets": sum(1 for r in part if r.get("teacher")),
        }
    try:
        git = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        git = None
    manifest = {
        "dataset": args.name,
        "targets": ("rules diagnosis; teacher prose where it passed the verifier; teacher composite rows "
                    "that passed" if teacher else
                    "rules diagnosis (chain and prose); composite rows await the teacher (v1)"),
        "teacher": runs or None,
        "graph_version": sorted({r["graph"].get("version") for r in rows if r.get("graph")}),
        "backend_git_sha": git,
        "engine": "Stockfish, deterministic (1 thread, hash cleared), depth 14",
        "splits": {"train": counts(train), "val": counts(val), "test_ab": counts(test),
                   "teacher_queue": counts(teacher_queue)},
        "dropped_or_capped": dict(report),
        "files": {str(p.relative_to(out)).replace("\\", "/"): sha256(p) for p in files},
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
    print(json.dumps({k: manifest[k] for k in ("splits", "dropped_or_capped")}, indent=1))


if __name__ == "__main__":
    main()
