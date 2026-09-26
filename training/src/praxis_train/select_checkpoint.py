"""Phase 7: choose each arm's checkpoint by the verifier, not by loss.

Run (on the training machine, after train_sft):
    python -m praxis_train.select_checkpoint --config config/grid_v1/2b-r3.yaml

PREREGISTRATION.md §3: "the end-of-epoch checkpoint with the higher verifier
claim precision on a fixed 200-row validation sample (the first 200 rows of
that R's val.jsonl); ties go to epoch 2. Test sets are never used for selection."

For each <adapter_dir>/epoch-N it generates answers to those 200 prompts
(greedy, the training format), writes them in the Phase 5 outputs format, runs
the app's verifier (EvalCli verify, through tools/javacli.sh) against the
validation rows' graphs, and pools claim precision exactly as the metrics do.
The winner is copied to <adapter_dir>/selected, with selection.json beside it.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import time
from pathlib import Path

import torch
import yaml

ROOT = Path(__file__).resolve().parents[2]      # the repository


def load_rows(path: Path, n: int) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            if line.strip():
                rows.append(json.loads(line))
            if len(rows) == n:
                break
    return rows


def generate(base_name: str, revision: str, adapter: Path, rows: list[dict], batch: int, max_new: int) -> list[tuple[str, int]]:
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tok = AutoTokenizer.from_pretrained(str(adapter))
    tok.padding_side = "left"
    base = AutoModelForCausalLM.from_pretrained(base_name, revision=revision, dtype=torch.bfloat16, device_map={"": 0})
    model = PeftModel.from_pretrained(base, str(adapter)).eval()
    stop = [t for t in {tok.convert_tokens_to_ids("<|im_end|>"), tok.eos_token_id} if t is not None]
    prompts = [tok.apply_chat_template(r["messages"][:-1], tokenize=False, add_generation_prompt=True) for r in rows]
    out: list[tuple[str, int]] = []
    for i in range(0, len(prompts), batch):
        enc = tok(prompts[i:i + batch], return_tensors="pt", padding=True, add_special_tokens=False).to(model.device)
        started = time.time()
        with torch.no_grad():
            gen = model.generate(**enc, max_new_tokens=max_new, do_sample=False, eos_token_id=stop,
                                 pad_token_id=tok.pad_token_id)
        ms = int((time.time() - started) * 1000 / len(gen))
        for seq in gen[:, enc["input_ids"].shape[1]:]:
            out.append((tok.decode(seq, skip_special_tokens=True).strip(), ms))
        print(f"  {adapter.name}: {len(out)}/{len(prompts)}", flush=True)
    del model, base
    torch.cuda.empty_cache()
    return out


def precision(verified: Path, arm: str) -> dict:
    rows = [json.loads(l) for l in verified.read_text(encoding="utf-8").splitlines() if l.strip()]
    mine = [r for r in rows if r["arm"] == arm]
    parsed = [r for r in mine if r.get("parsed")]
    claims = sum(r.get("claims", 0) for r in parsed)
    return {"items": len(mine), "parsed": len(parsed),
            "claim_precision": round(sum(r.get("claims_true", 0) for r in parsed) / claims, 4) if claims else 0.0,
            "chain_validity": round(sum(1 for r in mine if r.get("claim_passed")) / len(mine), 4) if mine else 0.0}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", type=Path, required=True)
    ap.add_argument("--rows", type=int, default=200)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--max-new-tokens", type=int, default=1000)
    ap.add_argument("--graphs", default="data/phase7/val_gold.jsonl",
                    help="the validation rows' graphs (a slice of gold.jsonl)")
    ap.add_argument("--adapters", type=Path, help="default: the config's adapter_dir (the pilot passes its own)")
    args = ap.parse_args()
    cfg = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    arm = cfg["output"]["ollama_model"]
    adapters = args.adapters or Path(cfg["output"]["adapter_dir"])
    rows = load_rows(Path(cfg["data"]["val"]), args.rows)
    level = rows[0]["meta"]["R"]

    results = {}
    for epoch_dir in sorted(adapters.glob("epoch-*"), key=lambda p: int(p.name.split("-")[1])):
        work = adapters / "select" / epoch_dir.name
        work.mkdir(parents=True, exist_ok=True)
        outputs, verified = work / "outputs.jsonl", work / "verified.jsonl"
        label = f"{arm}@{epoch_dir.name}"
        if not outputs.exists():
            answers = generate(cfg["model"]["base"], cfg["model"]["revision"], epoch_dir, rows,
                               args.batch, args.max_new_tokens)
            with outputs.open("w", encoding="utf-8") as w:
                for r, (text, ms) in zip(rows, answers):
                    w.write(json.dumps({"item_id": r["meta"]["source_id"], "arm": label, "level": level,
                                        "raw": text, "latency_ms": ms}, ensure_ascii=False) + "\n")
        # The same verifier the app and every evaluation use.
        subprocess.run(["bash", str(ROOT / "training" / "tools" / "javacli.sh"), "EvalCli", "verify",
                        "--testset", str(Path(args.graphs).resolve()), "--outputs", str(outputs.resolve()),
                        "--out", str(verified.resolve())], check=True)
        results[epoch_dir.name] = precision(verified, label)
        print(f"  {label}: {results[epoch_dir.name]}", flush=True)

    if not results:
        raise SystemExit(f"no epoch-* checkpoints in {adapters}")
    # Highest claim precision; ties go to the later epoch.
    best = max(results, key=lambda e: (results[e]["claim_precision"], int(e.split("-")[1])))
    chosen = adapters / "selected"
    if chosen.exists():
        shutil.rmtree(chosen)
    shutil.copytree(adapters / best, chosen)
    selection = {"arm": arm, "level": level, "rows": len(rows), "rule": "PREREGISTRATION.md §3",
                 "epochs": results, "selected": best}
    (adapters / "selection.json").write_text(json.dumps(selection, indent=1), encoding="utf-8")
    print(json.dumps(selection, indent=1))


if __name__ == "__main__":
    main()
