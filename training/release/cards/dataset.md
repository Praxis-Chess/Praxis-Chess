---
license: cc-by-4.0
language:
- en
task_categories:
- text-generation
tags:
- chess
- reasoning
- evidence-grounded
- verifiable
- stockfish
- lichess
pretty_name: Praxis Chess Evidence Graphs
size_categories:
- 1K<n<10K
configs:
- config_name: R3
  default: true
  data_files:
  - split: train
    path: data/R3/train.jsonl
  - split: validation
    path: data/R3/validation.jsonl
- config_name: R2
  data_files:
  - split: train
    path: data/R2/train.jsonl
  - split: validation
    path: data/R2/validation.jsonl
- config_name: R1
  data_files:
  - split: train
    path: data/R1/train.jsonl
  - split: validation
    path: data/R1/validation.jsonl
- config_name: R0
  data_files:
  - split: train
    path: data/R0/train.jsonl
  - split: validation
    path: data/R0/validation.jsonl
- config_name: graphs
  data_files:
  - split: train
    path: data/graphs/train.jsonl
  - split: validation
    path: data/graphs/validation.jsonl
- config_name: test
  data_files:
  - split: test
    path: data/test/test.jsonl
- config_name: public_test_answers
  data_files:
  - split: test
    path: results/verified.jsonl
---

# Praxis Chess Evidence Graphs

> **v1.0, waiting for an independent reproduction.** Built for, and used to
> train and test,
> [praxis-chess-reasoner-qwen3.5-2b-lora](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-2b-lora)
> and its [4B sibling](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-4b-lora).

Chess mistakes from public Lichess data, each with an **evidence graph** of
engine-computed facts about the move, and a **verified explanation**: a typed
reasoning chain that a program can check claim by claim, the labels (what
happened, and why) and two or three sentences of prose.

The dataset exists to test one question: does a small model explain mistakes
more truthfully when it is given structured evidence? Every row is rendered
four ways, from the bare board (R0) to the full graph (R3), and **the same
target answer is paired with all four**, so the input is the only thing that
changes between the training sets.

## What is in it

| Path | Rows | What |
|---|---|---|
| `data/R0` … `data/R3` / `train.jsonl` | {{n_train}} | Training rows, chat format, at each rendering |
| `data/R0` … `data/R3` / `validation.jsonl` | {{n_val}} | Validation rows, uncapped (checkpoint selection used the first 200) |
| `data/graphs/train.jsonl`, `validation.jsonl` | {{n_train}} / {{n_val}} | The evidence graph and the rules' labels behind each training and validation row, by `id` (= `meta.source_id`) |
| `data/test/test.jsonl` | {{n_test}} | The public test set: graph, every rendering, the rules' labels |
| `results/outputs.jsonl` | {{n_outputs}} | Every model answer behind the published table, as generated |
| `results/verified.jsonl` | {{n_verified}} | Those answers with the checker's verdict, plus the rules' own diagnosis of each item |
| `results/public_test_v1.json`, `.md` | | The published table, with 95% intervals |
| `schema/graph_schema.json` | | JSON Schema of the evidence graph (version 2) |
| `schema/answer_schema.json` | | The answer's JSON schema, claim types and their required arguments |
| `verifier/verifier_test_vectors.jsonl.gz` | {{n_vectors}} | Graph + answer + the Java checker's verdict, for porting the checker |
| `manifest.json` | | Counts, provenance, and the sha256 of every file |

### The four renderings

The user message a model sees. Each level contains the one below it.

- **R0:** the position (FEN), the player's side and the move played.
- **R1:** R0 plus the engine's best move, the evaluation before and after,
  and the engine line after the played move.
- **R2:** R1 plus a flat list of what the move changed: what it attacks,
  pieces left loose or without a defender, lines opened or closed.
- **R3:** the evidence graph: everything above plus the opponent's threat
  before the move (a null-move probe), the counterfactual (does the punishing
  reply still work after the best move?) and how deep the engine had to look,
  as items with IDs (`P0`, `R1`, `CF1`, `T1`, `Δ1`, `D1`, …) that a claim can
  cite.

### A training row

```json
{"messages": [{"role": "system", "content": "{{system_prompt}}"},
              {"role": "user", "content": "<the rendering>"},
              {"role": "assistant", "content": "<the target answer, JSON>"}],
 "meta": {"slice": "A", "source_id": "lichess:puzzle:…", "R": "R3", "consequence": "LOST_MATERIAL",
          "mechanism": "IGNORED_THREAT", "rules_fired": ["IGNORED_THREAT"], "composite": false,
          "teacher": "…", "verified": true, "tokens_in": 471, "…": "…"}}
```

The answer is JSON: `reasoning_chain` (at most 7 typed claims, each with
`type`, `args`, `cites` and `text`), then `consequence`, `mechanism`, `motif`,
`critical_response`, `visibility` and `explanation`. The claim types and
their arguments are in `schema/answer_schema.json`.

### A test item

`id`, `slice`, `severity`, `fen_key`, `graph` (the full evidence graph),
`renders` (R0–R3 plus the four ablated R3 renderings used in the study:
R3-CF, R3-T, R3-DELTA, R3-D, each with one component removed, and a token
count for each) and `rules` (the rules' labels: consequence, mechanism, which
rules fired, single-cause or composite, motif, visibility).

## How it was built

1. **Sources** (CC0):
   - **Slice A, puzzles:** {{a_total}} positions from the
     [Lichess puzzle database](https://database.lichess.org/#puzzles), chosen
     with per-mechanism quotas.
   - **Slice B, games:** {{b_total}} mistakes from rated rapid and blitz games
     between players rated 1000–1600, from the
     [June 2024 Lichess export](https://database.lichess.org/). They were
     found by Praxis's own analysis pipeline, as the app would flag them.
     Player names, ratings, dates and links were removed before analysis. Each
     game is known only by a hash of its URL.
2. **Evidence:** for each mistake, Praxis's `EvidenceGraphBuilder` runs
   Stockfish (deterministic: one thread, hash cleared, depth 14) and records
   typed facts, never labels. Graph version 2; backend commit `{{backend_sha}}`.
3. **Labels and targets:** Praxis's rules label every mistake (what happened,
   which mechanism rules fired) and write a verified diagnosis wherever exactly
   one rule fires. A teacher model, `Qwen/Qwen3.5-27B-FP8`, then:
   - **rewrote the prose:** {{teacher_prose_passed}} of {{teacher_prose_total}}
     attempts kept;
   - **answered the mistakes with more than one possible cause:**
     {{teacher_comp_passed}} of {{teacher_comp_total}} attempts passed, and
     {{teacher_comp_added}} entered training and validation after the caps.

   Only answers that passed the checker were kept.
   {{teacher_train}} of the {{n_train}} training targets contain teacher-written
   text; the rest are entirely the rules' own.
4. **Clean-up and split:**
   - one row per position;
   - any position that also appears among the author's private test mistakes
     or the few-shot examples was dropped ({{dropped_private}} rows);
   - the split is by source, so a puzzle or a whole game lands in exactly one
     of train, validation and test;
   - training rows are capped per mechanism and per slice, and "nothing
     concrete" rows are capped at a share, so no single answer dominates;
   - the test set is not capped.

| Split | Rows | Slice A | Slice B | Lost material | Missed material | Mated | Missed mate | Nothing concrete | Composite |
|---|---|---|---|---|---|---|---|---|---|
{{split_rows}}

## Using it

```python
from datasets import load_dataset

train = load_dataset("praxis-chess/praxis-chess-evidence-graphs", "R3", split="train", revision="v1.0")
test = load_dataset("praxis-chess/praxis-chess-evidence-graphs", "test", split="test", revision="v1.0")
```

**Checking answers.** `praxis_eval` (Python, needs only python-chess) is a
port of the Java checker that judged every published number:
- it matches the Java verdict on all 5,866 published answers and on the
  {{n_vectors}} test vectors here, broken answers included;
- **claim mode** checks every claim against the full graph and the board;
- **citation mode** also requires each cited ID to exist and to be about the
  claim's squares.

```python
from praxis_eval import parse_diagnosis, verify

report = verify(item["graph"], parse_diagnosis(model_answer))
report.passed, report.claims_true, report.claims
```

**Reproducing the table.** In the
[Praxis repo](https://github.com/Praxis-Chess/Praxis-Chess/tree/lora-v1.0/training):
- `python reproduce.py score` re-checks the published answers here;
- `python reproduce.py run --model 2b-r3` regenerates them.

See `REPRODUCE.md`.

## Results on the test set

{{public_table}}

## Privacy

Every row comes from public Lichess data. Slice B games were scrubbed before
analysis: no names, ratings, dates or links. The author's own games, which
are the study's primary test set, are **not** in this dataset in any form.
Positions they share with it were removed from training, as described above.

## Limitations

- **Tactics and material only.** Version 1 explains mistakes where material or
  mate changes hands within 8 plies; the rest are labelled "nothing concrete"
  with no cause.
- **The labels are the rules'.** They are hand-written, and they were checked
  against human labels only on the author's games.
- **The teacher's prose is checked, not proofread.** The checker guarantees
  that every move and square it names was earned by a true claim. It does not
  guarantee good style.
- **Narrow source.** Lichess puzzles, and 1000–1600 rapid and blitz games.
  English only.

## Licence

- **This dataset:** CC-BY-4.0, built from CC0 Lichess data.
- **Teacher answers:** written by Qwen3.5-27B-FP8 (Apache-2.0).
- **Engine:** the evidence comes from Stockfish (GPL-3.0), run as a separate
  program.
- **Puzzle themes:** the CC0 Lichess export carries theme tags produced by
  the AGPL `lichess-puzzler`. That tool is not part of this dataset.

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
