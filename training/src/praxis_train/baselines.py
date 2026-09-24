"""Phase 5: the prompted half of the evaluation grid (plan §15.1).

Run:   python -m praxis_train.baselines run --run-dir DIR [--limit N] [--arms A,B]
Watch: python training/tools/phase5_status.py DIR

Each system is asked to diagnose every test mistake from the evidence at one
representation level (R0-R3), few-shot, with Ollama constrained to the §8.1
answer schema. This module only talks to models. It never renders evidence,
never writes the schema and never judges an answer: the backend's EvalCli does
all three, so the prompt a model sees here is byte for byte the one the app
renders, and every system is judged by the verifier the app uses.

The run is long (thousands of calls on a 4 GB laptop GPU) so it is built to be
interrupted: every answer is appended to outputs.jsonl as it arrives, a rerun
skips what is already there, and progress.json is rewritten after each answer
for the status command.
"""

from __future__ import annotations

import argparse
import json
import random
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path

OLLAMA_CHAT = "http://localhost:11434/api/chat"
OLLAMA_GENERATE = "http://localhost:11434/api/generate"

# The grid (§15.1). The order is the run order: one model at a time, so each is
# loaded once. S_rules needs no model; EvalCli verify scores it.
#
# route "raw": Ollama 0.23.1 does NOT apply the JSON schema to Qwen3.5 through
# /api/chat — asked for an enum of RED/BLUE, qwen3.5:2b answered GREEN and added
# fields of its own. Through /api/generate with raw=true it does. So Qwen3.5 gets
# the conversation as a raw ChatML prompt with the thinking block opened and
# closed empty, exactly the non-thinking format Phase 1 trained with (§9.3).
# qwen2.5 keeps /api/chat: its schema is enforced there, and it is how the app
# calls it today.
#
# sample: the 4B and 7B answer in 37 s and 51 s against the 2B's 15 s, so the
# full grid is ~46 hours on this laptop. The 2B answers everything: it carries
# the headline question (R0 vs R1 vs R2 vs R3). The slower arms answer the first
# SLOW_SAMPLE items of the same fixed shuffle, so every system shares those items
# and the comparisons between them stay paired. Chosen with the user, 2026-09-24.
SLOW_SAMPLE = 300
ARMS = [
    {"arm": "qwen3.5-2b", "model": "qwen3.5:2b", "levels": ["R0", "R1", "R2", "R3"], "route": "raw", "sample": None},
    {"arm": "qwen3.5-4b", "model": "qwen3.5:4b", "levels": ["R0", "R3"], "route": "raw", "sample": SLOW_SAMPLE},
    {"arm": "qwen2.5-7b", "model": "qwen2.5:7b", "levels": ["R1"], "route": "chat", "sample": SLOW_SAMPLE},
]

# Relative cost per answer, used only to guess the time for arms not yet
# started. Replaced by measured speed as soon as an arm has answers.
PRIOR_COST = {"qwen3.5-2b": 1.0, "qwen3.5-4b": 2.5, "qwen2.5-7b": 3.4}   # measured: 15 s, 37 s, 51 s

SYSTEM = """You are Prax, a chess coach. Diagnose the player's mistake using ONLY the evidence given. Output JSON.

Reason first. reasoning_chain is a list of typed claims, at most {max_chain}. Each claim has:
  type  - one of the claim types below
  args  - the arguments that type requires (moves in SAN, squares like "e4")
  cites - the evidence IDs it rests on, e.g. ["T1", "R1"], when the evidence has IDs; otherwise []
  text  - one short sentence
Then give:
  consequence - MATED, LOST_MATERIAL, MISSED_MATE, MISSED_MATERIAL, or NOT_CONCRETE when no material or mate changes hands
  mechanism   - the single cause, UNCLEAR if more than one or none fits, NONE when the consequence is NOT_CONCRETE
  motif, critical_response (the opponent's punishing move), visibility, explanation (two or three sentences)

Claim types and their required args:
{claim_reference}
COUNTERFACTUAL effect is one of: {effects}.

Claim only what the evidence supports. If nothing concrete happens, say NOT_CONCRETE and name no cause: an invented cause is worse than none."""


def load_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def system_prompt(schema: dict) -> str:
    ref = "\n".join(f"  {t}: {', '.join(args) if args else '(none)'}"
                    for t, args in schema["required_args"].items())
    return SYSTEM.format(max_chain=schema["max_chain"], claim_reference=ref,
                         effects=", ".join(schema["counterfactual_effects"]))


def messages(system: str, fewshot: list[dict], item: dict, level: str) -> list[dict]:
    """System, then each worked example at the SAME level as the test item, then the item."""
    out = [{"role": "system", "content": system}]
    for ex in fewshot:
        out.append({"role": "user", "content": ex["renders"][level]["text"]})
        out.append({"role": "assistant", "content": json.dumps(ex["gold"], ensure_ascii=False)})
    out.append({"role": "user", "content": item["renders"][level]["text"]})
    return out


# Greedy and seeded: the same prompt gets the same answer, so a rerun measures
# the same thing.
OPTIONS = {"temperature": 0, "top_p": 1, "seed": 0, "num_ctx": 6144, "num_predict": 1000}


def chatml(msgs: list[dict]) -> str:
    """Qwen's chat format, ending in an assistant turn whose thinking is already closed."""
    turns = "".join(f"<|im_start|>{m['role']}\n{m['content']}<|im_end|>\n" for m in msgs)
    return turns + "<|im_start|>assistant\n<think>\n\n</think>\n\n"


def ask(model: str, msgs: list[dict], schema: dict, route: str, timeout: int) -> tuple[str, int]:
    if route == "raw":
        url = OLLAMA_GENERATE
        body = {"model": model, "prompt": chatml(msgs), "raw": True, "format": schema, "stream": False,
                "keep_alive": "30m", "options": {**OPTIONS, "stop": ["<|im_end|>"]}}
    else:
        url = OLLAMA_CHAT
        body = {"model": model, "messages": msgs, "format": schema, "stream": False,
                "keep_alive": "30m", "options": OPTIONS}
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    started = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        payload = json.load(r)
    text = payload["response"] if route == "raw" else payload["message"]["content"]
    return text, int((time.time() - started) * 1000)


def ordered_items(items: list[dict], seed: int = 5) -> list[dict]:
    """A fixed shuffle, so a partial run (or --limit) is a sample of both slices, not the first games."""
    rng = random.Random(seed)
    out = sorted(items, key=lambda i: i["id"])
    rng.shuffle(out)
    return out


# ── progress ─────────────────────────────────────────────────────────────────

def write_progress(run_dir: Path, plan: list[tuple], done: set, latencies: dict,
                   current: str, failures: int, started: float) -> None:
    total = len(plan)
    per_arm_left: dict[str, int] = {}
    for arm, level, item_id in plan:
        if (arm, level, item_id) not in done:
            per_arm_left[arm] = per_arm_left.get(arm, 0) + 1
    measured = {a: statistics.mean(v[-50:]) for a, v in latencies.items() if v}
    seconds = 0.0
    rough = False
    for arm, left in per_arm_left.items():
        if arm in measured:
            seconds += left * measured[arm] / 1000
        elif measured:
            # Guess from a measured arm, scaled by the prior. Rough until measured.
            ref_arm, ref_ms = next(iter(measured.items()))
            seconds += left * ref_ms / 1000 * PRIOR_COST[arm] / PRIOR_COST[ref_arm]
            rough = True
        else:
            rough = True
    progress = {
        "updated": time.strftime("%Y-%m-%d %H:%M:%S"),
        "started": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(started)),
        "total": total,
        "done": len(done & set(plan)),
        "current": current,
        "failures": failures,
        "seconds_left": round(seconds) if measured else None,
        "eta_rough": rough,
        "avg_ms_last50": {a: round(ms) for a, ms in measured.items()},
        "left_per_arm": per_arm_left,
    }
    tmp = run_dir / "progress.json.tmp"
    tmp.write_text(json.dumps(progress, indent=1), encoding="utf-8")
    tmp.replace(run_dir / "progress.json")


# ── run ──────────────────────────────────────────────────────────────────────

def run(args) -> None:
    data = Path(args.data)
    run_dir = Path(args.run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)
    schema_file = json.loads((data / "schema.json").read_text(encoding="utf-8"))
    schema = schema_file["json_schema"]
    system = system_prompt(schema_file)
    fewshot = load_jsonl(data / "fewshot.jsonl")
    items = ordered_items(load_jsonl(data / "testset.jsonl"))
    if args.limit:
        items = items[: args.limit]
    arms = [a for a in ARMS if not args.arms or a["arm"] in args.arms.split(",")]

    (run_dir / "config.json").write_text(json.dumps({
        "arms": arms, "items": len(items), "fewshot": [f["id"] for f in fewshot],
        "system_prompt": system, "options": OPTIONS,
    }, indent=1), encoding="utf-8")

    outputs = run_dir / "outputs.jsonl"
    done = {(o["arm"], o["level"], o["item_id"]) for o in load_jsonl(outputs) if "error" not in o}
    def arm_items(a: dict) -> list[dict]:
        return items[: a["sample"]] if a.get("sample") else items

    plan = [(a["arm"], lvl, it["id"]) for a in arms for lvl in a["levels"] for it in arm_items(a)]
    latencies: dict[str, list[int]] = {}
    for o in load_jsonl(outputs):
        if "error" not in o:
            latencies.setdefault(o["arm"], []).append(o["latency_ms"])
    failures = 0
    started = time.time()
    by_id = {it["id"]: it for it in items}

    def attempt(arm: dict, level: str, item: dict) -> bool:
        nonlocal failures
        key = (arm["arm"], level, item["id"])
        row = {"item_id": item["id"], "arm": arm["arm"], "model": arm["model"], "level": level}
        try:
            raw, ms = ask(arm["model"], messages(system, fewshot, item, level), schema, arm["route"], args.timeout)
            row.update(raw=raw, latency_ms=ms)
            latencies.setdefault(arm["arm"], []).append(ms)
            done.add(key)
            ok = True
        except (urllib.error.URLError, TimeoutError, OSError, KeyError, json.JSONDecodeError) as e:
            row.update(error=f"{type(e).__name__}: {e}", latency_ms=0)
            failures += 1
            ok = False
        with outputs.open("a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
        return ok

    retry = []
    for arm in arms:
        todo = arm_items(arm)
        for level in arm["levels"]:
            for n, item in enumerate(todo, 1):
                if (arm["arm"], level, item["id"]) in done:
                    continue
                current = f'{arm["model"]} · {level} · mistake {n} of {len(todo)}'
                write_progress(run_dir, plan, done, latencies, current, failures, started)
                if not attempt(arm, level, item):
                    retry.append((arm, level, item))
    # One retry for anything that failed (a model load timing out, a stall).
    for arm, level, item in retry:
        write_progress(run_dir, plan, done, latencies, f'retry · {arm["model"]} · {level}', failures, started)
        attempt(arm, level, by_id[item["id"]])
    write_progress(run_dir, plan, done, latencies, "finished", failures, started)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--data", default="training/data/phase5")
    r.add_argument("--run-dir", required=True)
    r.add_argument("--limit", type=int, default=0, help="only the first N items of the fixed shuffle")
    r.add_argument("--arms", default="", help="comma-separated arm names; default all")
    r.add_argument("--timeout", type=int, default=600)
    args = p.parse_args()
    if args.cmd == "run":
        run(args)


if __name__ == "__main__":
    main()
