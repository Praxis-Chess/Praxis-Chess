"""Reproduce the Praxis Chess public-test table (REPRODUCE.md).

    # 1. one model end to end: download, answer the 838 public positions, verify, compare
    python reproduce.py run --model 2b-r3                    # Ollama + the published GGUF (the table's setup)
    python reproduce.py run --model 2b-r3 --backend transformers   # bf16 adapter on a CUDA GPU, no schema

    # 2. re-score without running a model: the published answers, through praxis_eval
    python reproduce.py score

    python reproduce.py run --model 2b-r3 --limit 50         # a quick look; intervals are not comparable

Run from training/ after `pip install -e .` (or with src/ on PYTHONPATH).

What "reproduced" means: the claim precision and chain validity you measure
fall inside the published 95% intervals (reports/public_test_v1.json). With
the Ollama backend the setup is the published one exactly (the q4_K_M GGUF,
greedy, seed 0, the answer JSON schema), so expect the same numbers or very
nearly. The Transformers backend runs the bf16 adapter without the schema,
because generate() cannot apply one; the Phase 8 quantisation check measured
that gap at -0.1 points of claim precision on 300 items, but chain validity
can move more, so read it as a cross-check rather than the reproduction.

--source local reads every file from this repository instead of the Hub (the
author's check before release; also works offline).
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "src"))

from praxis_eval.__main__ import verify_file  # noqa: E402
from praxis_train.baselines import ask, ordered_items  # noqa: E402
from praxis_train.build_dataset6 import SYSTEM  # noqa: E402
from praxis_train.public_table import LABELS, markdown, table  # noqa: E402

DATASET = "praxis-chess/praxis-chess-evidence-graphs"
REVISION = "v1.0"

# Where each model's files live on the Hub. The released models (R3) have their
# own repos; the comparison arms that complete the table live in the arms repo.
REPO_2B, REPO_4B = "praxis-chess/praxis-chess-reasoner-qwen3.5-2b-lora", "praxis-chess/praxis-chess-reasoner-qwen3.5-4b-lora"
GGUF_2B, GGUF_4B = "praxis-chess/praxis-chess-reasoner-qwen3.5-2b-GGUF", "praxis-chess/praxis-chess-reasoner-qwen3.5-4b-GGUF"
ARMS_REPO = "praxis-chess/praxis-chess-grid-v1-arms"
BASE_2B = ("Qwen/Qwen3.5-2B", "15852e8c16360a2fea060d615a32b45270f8a8fc")
BASE_4B = ("Qwen/Qwen3.5-4B", "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a")

MODELS = {
    "2b-r3": {"level": "R3", "base": BASE_2B, "adapter": (REPO_2B, ""),
              "gguf": (GGUF_2B, "praxis-chess-reasoner-qwen3.5-2b-Q4_K_M.gguf")},
    "4b-r3": {"level": "R3", "base": BASE_4B, "adapter": (REPO_4B, ""),
              "gguf": (GGUF_4B, "praxis-chess-reasoner-qwen3.5-4b-Q4_K_M.gguf")},
    **{arm: {"level": arm.split("-")[1].upper(), "base": BASE_4B if arm.startswith("4b") else BASE_2B,
             "adapter": (ARMS_REPO, f"{arm}/adapter"), "gguf": (ARMS_REPO, f"{arm}/praxis-grid-{arm}-Q4_K_M.gguf")}
       for arm in ("2b-r0", "2b-r1", "2b-r2", "4b-r0")},
}

# --source local: the same files, from this repository.
LOCAL = {
    "testset": HERE / "data/phase6_v1/test_ab_ablations.jsonl",
    "answer_schema": HERE / "data/phase5/schema.json",
    "published": HERE / "reports/public_test_v1.json",
    "outputs": HERE / "results_private/phase8/grid_ab/outputs.jsonl",
}
HUB_FILES = {
    "testset": "data/test/test.jsonl",
    "answer_schema": "schema/answer_schema.json",
    "published": "results/public_test_v1.json",
    "outputs": "results/outputs.jsonl",
}


def dataset_file(name: str, source: str) -> Path:
    if source == "local":
        return LOCAL[name]
    from huggingface_hub import hf_hub_download
    return Path(hf_hub_download(DATASET, HUB_FILES[name], repo_type="dataset", revision=REVISION))


def model_file(repo: str, path: str, source: str, model: str) -> Path:
    if source == "local":
        root = HERE / "outputs/grid_v1_results" / model
        return root / ("adapter" if path.endswith("adapter") or path == "" else f"praxis-grid-{model}-q4_k_m.gguf")
    from huggingface_hub import hf_hub_download, snapshot_download
    if path.endswith(".gguf"):
        return Path(hf_hub_download(repo, path, revision=REVISION))
    folder = snapshot_download(repo, revision=REVISION, allow_patterns=[f"{path}/*" if path else "*"],
                               ignore_patterns=["*.gguf"])
    return Path(folder) / path


def load_jsonl(p: Path) -> list[dict]:
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


# ── answering ────────────────────────────────────────────────────────────────

def answer_ollama(model: str, spec: dict, items: list[dict], out: Path, source: str, gguf: Path | None) -> None:
    """The published setup: the q4_K_M GGUF in Ollama, raw prompt, greedy, seed 0, the answer schema."""
    gguf = gguf or model_file(*spec["gguf"], source, model)
    name = f"praxis-repro-{model}"
    with tempfile.TemporaryDirectory() as tmp:
        # Raw mode sends the full prompt, so the Modelfile needs only the weights
        # and the context the published runs used.
        mf = Path(tmp) / "Modelfile"
        mf.write_text(f'FROM "{gguf.resolve().as_posix()}"\nPARAMETER num_ctx 3072\n', encoding="utf-8")
        subprocess.run(["ollama", "create", name, "-f", str(mf)], check=True, stdout=subprocess.DEVNULL)
    schema = json.loads(dataset_file("answer_schema", source).read_text(encoding="utf-8"))["json_schema"]
    done = {o["item_id"] for o in load_jsonl(out)} if out.exists() else set()
    with out.open("a", encoding="utf-8") as w:
        for n, it in enumerate(items, 1):
            if it["id"] in done:
                continue
            msgs = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": it["renders"][spec["level"]]["text"]}]
            raw, ms = ask(name, msgs, schema, "trained", timeout=600)
            w.write(json.dumps({"item_id": it["id"], "arm": f"lora-{model}", "model": name, "level": spec["level"],
                                "raw": raw, "latency_ms": ms}, ensure_ascii=False) + "\n")
            w.flush()
            if n % 25 == 0 or n == len(items):
                print(f"  {model}: {n}/{len(items)}", flush=True)


def answer_transformers(model: str, spec: dict, items: list[dict], out: Path, source: str, gguf: Path | None) -> None:
    """The bf16 adapter through Transformers + PEFT, greedy, no schema (Phase 8's quant_delta.py setup)."""
    from praxis_train.select_checkpoint import generate
    adapter = model_file(*spec["adapter"], source, model)
    base, revision = spec["base"]
    rows = [{"messages": [{"role": "system", "content": SYSTEM},
                          {"role": "user", "content": it["renders"][spec["level"]]["text"]},
                          {"role": "assistant", "content": ""}]} for it in items]
    answers = generate(base, revision, adapter, rows, batch=16, max_new=1000)
    with out.open("w", encoding="utf-8") as w:
        for it, (text, ms) in zip(items, answers):
            w.write(json.dumps({"item_id": it["id"], "arm": f"lora-{model}", "model": f"bf16-{model}",
                                "level": spec["level"], "raw": text, "latency_ms": ms}, ensure_ascii=False) + "\n")


# ── scoring ──────────────────────────────────────────────────────────────────

def compare(measured: dict, published: dict, partial: bool) -> bool:
    print("\nAgainst the published table (reports/public_test_v1.json):\n")
    print(f"{'System':36} {'Metric':16} {'Measured':>9}  {'Published (95% CI)':>24}  Inside")
    ok = True
    for name, s in measured.items():
        if name == "S_rules" or name not in published:
            continue
        p = published[name]
        for key, label in (("claim_precision", "claim precision"), ("chain_validity", "chain valid")):
            lo, hi = p[f"{key}_ci"]
            inside = lo <= s[key] <= hi
            ok &= inside
            print(f"{LABELS.get(name, name):36} {label:16} {100 * s[key]:8.1f}%  "
                  f"{100 * p[key]:6.1f}% ({100 * lo:.1f}–{100 * hi:.1f})  {'yes' if inside else 'NO'}")
    if partial:
        print("\n--limit was used: a sample's numbers are not judged against the full-set intervals.")
        return True
    print("\nReproduced: every number inside its interval." if ok else
          "\nNOT reproduced: at least one number falls outside its interval.")
    return ok


def score(outputs: Path, work: Path, source: str, partial: bool) -> bool:
    testset = dataset_file("testset", source)
    verified = work / "verified.jsonl"
    rows = verify_file(testset, outputs, verified)
    print(f"verified {rows} answers (the rules' own {rows - len(load_jsonl(outputs))} included) -> {verified}")
    measured = table(verified, testset)
    print("\n" + "\n".join(markdown(measured)))
    (work / "measured.json").write_text(json.dumps(measured, indent=1), encoding="utf-8")
    published = json.loads(dataset_file("published", source).read_text(encoding="utf-8"))
    return compare(measured, published, partial)


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")   # the tables use en dashes; a Windows console defaults to cp1252
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="answer the public test set with one model, then score it")
    r.add_argument("--model", choices=sorted(MODELS), default="2b-r3")
    r.add_argument("--backend", choices=["ollama", "transformers"], default="ollama")
    r.add_argument("--limit", type=int, default=0, help="only the first N items of the fixed shuffle")
    r.add_argument("--gguf", type=Path, help="answer with this GGUF instead of the published one (a model you retrained)")
    s = sub.add_parser("score", help="re-score answers without running a model (default: the published ones)")
    s.add_argument("--outputs", type=Path, help="answers to score; default: the published results/outputs.jsonl")
    for p in (r, s):
        p.add_argument("--source", choices=["hub", "local"], default="hub")
        p.add_argument("--work", type=Path, default=Path("reproduce_runs"))
    args = ap.parse_args()

    if args.cmd == "run":
        spec = MODELS[args.model]
        work = args.work / f"{args.model}-{args.backend}"
        work.mkdir(parents=True, exist_ok=True)
        items = ordered_items(load_jsonl(dataset_file("testset", args.source)))
        if args.limit:
            items = items[: args.limit]
        out = work / "outputs.jsonl"
        started = time.time()
        (answer_ollama if args.backend == "ollama" else answer_transformers)(args.model, spec, items, out, args.source,
                                                                             args.gguf)
        print(f"answered {len(items)} positions in {(time.time() - started) / 60:.1f} min")
        ok = score(out, work, args.source, partial=bool(args.limit))
    else:
        work = args.work / "rescore"
        work.mkdir(parents=True, exist_ok=True)
        ok = score(args.outputs or dataset_file("outputs", args.source), work, args.source, partial=False)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
