"""The teacher: prose for single-cause targets, full chains for composite ones (plan §11.3).

Run (plain Python, no dependencies):
    python -m praxis_train.teacher --task prose --n 100 \
        --base-url https://POD_ID-8000.proxy.runpod.net/v1 --model Qwen/Qwen3.5-27B-FP8 \
        --no-thinking --workers 16 --gpu-rupees-per-hour 51 --out data/phase6/pilot_qwen27b_prose
    python -m praxis_train.teacher --task prose --n 100 \
        --base-url https://API/v1 --model NAME --rupees-per-mtok-in X --rupees-per-mtok-out Y \
        --out data/phase6/pilot_api
  then judge the answers with the app's verifier:
    EvalCli verify --testset data/phase6/gold.jsonl --outputs <out>/outputs.jsonl --out <out>/verified.jsonl
    python -m praxis_train.teacher --score <out>

Talks to any OpenAI-compatible chat endpoint: vLLM on a rented GPU serves one,
and so do most APIs. An API key, if needed, comes from TEACHER_API_KEY.

Only public rows (slices A and B) are ever sent; the loader refuses anything
else (A3, §11.3). The deciding number is rupees per example that PASSES the
verifier, not per example generated (§11.3): a cheap teacher that fails half
its answers is not cheap.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import threading
import time
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROMPTS = ROOT / "prompts"
# The verifier's own vocabulary and answer schema, written by EvalCli (Phase 5),
# and a textbook worked example that verifies. Never from a test set.
SCHEMA = ROOT / "data" / "phase5" / "schema.json"
FEWSHOT = ROOT / "data" / "phase5" / "fewshot.jsonl"
EXAMPLE_ID = "fewshot-Nf6"

# Rule 4 of the verifier: the words each claim type licenses.
CAUSAL_WORDS = "allow, allows, allowed, enable, enables, enabled, because, create, creates, created, lets"
THREAT_WORDS = "already, ignore, ignores, ignored"
# The verifier's MOVE_OR_SQUARE: a SAN move or a bare square. Only these go in
# the "may name" list; codes like FORK or SHALLOW are not for the prose anyway.
MOVE_OR_SQUARE = re.compile(r"(O-O-O|O-O|[KQRBN][a-h]?[1-8]?x?[a-h][1-8]|[a-h]x[a-h][1-8](=[QRBN])?|[a-h][1-8](=[QRBN])?)[+#]?")


def section(name: str, prompt: str, schema_file: dict) -> str:
    text = (PROMPTS / prompt).read_text(encoding="utf-8")
    rules = text.split("## Rules for every answer", 1)[1].split("## Task:", 1)[0]
    task = text.split(f"## Task: {name}", 1)[1].split("## Task:", 1)[0]
    # str.replace, not str.format: the prompt quotes JSON with braces in it.
    ref = "\n".join(f"  {t}: {', '.join(a) if a else '(none)'}" for t, a in schema_file["required_args"].items())
    task = (task.replace("{claim_reference}", ref)
                .replace("{effects}", ", ".join(schema_file["counterfactual_effects"]))
                .replace("{max_chain}", str(schema_file["max_chain"])))
    return ("Rules:" + rules + "\n" + task).strip()


# Each argument's JSON type, from the verifier's checks (DiagnosisVerifier.falsity).
# v2's schema left `args` as any object and got "-3 pawns" where 3 was wanted.
ARG_TYPES = {
    "move": {"type": "string"}, "threat": {"type": "string"}, "alt_move": {"type": "string"},
    "reply": {"type": "string"}, "played": {"type": "string"}, "after": {"type": "string"},
    "square": {"type": "string"}, "defender": {"type": "string"}, "slider": {"type": "string"},
    "target": {"type": "string"}, "by": {"type": "string"},
    "attackers": {"type": "array", "items": {"type": "string"}},
    "defenders": {"type": "array", "items": {"type": "string"}},
    "targets": {"type": "array", "items": {"type": "string"}},
    "amount": {"type": "integer"}, "n": {"type": "integer"}, "ply": {"type": "integer"}, "depth": {"type": "integer"},
    "line": {"enum": ["PLAYED", "BEST"]}, "result": {"enum": ["MATE", "MATERIAL", "NONE"]},
    "kind": {"enum": ["FORK", "PIN", "SKEWER", "DISCOVERED_ATTACK"]}, "position": {"enum": ["P0", "PP"]},
}
# Arguments the verifier reads when present but does not require.
OPTIONAL_ARGS = {
    "CRITICAL_REPLY": ["after", "result", "ply"], "MATERIAL_CHANGE": ["line"], "MATE_IN": ["line"],
    "COUNTERFACTUAL": ["played", "reply"], "DEFENDER_REMOVED": ["move"], "TACTIC_GEOMETRY": ["move"],
    "VISIBILITY": ["depth"], "ATTACK_DEFENSE": ["position"],
}


def strict_schema(schema_file: dict) -> dict:
    """The answer schema with one claim shape per type: its own args, each typed.

    anyOf over the claim types, so the server cannot pair a type with another
    type's arguments, write a number as text, or invent an argument.
    """
    schema = json.loads(json.dumps(schema_file["json_schema"]))
    variants = []
    for ctype, required in schema_file["required_args"].items():
        names = list(required) + OPTIONAL_ARGS.get(ctype, [])
        props = {a: dict(ARG_TYPES.get(a, {"type": "string"})) for a in names}
        if ctype == "COUNTERFACTUAL":
            props["effect"] = {"enum": schema_file["counterfactual_effects"]}
        if ctype == "VISIBILITY":
            props["band"] = {"enum": schema["properties"]["visibility"]["enum"]}
        variants.append({"type": "object", "properties": {
            "type": {"enum": [ctype]},
            "args": {"type": "object", "properties": props, "required": list(required), "additionalProperties": False},
            "cites": {"type": "array", "items": {"type": "string"}},
            "text": {"type": "string"}},
            "required": ["type", "args", "cites", "text"], "additionalProperties": False})
    schema["properties"]["reasoning_chain"]["items"] = {"anyOf": variants}
    return schema


def strings(value) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [s for v in value for s in strings(v)]
    return []


def prose_limits(chain: list[dict]) -> str:
    """What this item's explanation may name and must not say, from its own chain.

    The verifier accepts a move or square only if it appears in a claim's args,
    and the rule-4 words only with the claim that licenses them. Saying so per
    item is what v1 lacked: it stated the rule and left the model to apply it.
    """
    named = sorted({s for c in chain for v in (c.get("args") or {}).values() for s in strings(v)
                    if MOVE_OR_SQUARE.fullmatch(s)})
    types = {c.get("type") for c in chain}
    banned = ([] if "COUNTERFACTUAL" in types else [CAUSAL_WORDS]) + ([] if "THREAT_EXISTS" in types else [THREAT_WORDS])
    return (f"Moves and squares you may name (no others): {', '.join(named) or 'none'}\n"
            f"Words you must not use: {'; '.join(banned) or 'none'}")


def composite_user(render: str, consequence: str, fired: list[str]) -> str:
    return (f"{render}\n\nConsequence: {consequence}\n"
            f"Causes whose preconditions hold: {', '.join(fired) or 'none'}")


def load_public(path: Path) -> list[dict]:
    rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    private = [r["id"] for r in rows if r.get("slice") not in ("A", "B")]
    if private:
        raise SystemExit(f"refusing: {len(private)} rows are not public slices A/B (e.g. {private[0]})")
    return rows


def chat(base_url: str, model: str, messages: list[dict], schema: dict | None, timeout: int,
         no_thinking: bool = False) -> tuple[str, dict, int]:
    # With a schema the server can only emit the answer's shape: claim types
    # from the vocabulary, every field present. The verifier still judges truth.
    response_format = ({"type": "json_schema", "json_schema": {"name": "answer", "schema": schema}}
                       if schema else {"type": "json_object"})
    body = {"model": model, "temperature": 0.7, "max_tokens": 900,
            "response_format": response_format, "messages": messages}
    if no_thinking:
        # Qwen3.5 thinks by default: a long hidden essay before every answer,
        # paid for by the GPU-hour and able to run past max_tokens. vLLM reads
        # this; a hosted API may reject it, hence a flag.
        body["chat_template_kwargs"] = {"enable_thinking": False}
    # A named User-Agent: RunPod's proxy (Cloudflare) refuses Python's default
    # "Python-urllib/x.y" with a 403 (error 1010) before vLLM ever sees it.
    headers = {"Content-Type": "application/json", "User-Agent": "praxis-teacher/1.0"}
    if os.environ.get("TEACHER_API_KEY"):
        headers["Authorization"] = "Bearer " + os.environ["TEACHER_API_KEY"]
    req = urllib.request.Request(base_url.rstrip("/") + "/chat/completions", json.dumps(body).encode(), headers)
    started = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        payload = json.load(r)
    return payload["choices"][0]["message"]["content"], payload.get("usage", {}), int((time.time() - started) * 1000)


def generate(args) -> None:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    schema_file = json.loads(SCHEMA.read_text(encoding="utf-8"))
    system = section(args.task, args.prompt, schema_file)
    prefix = [{"role": "system", "content": system}]
    if args.task == "prose":
        # Only rows the dataset actually trains on: gold.jsonl also holds test
        # rows and rows the per-mechanism caps left out, about half of it.
        used = {json.loads(l)["meta"]["source_id"] for s in ("train", "val")
                for l in (Path(args.splits) / f"{s}.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()}
        rows = [r for r in load_public(Path(args.gold)) if r.get("gold_verified")
                and r["rules"]["consequence"] != "NOT_CONCRETE" and r["id"] in used]
        schema = {"type": "object", "properties": {"explanation": {"type": "string"}},
                  "required": ["explanation"], "additionalProperties": False}
    else:
        rows = load_public(Path(args.queue))
        schema = schema_file["json_schema"] if args.loose_args else strict_schema(schema_file)
        # One worked example, in the same user format as the real items. Shared
        # by every call, so vLLM's prefix cache pays for it once.
        ex = next(json.loads(l) for l in FEWSHOT.read_text(encoding="utf-8").splitlines()
                  if l.strip() and json.loads(l)["id"] == EXAMPLE_ID)
        prefix += [{"role": "user", "content": composite_user(ex["renders"]["R3"]["text"], ex["gold"]["consequence"],
                                                              [ex["gold"]["mechanism"]])},
                   {"role": "assistant", "content": json.dumps(ex["gold"], ensure_ascii=False)}]
    if args.no_schema:
        schema = None
    random.Random(args.seed).shuffle(rows)
    rows = rows[: args.n] if args.n else rows
    if args.only_failed:
        # A second attempt at the items an earlier run's answers failed: at
        # temperature 0.7 a retry is a fresh draw, and it costs only those items.
        failed = {v["item_id"] for v in map(json.loads, filter(str.strip, Path(args.only_failed).read_text(
            encoding="utf-8").splitlines())) if v["arm"].startswith("teacher:") and not v.get("claim_passed")}
        rows = [r for r in rows if r["id"] in failed]
    print(f"{len(rows)} items to ask")

    done = set()
    outputs = out / "outputs.jsonl"
    if outputs.exists():
        # Only answers count as done: a failed call is retried on the next run.
        done = {o["item_id"] for o in map(json.loads, filter(str.strip, outputs.read_text(encoding="utf-8").splitlines()))
                if "error" not in o}
    usage_total = {"prompt_tokens": 0, "completion_tokens": 0, "seconds": 0.0, "calls": 0}
    lock = threading.Lock()
    started = time.time()
    with outputs.open("a", encoding="utf-8") as w:
        def one(r: dict) -> None:
            evidence = r["renders"]["R3"]["text"]
            if args.task == "prose":
                chain = r["gold"]["reasoning_chain"]
                user = (f"{evidence}\n\nVerified reasoning chain:\n{json.dumps(chain, ensure_ascii=False)}\n\n"
                        f"{prose_limits(chain)}")
            else:
                user = composite_user(evidence, r["rules"]["consequence"], r["rules"]["fired"])
            row = {"item_id": r["id"], "arm": f"teacher:{args.model}", "level": "R3", "prompt": args.prompt}
            try:
                text, usage, ms = chat(args.base_url, args.model, prefix + [{"role": "user", "content": user}],
                                       schema, args.timeout, args.no_thinking)
                if args.task == "prose":
                    # The chain and labels stay the rules'; only the prose is the teacher's.
                    diagnosis = dict(r["gold"], explanation=json.loads(text)["explanation"])
                    text = json.dumps(diagnosis, ensure_ascii=False)
                row.update(raw=text, latency_ms=ms)
                with lock:
                    usage_total["prompt_tokens"] += usage.get("prompt_tokens", 0)
                    usage_total["completion_tokens"] += usage.get("completion_tokens", 0)
                    usage_total["seconds"] += ms / 1000
                    usage_total["calls"] += 1
            except Exception as e:  # recorded, never fatal: a pilot measures failures too
                row.update(error=f"{type(e).__name__}: {e}", latency_ms=0)
            with lock:
                w.write(json.dumps(row, ensure_ascii=False) + "\n")
                w.flush()

        # vLLM batches whatever is in flight, so several requests at once cost
        # little more GPU time than one. --workers 1 is the old sequential run.
        todo = [r for r in rows if r["id"] not in done]
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            list(pool.map(one, todo))
    # With parallel requests, summed latencies overstate the GPU time; the
    # rented GPU is billed by the wall clock.
    usage_total["wall_seconds"] = round(time.time() - started, 1)
    cost = {"gpu_rupees_per_hour": args.gpu_rupees_per_hour,
            "rupees_per_mtok_in": args.rupees_per_mtok_in, "rupees_per_mtok_out": args.rupees_per_mtok_out}
    (out / "usage.json").write_text(json.dumps({"usage": usage_total, "prices": cost, "task": args.task,
                                                "model": args.model, "workers": args.workers}, indent=1),
                                    encoding="utf-8")
    print(f"{usage_total['calls']} answers -> {outputs}")


def score(folder: Path) -> None:
    """Rupees per verified example: the number the plan chooses the teacher by."""
    u = json.loads((folder / "usage.json").read_text(encoding="utf-8"))
    verified = [json.loads(l) for l in (folder / "verified.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    answers = [v for v in verified if v["arm"].startswith("teacher:")]
    passed = sum(1 for v in answers if v.get("claim_passed"))
    p, us = u["prices"], u["usage"]
    if p.get("gpu_rupees_per_hour"):
        rupees = p["gpu_rupees_per_hour"] * us.get("wall_seconds", us["seconds"]) / 3600
    else:
        rupees = (us["prompt_tokens"] * (p.get("rupees_per_mtok_in") or 0)
                  + us["completion_tokens"] * (p.get("rupees_per_mtok_out") or 0)) / 1e6
    per = rupees / passed if passed else None
    # Why answers failed, most common first: how v1 -> v2 -> v3 of the prompt
    # were found. Claim-level reasons ("rule 2: MATERIAL_CHANGE: ...") are cut
    # before the item's own values so that like counts with like.
    reasons = Counter(re.sub(r"(net material is|consequence ply is|result is) .*", r"\1 …", v)[:90]
                      for a in answers for v in a.get("violations", []))
    claims = sum(a.get("claims", 0) for a in answers)
    print(json.dumps({"model": u["model"], "task": u["task"], "answers": len(answers), "passed": passed,
                      "pass_rate": round(passed / len(answers), 3) if answers else None,
                      "claims_true": round(sum(a.get("claims_true", 0) for a in answers) / claims, 3) if claims else None,
                      "rupees_total": round(rupees, 2), "rupees_per_verified_example": per and round(per, 3),
                      "top_failure_reasons": dict(reasons.most_common(8))},
                     indent=1, ensure_ascii=False))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", choices=["prose", "composite"], default="prose")
    ap.add_argument("--gold", default="data/phase6/gold.jsonl")
    ap.add_argument("--queue", default="data/phase6/teacher_queue.jsonl")
    ap.add_argument("--n", type=int, default=100, help="pilot size; 0 for everything")
    ap.add_argument("--splits", default="data/phase6/R3", help="prose: only rows in this folder's train/val")
    ap.add_argument("--only-failed", help="a verified.jsonl: ask again only the items whose answers failed there")
    ap.add_argument("--base-url")
    ap.add_argument("--model")
    ap.add_argument("--out")
    ap.add_argument("--timeout", type=int, default=300)
    ap.add_argument("--workers", type=int, default=1, help="requests in flight at once (vLLM batches them)")
    ap.add_argument("--prompt", default="teacher_v3.md", help="the versioned instructions in prompts/")
    ap.add_argument("--loose-args", action="store_true",
                    help="v2's schema: claim args as any object (if the server rejects the typed one)")
    ap.add_argument("--no-schema", action="store_true",
                    help="plain JSON mode, for an endpoint without json_schema support")
    ap.add_argument("--no-thinking", action="store_true",
                    help="ask the server to skip Qwen3.5's thinking block (vLLM's chat_template_kwargs)")
    ap.add_argument("--seed", type=int, default=3)
    ap.add_argument("--gpu-rupees-per-hour", type=float)
    ap.add_argument("--rupees-per-mtok-in", type=float)
    ap.add_argument("--rupees-per-mtok-out", type=float)
    ap.add_argument("--score", help="score a finished pilot folder instead of generating")
    args = ap.parse_args()
    if args.score:
        score(Path(args.score))
    else:
        if not (args.base_url and args.model and args.out):
            raise SystemExit("--base-url, --model and --out are required to generate")
        generate(args)


if __name__ == "__main__":
    main()
