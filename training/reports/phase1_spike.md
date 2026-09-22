# Phase 1 — toolchain spike on Qwen3.5-0.8B

Run 2026-09-23 on the RTX 3050 Laptop (4 GB), WSL2 Ubuntu 24.04.
Phase 1 of `LORA_IMPLEMENTATION_PLAN.md`.

**Verdict: go on Qwen3.5.** The whole path runs on this machine — CC0 puzzles →
stub evidence → template targets → bf16 LoRA → merge → GGUF → Ollama → answers
on held-out positions. Six things had to be worked around to get there; all six
are one-line fixes once known, and they are listed below because the next person
to hit them will otherwise lose the same day.

## What was built

| | |
|---|---|
| Base | `Qwen/Qwen3.5-0.8B` (Apache-2.0, 752M params, hybrid attention) |
| Data | 197 rows from 200 CC0 Lichess puzzles — 178 train, 19 val |
| Adapter | LoRA r=16, **10,822,656 trainable params (1.42%)** |
| Training | 3 epochs, 69 steps, **7m 18s**, peak **2.7 GB** VRAM |
| Loss | train 0.845, eval **0.591** |
| Export | merged fp16 → GGUF **q8_0, 811.8 MB**, 28.6 s |
| Serving | Ollama 0.23.1, **100% GPU**, 1.7 GB resident |

## Results on the 19 held-out positions

Two floor conditions, both taken from `reports/baseline.md` so the numbers line
up with what ships today. Neither is a quality score — a model that says
"Nf3 is bad" every time would pass both. They are the conditions under which
anything else about an answer is worth reading.

| | praxis-phase1 (0.8B, tuned) | qwen2.5:7b (what ships) | qwen3.5:0.8b (base, prompted) |
|---|---|---|---|
| **Invented squares** | **0%** | **57.9%** | — |
| Cites a square from the evidence | 73.7% | 100% | 0% |
| Same opening five words | 5.3% | 5.3% | — |
| Median latency | **2.72 s** | 6.96 s | 3.87 s |
| Mean length | 77 chars | 197 chars | 0 |
| Empty answers | 0 | 0 | **19 / 19** |

"Invented squares" counts an answer naming a square that appears nowhere in its
own evidence block. The shipped 7B does it in more than half of these cases,
given evidence that contains the answer:

> **Evidence:** `Ne6+ (check)`, attacked: queen on c7 (undefended), lost in the
> line: queen on c7 (`Nxc7`), outcome: 9 points.
>
> **qwen2.5:7b:** "…attack the undefended queen on c7, **pinning it against the
> king on g8**, and capturing it with Nxc7. The attacked squares are c7 (queen)
> and **g7 (bishop, though defended)**."
>
> **praxis-phase1:** "Rb8 leaves the queen on c7 (undefended), and Ne6+ takes it:
> the queen on c7 falls to Nxc7 for 9 points."

g8 and the g7 bishop are not in the evidence. This is the same failure the
baseline caught in production (`Bg5` described as "a fork on f6"), reproduced
here on controlled inputs where it can be counted.

**The base 0.8B is unusable prompted.** Qwen3.5 is a thinking model: all 19
answers were empty because the `<think>` block consumed the token budget before
any answer began. Fine-tuning removed the thinking behaviour entirely — which is
a result about the model family, not about this dataset.

## Read this before believing the table

- **The targets are templates, so the model is partly learning to reproduce
  templates.** Three of the five sampled answers match their target word for
  word. That is what 178 rows over 8 sentence shapes produces, and it is fine for
  a toolchain spike — but "0% invented squares" here means "it copies a format
  that never invents", not "it reasons carefully". The teacher model (Phase 4)
  and the verifier (Phase 3) are what turn this into a real claim.
- **The evidence is stub-derived**, from python-chess and the puzzle's own
  solution line — no engine, no threat probe, no counterfactual, no depth curve.
  The real graph is Phase 2.
- **19 validation rows.** No confidence intervals, and one row moves a rate by
  5 points. Do not read 73.7% against 100% as a ranking.
- **The 7B is prompted, not tuned.** The comparison shows the failure mode is
  real and countable on identical inputs, not that 0.8B beats 7B in general.

## The six things that had to be worked around

Each of these presents as something else, which is why they are written down.

1. **Qwen3.5 is a hybrid, and the standard LoRA target list misses most of it.**
   Of 24 layers, 6 carry classic `self_attn` (`q/k/v/o_proj`) and 18 carry
   `linear_attn` (`in_proj_qkv/z/b/a`, `out_proj`). Every Qwen2/Qwen3 recipe
   targets `q,k,v,o,gate,up,down` — which here adapts the attention of a quarter
   of the stack and silently skips the rest. Nothing errors; the run just learns
   less. Both families are listed in `config/dev_0p8b.yaml`.

2. **Triton needs a C compiler, and a stock WSL2 image has none.** It fails at
   the first forward pass — after the model loads and the dataset is tokenised —
   with `Failed to find C compiler`, which reads like a CUDA problem. There are
   two independent Triton users here: the Hub kernel for gated-delta-net, and
   torch 2.14's own `_native` op router. Disabling the first (`USE_HUB_KERNELS=NO`)
   does not save you from the second.

3. **With no root, the compiler came from a wheel.** `ziglang` provides a
   hermetic clang; `tools/zig-cc.sh` presents it as `cc`. One translation is
   needed: Triton links CUDA as `-l:libcuda.so.1`, GNU ld's exact-filename form,
   which zig's lld rejects — and under WSL there is no `libcuda.so` symlink to
   fall back on, so the shim resolves `-l:NAME` to an absolute path.

4. **The venv needs Python dev headers.** Ubuntu's system python3.12 ships none,
   so Triton compiles nothing (`Python.h` not found) even with a working
   compiler. A uv-managed CPython includes them; rebuilding the venv on one fixed
   it.

5. **`convert_hf_to_gguf.py` needs `--no-mtp` for this checkpoint.** Without it
   the converter assumes a multi-token-prediction block, writes
   `block_count = 25` for a 24-layer model and a `recurrent_layers` pattern with
   a trailing entry. llama.cpp tolerates the result. Ollama refuses it with
   `qwen3next: layer 24 missing attn_qkv/attn_gate projections`, which reads like
   a corrupt export rather than one extra layer that was never asked for.

6. **The Ollama template must be pinned, or every answer comes back empty.**
   Qwen3.5's own chat template opens a `<think>` block; TRL trained on the
   non-thinking form, where the block is opened and closed empty before the
   answer. Inheriting the GGUF template asks the model to continue from a prefix
   it never saw, and it emits its stop token immediately — one token, no error,
   empty string. The `TEMPLATE` block in the generated Modelfile reproduces the
   training format exactly, and there is deliberately no `SYSTEM` line, because
   the training rows are a bare user turn and the instruction lives in the
   rendered evidence block.

## Deviations from the plan

- **transformers + PEFT + TRL, not Unsloth.** Phase 1 decides whether *Qwen3.5*
  works; blocking that on a third-party kernel package's support for a new
  architecture answers a different question. The `unsloth` extra is declared in
  `pyproject.toml` for the RunPod runs.
- **q8_0, not q4_K_M.** `convert_hf_to_gguf.py` writes f32/f16/bf16/q8_0
  directly; anything else needs the compiled `llama-quantize`, and this box has
  no C++ build. At 0.8B, q8_0 is 811 MB and sits entirely in 4 GB of VRAM, so the
  spike loses nothing. The real runs quantise where the toolchain exists.
- **The prompt is rendered in Python, not by the backend.** §17.3 has the backend
  own rendering so training and production cannot drift. There is no graph in the
  backend yet, so Phase 1 renders locally in modules small enough to delete.
- **Training ran on the torch fallback for linear attention.** The compiler
  arrived after the decision, so `USE_HUB_KERNELS=NO` was set for the run.
  transformers' own source calls the fallback more than an order of magnitude
  slower than the fused kernel on an H100. 7m 18s for 178 rows either way, but
  worth re-checking before anything larger.

## Moved to Phase 2

Both remaining items need the backend to render an evidence block for a real
game, and both therefore need `ThreatProbe` — the primitive that answers "what
does the opponent do after this move". Building a throwaway version of it here
would have meant writing it twice, so they are now Phase 2 exit criteria.

- **One game re-diagnosed on screen.** `praxis-phase1` is already exported and
  loaded in Ollama; only the block is missing. Watch the mismatch: this model's
  `Reply` field is the opponent's refutation **after** the blunder, while the
  pipeline stores the engine's best move **instead of** it. Different positions,
  and feeding one where the model expects the other fails silently.
- **Per-mistake engine time** is exactly what that second engine call costs, and
  `analysis_timing` records it as soon as any analysis runs.

## Reproducing

```bash
python -m praxis_train.build_dataset --out data/phase1 --count 200
python -m praxis_train.train_sft     --config config/dev_0p8b.yaml
python -m praxis_train.export        --config config/dev_0p8b.yaml
ollama create praxis-phase1 -f outputs/Modelfile
python -m praxis_train.spike_eval --model praxis-phase1 --compare qwen2.5:7b
```

See `README.md` for the environment those commands assume.
