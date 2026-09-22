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
