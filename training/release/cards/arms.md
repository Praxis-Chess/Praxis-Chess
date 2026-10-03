---
license: apache-2.0
library_name: peft
pipeline_tag: text-generation
language:
- en
tags:
- chess
- lora
- ablation
- research
- evidence-grounded
---

# Praxis Chess LoRA v1: the comparison arms

> **Research artifacts, not models to use.** For explaining chess mistakes,
> use [praxis-chess-reasoner-qwen3.5-2b-lora](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-2b-lora)
> or the [4B](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-4b-lora).

The study asked one question: does a small model explain chess mistakes more
truthfully when it is given structured evidence? Every model below is the same
base and recipe as the released R3 adapters; **only the input changes.** They
are published so that every row of the results table can be reproduced, not
only the winning one.

| Folder | Base | Input it was trained and tested on | Claim precision, public test (95% CI) | Chain validity (95% CI) |
|---|---|---|---|---|
{{arm_rows}}
| *released* 2B-R3 | Qwen3.5-2B | R3: the full evidence graph, with item IDs | {{pub_2b_r3_cp}}% | {{pub_2b_r3_cv}}% |
| *released* 4B-R3 | Qwen3.5-4B | R3 | {{pub_4b_r3_cp}}% | {{pub_4b_r3_cv}}% |

- **R0:** the position (FEN), the player's side and the move played.
- **R1:** R0 plus the engine's best move, the evaluation before and after,
  and the engine line after the played move.
- **R2:** R1 plus a flat list of what the move changed: what it attacks,
  pieces left loose or without a defender, lines opened or closed. No IDs.
- **R3:** R2's facts plus what only the graph holds: the threat before the
  move, the counterfactual after the best move, and how deep the engine had to
  look, as typed nodes with IDs that a claim can cite.

Each folder holds the adapter (`adapter/`), the merged q4_K_M GGUF the numbers
were measured with, its `Modelfile`, the training configuration and the
checkpoint-selection scores. Reproduce any row with the Praxis repo:

```bash
python reproduce.py run --model 2b-r0      # or 2b-r1, 2b-r2, 4b-r0
```

The five 2B-R3 ablations (each trained with one evidence component removed,
for H5) were evaluated only on the author's private games, so they are not
published here.

Training: Qwen3.5-2B (revision `{{base_2b_revision}}`) or Qwen3.5-4B (revision
`{{base_4b_revision}}`), LoRA r = 16, alpha = 32, two epochs, learning rate
2e-4, answer tokens only, one NVIDIA A40. The released adapters' cards have the
full method, the results on the author's games and the limitations.

Licence: Apache-2.0, like the base models.
