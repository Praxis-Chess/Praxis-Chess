---
license: apache-2.0
base_model: {{base}}
base_model_relation: adapter
library_name: peft
pipeline_tag: text-generation
language:
- en
datasets:
- praxis-chess/praxis-chess-evidence-graphs
tags:
- chess
- lora
- peft
- reasoning
- stockfish
- evidence-grounded
- verifiable
- qwen3.5
model-index:
- name: {{repo_name}}
  results:
  - task:
      type: text-generation
      name: Chess mistake diagnosis
    dataset:
      name: Praxis Chess Evidence Graphs (public test, 838 positions)
      type: praxis-chess/praxis-chess-evidence-graphs
      split: test
      revision: v1.0
    metrics:
    - name: Claim precision (%)
      type: claim_precision
      value: {{pub_r3_cp}}
    - name: Chain validity (%)
      type: chain_validity
      value: {{pub_r3_cv}}
---

# Praxis Chess Reasoner: Qwen3.5-{{size}} LoRA (R3)

> **v1.0, waiting for an independent reproduction.** Every number below was
> measured and can be re-checked with [`reproduce.py`](https://github.com/Praxis-Chess/Praxis-Chess/tree/lora-v1.0/training).
> This note goes away once someone other than the author has reproduced the
> table on a clean machine.

A LoRA adapter for [{{base}}](https://huggingface.co/{{base}}) that explains
**why a chess move was a mistake**, using only facts it is handed. The input
is a position, the move played and an **evidence graph**: engine-computed
facts about that move (the opponent's punishing reply, what it wins, what
the best move would have done, what the move left undefended). The output is
JSON: a **typed reasoning chain** whose every claim names its evidence, the
labels (what happened, why), and two or three sentences of explanation.

Each claim has a fixed type with fixed arguments (`CRITICAL_REPLY {move: "Qxf7#"}`,
`MATERIAL_CHANGE {amount: 3}`), so a program can check it. The checker,
[`praxis_eval`](https://github.com/Praxis-Chess/Praxis-Chess/tree/lora-v1.0/training/src/praxis_eval),
marks an answer valid only when every claim is true of the graph and the
board, the labels agree with the rules, and the prose names no move the
chain did not earn.

**What it is not.** Not a chess engine: it does not find moves or evaluate
positions. It interprets engine evidence it is given, and it needs that
evidence. Without it (R0 below) it gets {{pub_r0_cp}}% of its claims right.

Part of [Praxis Chess](https://github.com/Praxis-Chess/Praxis-Chess), a
chess-improvement app. Everything in this release, from the dataset to the
comparison models, is at [huggingface.co/praxis-chess]({{collection_url}}).

## Results on the public test set

838 held-out positions from Lichess (236 puzzles, 602 mistakes from rated
rapid and blitz games between 1000 and 1600 players), never seen in training.
Every answer was checked claim by claim. Same base model, same data, same
recipe; only the input changes from R0 to R3.

| System | Input | Claim precision (95% CI) | Chain validity (95% CI) | Names the rules' cause | Invents a cause |
|---|---|---|---|---|---|
{{pub_rows}}

- **Claim precision:** of all the claims made, the share that are true of the
  graph and the board.
- **Chain validity:** the share of answers that pass every check at once.
- **Names the rules' cause:** on the {{pub_sc_n}} positions where exactly one
  rule fires, how often the answer names that cause.
- **Invents a cause:** on the {{pub_nc_n}} positions where nothing concrete
  happens (no material or mate changes hands within 8 plies), how often the
  model names a cause anyway. The right answer there is "NOT_CONCRETE, no
  cause".
- Rules: Praxis's hand-written rules over the same graph. They always pass
  their own checks, and name no cause unless exactly one rule fires.

Intervals are 95% bootstrap intervals over positions (1,000 resamples,
seed 0). Answers came from the q4_K_M GGUF in Ollama, greedy, with the answer
JSON schema enforced. Latency: {{pub_r3_latency}} s median per answer on a
4 GB laptop GPU (RTX 3050).

## Was it good enough to ship? No, by our own rules

Before training, the study fixed four ship rules
([`PREREGISTRATION.md`](https://github.com/Praxis-Chess/Praxis-Chess/blob/lora-v1.0/training/PREREGISTRATION.md),
tagged `prereg-v1`). They were judged on the author's own games, below.

| Rule | Result |
|---|---|
{{ship_rows}}

The Phase 8 decision was **"ship the rules."** In Praxis, the rules stay the
diagnosis. {{app_use}}

## Results on the author's own games (aggregate only)

The primary test set, T_C, is 890 mistakes from the author's own Chess.com
games. They are private: no position, answer or label from it is published,
only these totals. Prompted rows are the base model with three worked
examples and no training. The prompted 4B rows cover a fixed sample of 300
positions, because of laptop time.

| System | Items | Claim precision (95% CI) | Chain validity (95% CI) | Names the rules' cause | Invents a cause |
|---|---|---|---|---|---|
{{tc_rows}}

What the study found (2B models unless stated; on T_C, paired):

- **H1, supported.** Training on the evidence graph (R3) beats training on
  the board alone (R0) by {{h1_r3_r0}} points of claim precision, and beats
  engine facts alone (R1) by {{h1_r3_r1}}. With prompt length held level, the
  gain is still {{h1_len}} points (n = {{h1_len_n}}), so it is not just a
  longer prompt.
- **H2, supported.** Training adds more where the graph is: {{h2_gain_r3}}
  points of claim precision over the prompted model at R3, {{h2_gain_r0}} at R0.
- **H3, supported (marginally).** The 4B's edge over the 2B in claim
  precision is {{h3_gap_r0}} points on the board alone and {{h3_gap_r3}} with
  the graph: the evidence does the work a bigger model would. (The 4B still
  passes more whole chains; see the tables.)
- **H4, not supported.** On the {{h4_n}} mistakes with more than one possible
  cause (author's games and the public set pooled), where the rules name none,
  2B-R3 names a cause in {{h4_named}} answers, and {{h4_passed}} of those pass
  the checker ({{h4_precision}}%; the bar was 80%).
- **H5, refuted.** We predicted the counterfactual (CF1) mattered most. Among
  2B-R3 models trained with one component removed, the one without the threat
  probe (T1) names the rules' cause least often ({{h5_not}}%, against
  {{h5_others}} without any other): the threat probe carries the diagnosis.

Details: [the write-up](https://github.com/Praxis-Chess/Praxis-Chess/blob/lora-v1.0/training/reports/writeup_v1.md).

## How to use it

The adapter reads the **R3 rendering** of an evidence graph: plain text with
item IDs (`P0`, `R1`, `CF1`, `T1`, `Δ1`, …). Rendered inputs for every public
test position are in the
[dataset](https://huggingface.co/datasets/praxis-chess/praxis-chess-evidence-graphs)
(`renders.R3.text`). For your own games, Praxis's headless `GraphCli` builds
the graph and its R3 rendering (`--level R3`) from a FEN, a move and a
Stockfish binary.

**Transformers + PEFT** (bf16, a CUDA GPU; this is how the adapter was
trained and how the quantisation check ran it):

```python
import json
import torch
from huggingface_hub import hf_hub_download
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

repo = "praxis-chess/{{repo_name}}"
tok = AutoTokenizer.from_pretrained(repo, revision="v1.0")
base = AutoModelForCausalLM.from_pretrained("{{base}}", revision="{{base_revision}}",
                                            dtype=torch.bfloat16, device_map="auto")
model = PeftModel.from_pretrained(base, repo, revision="v1.0").eval()

# One public test position, already rendered at R3.
test = hf_hub_download("praxis-chess/praxis-chess-evidence-graphs", "data/test/test.jsonl",
                       repo_type="dataset", revision="v1.0")
item = json.loads(open(test, encoding="utf-8").readline())

messages = [{"role": "system", "content": "{{system_prompt}}"},
            {"role": "user", "content": item["renders"]["R3"]["text"]}]
prompt = tok.apply_chat_template(messages, tokenize=False, add_generation_prompt=True, enable_thinking=False)
ids = tok(prompt, return_tensors="pt", add_special_tokens=False).to(model.device)
out = model.generate(**ids, max_new_tokens=1000, do_sample=False)
answer = tok.decode(out[0, ids["input_ids"].shape[1]:], skip_special_tokens=True).strip()
```

The adapter was trained **non-thinking**: keep `enable_thinking=False` so the
thinking block is closed before the answer starts.

**Check the answer** (pip install python-chess; `praxis_eval` is in the
Praxis repo under `training/src`):

```python
from praxis_eval import parse_diagnosis, verify

report = verify(item["graph"], parse_diagnosis(answer))
print(report.passed, f"{report.claims_true}/{report.claims} claims true")
for v in report.violations:
    print(f"rule {v.rule}: {v.message}")
```

Show an answer only if it passes. That is how Praxis uses this model, and it
is the reason the claims are typed.

**Ollama / llama.cpp:** use the q4_K_M GGUF at
[praxis-chess/{{gguf_repo_name}}](https://huggingface.co/praxis-chess/{{gguf_repo_name}}).

**Reproduce the table:** `python reproduce.py run --model {{size_lower}}-r3`
in the Praxis repo's `training/` folder, or `python reproduce.py score` to
re-check the published answers without a GPU.

## Training

| | |
|---|---|
| Base | [{{base}}](https://huggingface.co/{{base}}) at revision `{{base_revision}}` (Apache-2.0 at that revision), loaded text-only through `AutoModelForCausalLM` |
| Method | LoRA, r = {{lora_r}}, alpha = {{lora_alpha}}, dropout 0, on every attention, linear-attention and MLP projection: `{{target_modules}}` |
| Loss | Answer tokens only (prompt masked), bf16, non-thinking format |
| Schedule | {{epochs}} epochs, learning rate 2e-4 with cosine decay and 5% warm-up, effective batch 8 ({{batch_note}}), max length 1,536 tokens, seed 17 |
| Checkpoint | The epoch with the higher claim precision on 200 validation rows, decided before training: {{selected}} |
| Data | {{train_rows}} training rows, R3 rendering ([dataset card](https://huggingface.co/datasets/praxis-chess/praxis-chess-evidence-graphs)) |
| Compute | One NVIDIA A40, {{train_minutes}} minutes; trainable parameters {{trainable_params}} |
| Software | torch {{v_torch}}, transformers {{v_transformers}}, peft {{v_peft}}, trl {{v_trl}} |

The training targets are the rules' own diagnoses, with the prose (and, for
mistakes with more than one cause, the whole answer) rewritten by a larger
teacher model, Qwen3.5-27B-FP8. A teacher answer was kept only if it passed
the same checker. `training_config.yaml` and `run_manifest.json` in this repo
record everything needed to train it again.

**Quantisation.** {{quant_sentence}}

## Limitations

- **Tactics and material only.** Version 1 explains mistakes where material or
  mate changes hands within 8 plies. Positional mistakes should get
  "NOT_CONCRETE" and no cause. It still names one on {{pub_r3_invent}}% of
  them in the public set.
- **About one answer in {{one_in}} fails a check** (chain validity
  {{pub_r3_cv}}%). Never show an answer the checker has not passed.
- **True claims can still tell the wrong story.** The checker catches false
  claims, not a misleading choice of true ones. Rules 3 and 4 cover the common
  cases; the rest needs human review. Human labels here are few (n = 33 fresh
  labels, all on the author's games).
- **The input format is Praxis's.** It needs an evidence graph, which needs
  Praxis's graph builder and Stockfish.
- **Narrow data.** Lichess puzzles and 1000–1600 rapid and blitz games, in
  English. Other levels and time controls are untested.
- **Scope of the measurement.** Measured on the q4_K_M GGUF with the answer
  schema enforced. The bf16 adapter without a schema was measured on 300
  positions only.

## Files

| File | What |
|---|---|
| `adapter_model.safetensors`, `adapter_config.json` | The LoRA adapter (PEFT {{v_peft}}) |
| `tokenizer.json`, `tokenizer_config.json`, `chat_template.jinja` | The base tokenizer and chat template, as trained |
| `training_config.yaml` | The training configuration, base revision pinned |
| `run_manifest.json` | Provenance: base and dataset revisions, git commits, versions, file hashes, validation scores |

## Licence and attribution

- **This adapter:** Apache-2.0.
- **Base model:** {{base}} by the Qwen team, also Apache-2.0.
- **Training data:** the Praxis Chess Evidence Graphs dataset, CC-BY-4.0. It
  is built from Lichess's CC0 puzzle and game exports.
- **Teacher:** Qwen3.5-27B-FP8, Apache-2.0.
- **Engine:** Stockfish (GPL-3.0) produced the evidence, run as a separate
  program.

## Citation

```bibtex
@misc{praxis_lora_v1_2026,
  title  = {Does structured evidence let a small model explain chess mistakes truthfully?},
  author = {Anand, Tanmay},
  year   = {2026},
  note   = {Praxis Chess, LoRA v1 research write-up},
  url    = {https://github.com/Praxis-Chess/Praxis-Chess}
}
```
