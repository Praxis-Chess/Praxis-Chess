# praxis-train

Dataset construction, LoRA training and export for Praxis Chess's
evidence-grounded mistake explanations. See
`technical-docs/LORA_IMPLEMENTATION_PLAN.md` for why any of this exists;
`reports/` holds what each run actually measured.

Phase 1 is a **toolchain spike**, not a model anyone should use. Its job was to
find out whether Qwen3.5 can be trained, exported and served on a 4 GB laptop
GPU before that assumption was built on. It can — `reports/phase1_spike.md` has
the numbers and the six workarounds it took.

## Environment

Linux or WSL2 with a CUDA GPU. Everything below was run on WSL2 Ubuntu 24.04
with an RTX 3050 Laptop (4 GB) and driver 596.49.

```bash
uv venv --python 3.12 --managed-python ~/.venvs/praxis-train
VIRTUAL_ENV=$HOME/.venvs/praxis-train uv pip install -e .
```

**Use a uv-managed Python, not the system one.** Triton compiles a CUDA shim at
the first forward pass and needs `Python.h`; Ubuntu's `python3.12` ships no
headers, and the failure surfaces as a compiler error deep in a training run
rather than at install time.

**A C compiler must be on PATH**, for the same reason. If you have root,
`apt install build-essential` and stop reading. If you do not:

```bash
VIRTUAL_ENV=$HOME/.venvs/praxis-train uv pip install ziglang
mkdir -p ~/.local/shim
cp tools/zig-cc.sh ~/.local/shim/cc && chmod +x ~/.local/shim/cc
ln -sf ~/.local/shim/cc ~/.local/shim/gcc
export PATH="$HOME/.local/shim:$PATH"
```

`tools/zig-cc.sh` explains the one translation it has to do. `train_sft.py`
detects whether a compiler is present and reports which kernel path it took; it
never picks one silently, because the fallback is much slower and that should
never be a surprise in a timing report.

## The pipeline

```
Lichess puzzles (CC0)  ->  stub graph  ->  prompt + template target  ->  JSONL
                                                                          |
                          LoRA (bf16, r=16)  <-------------------------- +
                                |
                merge -> GGUF (q8_0) -> Ollama -> held-out evaluation
```

```bash
python -m praxis_train.build_dataset --out data/phase1 --count 200
python -m praxis_train.train_sft     --config config/dev_0p8b.yaml
python -m praxis_train.export        --config config/dev_0p8b.yaml
ollama create praxis-phase1 -f outputs/Modelfile
python -m praxis_train.spike_eval --model praxis-phase1 --compare qwen2.5:7b
```

Each step writes a report next to its output (`train_report.json`,
`data/phase1/manifest.json`, `reports/phase1_spike.json`) so a run can be
compared with an earlier one without rerunning it.

## Modules

| | |
|---|---|
| `lichess_puzzles.py` | Streams a prefix of the CC0 puzzle export and fills per-mechanism quotas. The rows are not shuffled by theme, so taking the first N would be badly skewed. |
| `stub_graph.py` | Evidence for one blundered move, from python-chess alone: what the refutation attacks, what the line actually captures, check, mate, material. Phase 2 replaces this with the real graph behind Stockfish. |
| `render.py` | The evidence block the model reads, and the template target. |
| `build_dataset.py` | Rows, rejections, a split by position, and a manifest. |
| `train_sft.py` | LoRA SFT. Picks the kernel path and says which. |
| `export.py` | Merge → GGUF → a Modelfile with the template pinned. |
| `spike_eval.py` | Invented squares and boilerplate rate on held-out rows. |
| `baselines.py`, `baseline_metrics.py` | Phase 5: prompted models on the test sets, and their scores. |
| `slice_a.py` | Phase 6: balanced puzzle candidates from the local CC0 prefix. |
| `slice_b.py` | Phase 6: public games at 1000–1600, streamed and scrubbed. |
| `build_dataset6.py` | Phase 6: leakage filter, dedupe, split by source, balance, per-R JSONL, manifest. |
| `teacher.py` | Phase 6: teacher prose and composite chains; scores a pilot in rupees per verified example. |
| `trained_metrics.py` | Phase 6: a trained model against the Phase 5 baselines, paired, and on held-out A+B. |

Before any 2B/4B training: [`PREREGISTRATION.md`](PREREGISTRATION.md) (tag
`prereg-v1`) fixes the hypotheses, metrics, statistics and ship rules, and
`python training/tools/prereg_hashes.py --check` proves a run used the
registered data.

## Phase 6: building the training dataset

The Java half (evidence, renders, the rules' targets, the verifier) is the
backend's own code, run through `tools/javacli.sh`: it compiles the backend into
the gitignored `.javabuild/` and never touches `backend/target/`, so it cannot
disturb a running backend. Python only selects, scrubs, splits and talks to
models. Every command runs from the repository root.

| Step | Command | Time | Uses |
|---|---|---|---|
| 1. Pick slice A puzzles | `wsl.exe bash -lc "cd /mnt/d/Tanm/Projects/Praxis-Chess/training && ~/.venvs/praxis-train/bin/python -m praxis_train.slice_a"` | ~2 min | CPU, light |
| 2. Evidence for slice A | `bash training/tools/javacli.sh DatasetCli graphs --in training/data/phase6/slice_a_candidates.tsv --out training/data/phase6/graphs_a.jsonl` | ~40 min | Stockfish |
| 3. Download slice B games | `wsl.exe bash -lc "cd /mnt/d/Tanm/Projects/Praxis-Chess/training && ~/.venvs/praxis-train/bin/python -m praxis_train.slice_b"` | ~2–5 min, ≤ 50 MB | network |
| 4. Analyse slice B | `bash training/tools/javacli.sh DatasetCli corpus --pgn training/data/phase6/slice_b_games.pgn --out training/data/phase6/graphs_b.jsonl` | ~3–5 h | Stockfish |
| 5. Targets | `bash training/tools/javacli.sh DatasetCli gold --in training/data/phase6/graphs_a.jsonl,training/data/phase6/graphs_b.jsonl --out training/data/phase6/gold.jsonl` | ~2 min | CPU |
| 6. Dataset | `cd training && set PYTHONPATH=src&& python -m praxis_train.build_dataset6` | seconds | CPU |
| 7. 0.8B dev loop | `wsl.exe bash -lc "cd /mnt/d/Tanm/Projects/Praxis-Chess/training && ~/.venvs/praxis-train/bin/python -m praxis_train.train_sft --config config/dev_0p8b_phase6.yaml"` | overnight | GPU |

Steps 2 and 4 resume where they stopped, and `python training/tools/phase6_status.py`
shows their progress and finish time. Step 4 swept every game with the pipeline's
own parser, engine sweep and `MistakeCandidateFilter`, so slice B's mistakes are
the ones the app would flag; the games never enter the player's database.

Version 0 of the dataset uses the rules' own diagnosis as the target, prose
included. Composite mistakes wait in `teacher_queue.jsonl` for the teacher
(`teacher.py`, `prompts/teacher_v3.md`), which is chosen by rupees per verified
example in a 100-example pilot (§11.3) and becomes version 1.

### Testing a trained model

| Step | Command | Time | Uses |
|---|---|---|---|
| Export | `wsl.exe bash -lc "cd /mnt/d/Tanm/Projects/Praxis-Chess/training && PYTHONPATH=src ~/.venvs/praxis-train/bin/python -m praxis_train.export --config config/dev_0p8b_phase6.yaml"` | ~5 min | CPU |
| Load into Ollama | `ollama create praxis-phase6-dev -f training/outputs/praxis-phase6-dev.Modelfile` | ~1 min | disk |
| Exam, verify, report | `bash training/tools/phase6_eval.sh` → `training/reports/phase6_dev_eval.md` | ~2.5 h | GPU |

The exam asks the model in its training format (the dataset's system message,
no worked examples) through `baselines.py --arms praxis-0p8b-dev`, and
`trained_metrics.py` pairs it with the Phase 5 baselines on the same questions.

### The teacher on a rented GPU (version 1)

Qwen3.5-27B needs a 48 GB card: the official FP8 and GPTQ-Int4 files are both
about 30 GB. The pod serves it with vLLM; this laptop sends only public rows
(slices A and B) and judges every answer with the backend's verifier.

1. **Pod:** RunPod, 48 GB (A40 / A6000 / L40S), On-Demand, template
   "Runpod Pytorch 2.8.0", container disk 30 GB, volume 60 GB at `/workspace`,
   HTTP port `8000`, env `HF_HOME=/workspace/hf`.
2. **On the pod (Jupyter terminal):**
   `pip install -U vllm`, then `export VLLM_KEY=$(openssl rand -hex 16); echo $VLLM_KEY`, then
   `vllm serve Qwen/Qwen3.5-27B-FP8 --host 0.0.0.0 --port 8000 --max-model-len 6144 --language-model-only --reasoning-parser qwen3 --gpu-memory-utilization 0.92 --max-num-seqs 32 --api-key $VLLM_KEY`
3. **Here (PowerShell):** `$env:TEACHER_API_KEY = "<the key>"`, check with
   `curl.exe https://<POD_ID>-8000.proxy.runpod.net/v1/models -H "Authorization: Bearer $env:TEACHER_API_KEY"`.
4. **Pilot, then the full run** (from `training/`, `$env:PYTHONPATH = "src"`):
   `python -m praxis_train.teacher --task composite --n 100 --base-url https://<POD_ID>-8000.proxy.runpod.net/v1 --model Qwen/Qwen3.5-27B-FP8 --no-thinking --workers 16 --gpu-rupees-per-hour 48 --out data/phase6/pilot_...`;
   same with `--task prose`; `--n 0` for everything; `--only-failed <run>/verified.jsonl` for a retry.
5. **Judge:** `bash tools/javacli.sh EvalCli verify --testset data/phase6/gold.jsonl --outputs <run>/outputs.jsonl --out <run>/verified.jsonl`,
   then `python -m praxis_train.teacher --score <run>` (rupees per verified example).
6. **Dataset v1:** `python -m praxis_train.build_dataset6 --out data/phase6_v1 --name praxis-phase6-v1 --teacher <composite>,<composite_retry>,<prose>,<prose_retry>`.
7. **Terminate the pod.**

Result (2026-09-27): composite 2,102 / 2,625 and prose 3,968 / 4,674 passed after
one retry, ≈ ₹170 of generation. The prompt history, v1 → v3, is at the top of
`prompts/teacher_v3.md`.

## Two rules these modules follow

**A target may only state what its own evidence block supports.** A Lichess theme
tag is data, not a verified claim, so `render.py` names a mechanism ("forks",
"pins") only where python-chess can confirm the geometry, and otherwise falls
back to a sentence about what the line demonstrably does. The first draft did
not do this, and produced targets calling a knight check a pin and listing the
enemy king as a capture target — which would have trained the model to do
exactly what the baseline caught the shipped system doing.

**Measure the same things the baseline measured.** `stock_opening_rate` in
`build_dataset.py` and `spike_eval.py` is computed the way
`reports/baseline.md` computes it (95.1% of shipped explanations open with the
same five words), so a target set can be checked against the habit it is meant
to remove rather than accidentally teaching it.

## Licensing

The Lichess puzzle export is CC0. `lichess-puzzler`, the AGPL tool that produced
the theme tags, is never vendored here — only the CC0 data it emitted. Derived
datasets are published CC-BY-4.0; see §13 of the plan.

`data/` and `outputs/` are gitignored, along with `*.gguf` and `*.safetensors`.
