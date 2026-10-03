# Does structured evidence let a small model explain chess mistakes truthfully?

**Praxis Chess · LoRA v1 · research write-up · 3 October 2026**

Every number here comes from `reports/grid_v1.md` (generated 1 October 2026 by
`grid_report.py`) and is defined in [`PREREGISTRATION.md`](../PREREGISTRATION.md),
tagged `prereg-v1` before any 2B or 4B model was trained. Intervals are 95%
paired bootstrap intervals (1,000 resamples over items, seed 0).

## Summary

A chess improvement app needs to say *why* a move was a mistake. Free-text
explanations from a local 7B model were wrong most of the time: 9% of their
checkable claims were true. Praxis replaced them with a pipeline that computes
the facts first and checks every sentence afterwards:

1. **Stockfish evidence**, organised as a graph.
2. **A typed answer** whose claims cite that evidence.
3. **A verifier** that checks each claim against the full graph.

This write-up asks whether a small model, fine-tuned with LoRA, can read that
evidence well enough to replace the hand-written rules that currently produce
the diagnosis.

- **Structure, not size, made the difference.** Trained on the full evidence
  graph (R3), Qwen3.5-2B states **99.7% true claims** (99.5–99.9) on 890 of the
  author's own mistakes. The same model prompted with the same evidence states
  50%; trained on the bare board, 32%.
- **A flat fact list added nothing over engine numbers.** R2 − R1 was −1.0
  points; the gain arrives with the graph's threat, counterfactual and depth
  nodes (R3 − R2 +45.9 points).
- **The threat probe carries the diagnosis, not the counterfactual.** The
  pre-registered prediction (H5) was refuted.
- **The pre-registered decision is "ship the rules".** The model meets the
  precision and abstention rules but not non-inferiority on fresh human labels
  (33 labels, interval −18 to +12 points) and not the composite-mistake rule
  (H4). The rules keep the verified headline in the app; the trained 2B now
  writes the checked commentary beside it, at 4.6 s per answer on a 4 GB
  laptop GPU.

## 1. The question

*Can a small language model, given structured engine evidence, state only true
things about why a chess move was bad, and do it better than hand-written
rules?*

Five hypotheses were registered before training:

| | Hypothesis |
|---|---|
| H1 | Claim correctness rises with evidence: R0 < R1 < R2 < R3 |
| H2 | The LoRA gain over prompting is largest at R3 |
| H3 | The 2B–4B gap is smaller at R3 than at R0 (evidence substitutes for scale) |
| H4 | On composite mistakes, where the rules abstain, the model names a cause with acceptable precision |
| H5 | Removing the counterfactual hurts mechanism accuracy more than removing any other component |

## 2. The system being tested

- **Evidence graph.** For each mistake, `EvidenceGraphBuilder` runs Stockfish
  (deterministic: one thread, hash cleared, depth 14) and records typed facts:
  - the position before the move, the best and played moves and their win
    percentages;
  - the consequence and the critical reply with its line;
  - the counterfactual: what that reply does after the best move (`CF1`);
  - a null-move threat probe: what the opponent already threatened (`T1`);
  - the depth at which the move first looked losing (`D1`);
  - what the move changed (`Δ`), geometry (`G`), and the effects of each move.
- **Four renderings** of the same graph, each strictly containing the one below:

  | Level | The model is shown |
  |---|---|
  | R0 | FEN and the played move |
  | R1 | + engine best move, evaluation change, engine line |
  | R2 | + flat facts (loose pieces, attacks, lines) |
  | R3 | + the full graph, one `[ID] text` line per node, with citable IDs |

  R3 is capped at 40 items and about 900 tokens by dropping low-priority items
  first (`GraphBudget`); the threat, counterfactual and depth nodes are never
  dropped.
- **Typed answer.** A reasoning chain of claims (`THREAT_EXISTS`,
  `COUNTERFACTUAL`, `MATERIAL_CHANGE`, …), each with arguments and citations,
  then consequence, mechanism, motif, critical response, visibility and a short
  explanation.
- **Verifier.** `DiagnosisVerifier` checks every claim's arguments against the
  **full** graph, whatever the model was shown. That is what makes R0–R3
  comparable: citation validity would favour R3 by construction, so the
  headline is claim correctness.
- **Rules.** `DiagnosisRules` writes a diagnosis for every single-cause mistake
  and abstains (`UNCLEAR`) on composites. Against the author's 100 hand labels,
  the rules agree on consequence 79% of the time and on mechanism 68%.

## 3. Data

**The author's games are test data only.** Training data comes from public
Lichess data (CC0):

- **Slice A:** 5,000 puzzles drawn with per-theme quotas from about 271,000
  eligible.
- **Slice B:** 800 public games between players rated 1000–1600, scrubbed of
  names and dates and analysed with the app's own pipeline: 14,410 mistakes.

The dataset builder (`build_dataset6.py`):
- drops any position that also occurs among the author's test mistakes;
- deduplicates positions and splits by source, so no puzzle or game straddles
  train and test;
- caps each mechanism per slice, and "nothing concrete" at a share of training;
- pairs the **same target** with R0, R1, R2 and R3, so the evidence level is
  the only thing that varies between training sets.

**Targets.** Single-cause rows use the rules' verified diagnosis. A teacher,
Qwen3.5-27B-FP8 served with vLLM on a rented 48 GB GPU, wrote the rest: prose
for rule diagnoses, and full chains for composite mistakes. Only answers that
passed the verifier were kept: 2,102 of 2,625 composite answers (80%) and 3,968
of 4,674 prose answers (85%), after one retry, for about ₹170 of generation.

| Split (`praxis-phase6-v1`) | Rows | Teacher targets |
|---|---|---|
| Train | 7,057 (slice A 2,549, B 4,508) | 5,087 |
| Validation | 1,121 | 584 |
| T_AB (held-out public test) | 838 | — |

**Test sets.** T_C is 890 of the author's mistakes (416 single-cause, 392
"nothing concrete", 82 composite), 100 of them hand-labelled. T_AB is the 838
held-out public positions, used for replication.

## 4. Training

- **Models:** Qwen3.5-2B and Qwen3.5-4B, bf16 LoRA, rank 16, alpha 32, on the
  attention, linear-attention and MLP projections; vision frozen; non-thinking
  chat format.
- **Recipe:** 2 epochs, learning rate 2e-4 with cosine decay, sequences up to
  1,536 tokens, **loss on the answer only**.
- **Checkpoint choice:** the epoch with the higher verifier claim precision on
  the first 200 validation rows, never by loss. Ties go to epoch 2.
- **Export:** merged, converted to GGUF, quantised to q4_K_M, served by Ollama
  with the training template pinned.
- **The grid, 11 arms:**
  - 2B at R0, R1, R2, R3;
  - 4B at R0 and R3;
  - 2B-R3 with one component removed: −CF, −T, −Δ, −D (`ablate.py` deletes
    those lines from every render, in training and in test);
  - 2B-R3 at rank 32.
- **Cost:** one rented A40 (48 GB), 15.1 GPU-hours, about ₹740. A 2B arm took
  52–78 minutes; a 4B arm 1.9–2.7 hours.

## 5. Results

### Every system on T_C (890 of the author's mistakes)

| System | Claim precision | Chain valid | Mechanism = rules | Invents a cause | p50 latency |
|---|---|---|---|---|---|
| Rules (`S_rules`) | 100% | 100% | 100% | 0% | — |
| Prompted 2B, R0 | 8% (7–8) | 0% | 22% | 98% | 15.6 s |
| Prompted 2B, R3 | 50% (49–52) | 1% | 15% | 48% | 13.6 s |
| Prompted 4B, R3 (300-item sample) | 59% (57–62) | 6% | 32% | 45% | 38.1 s |
| **LoRA 2B, R0** | 32% (29–35) | 1% | 0% | 1% | 4.0 s |
| **LoRA 2B, R1** | 55% (53–57) | 14% | 46% | 22% | 5.1 s |
| **LoRA 2B, R2** | 54% (52–56) | 14% | 54% | 26% | 5.1 s |
| **LoRA 2B, R3** | **100% (99.5–99.9)** | **74%** | 65% | **7%** | **4.6 s** |
| LoRA 4B, R0 | 34% (31–37) | 1% | 0% | 0% | 9.0 s |
| LoRA 4B, R3 | 100% (99–100) | 85% | 92% | 16% | 14.6 s |
| LoRA 2B, R3, rank 32 | 100% (100–100) | 88% | 98% | 18% | 5.6 s |

On T_AB, the replication, LoRA 2B-R3 states 100% true claims (100–100), with
78% valid chains and 12% invented causes.

### The hypotheses

| | Verdict | Evidence |
|---|---|---|
| H1 | **Supported** | R3 − R0 +67.5 pts (64–70); R3 − R1 +44.8 pts (43–47). Steps: R0→R1 +22.7 (20–25), R1→R2 −1.0 (−2.5 to +0.4, not significant), R2→R3 +45.9 (44–48) |
| H2 | **Supported** | gain(R3) − gain(R0) +24.8 pts (22–28); − gain(R1) +11.7 (9–14); − gain(R2) +13.4 (11–16) |
| H3 | **Supported, marginally** | gap(R0) − gap(R3) +2.0 pts (0–4) |
| H4 | **Not supported** | 194 composite items (T_C + T_AB): named coverage 14% (Wilson lower bound 10%), named precision 60% (needed ≥ 80%) |
| H5 | **Refuted** | drop(CF) − drop(T) −18.8 pts (−23 to −15); − drop(Δ) +1.7 (0–4); − drop(D) +1.9 (0–4) |

**Length control (§8).** Longer prompts could explain H1 by themselves. Within
the one prompt-length tertile where R1 and R3 both have items, R3 still leads
by +48.5 points (37–61, n = 34), so no length caveat applies. The R3-short arm
was not built, which the pre-registration allows.

### What each removed component costs (H5)

| 2B-R3 arm | Claims true | Chain valid | Mechanism = rules | Invents a cause |
|---|---|---|---|---|
| −CF (no counterfactual) | 98% | 84% | 95% | 15% |
| −T (no threat probe) | 99% | 79% | **76%** | 10% |
| −Δ (no change list) | 99% | 86% | 97% | 18% |
| −D (no depth curve) | **79%** | **31%** | 97% | 16% |

Two components matter, and in different ways:
- **The threat probe carries the mechanism.** Without `[T1]`, the model names
  the rules' cause on 76% of single-cause mistakes; without any other line,
  95–97%. Most real mistakes are a threat left unanswered, and only the null-move
  probe states that the threat existed before the move.
- **The depth line carries the visibility claim.** Without `[D1]`, true claims
  fall to 79% and valid chains to 31%. A likely reason, not separately tested:
  every target ends with a visibility claim whose depth and band only `[D1]`
  supplies.

The counterfactual, the plan's favourite, is the least missed.

### The decision rules

| Rule (2B-R3-LoRA, T_C) | Result |
|---|---|
| (a) claim precision ≥ 95%, lower bound ≥ 93% | **Met:** 99.7% (99.5–99.9) |
| (b) within 5 points of the rules on fresh single-cause labels, n ≥ 30 | **Not met:** −3.0 pts (−18 to +12), n = 33 |
| (c) invented cause ≤ 10% on "nothing concrete" | **Met:** 7% |
| (d) H4 supported | **Not met** |

**Decision: ship the rules.** Rule (b) fails because 33 labels cannot bound
the difference within 5 points, not because the model was shown to be worse.
The 4B-R3 met (a) and (b) (+9.1 pts, 0 to +21) but invented a cause on 16% of
quiet positions, so it fails (c), and (d) as well.

### Quantisation

On 300 T_AB items with no schema on either side, bf16 and q4_K_M state 99.9%
and 99.8% true claims: a difference of −0.1 points (−0.5 to +0.2). The laptop
serves the model at no measurable cost in truthfulness.

## 6. What shipped

The decision table applies as written:
- **The rules write the headline.** Every mistake in the app carries
  `DiagnosisRules`' diagnosis, verified live and shown as "✓ Verified".
- **The trained 2B-R3 writes the commentary.** This is a product choice made
  after the experiment, and it does not alter any verdict. It replaced qwen2.5's
  free text because its claims are true where qwen2.5's were not, and it runs in
  4.6 s.
  - It is asked in its exact training format.
  - The verifier checks every claim in claim mode.
  - An answer with a single false claim is stored and never shown.

On the same stored mistake, the difference is visible
(`reports/images/phase10/`):
- **24.f3, before:** the old text says f3 "allows the opponent to gain a tempo
  with a fork on d2", and the card is tagged FORK.
- **24.f3, after:** "No material or mate changes hands within the horizon; the
  difference is positional."
- **15.Ba3, before:** a blunder with no explanation at all; the per-game budget
  of written explanations had been spent on inaccuracies.
- **15.Ba3, after:** "Before Ba3, Black already threatened Bf6. Ba3 does not
  deal with it: Bf6 follows, and White loses 3 pawns net."

## 7. Disclosures

These are reproduced from `grid_v1_disclosures.md`; none changed a definition.

- **The 4B checkpoint choice was redone before any test answer existed.**
  Qwen3.5-4B's template opens a thinking block by default, and every 4B
  validation answer began "Thinking Process: …", so epoch 2 won only by the tie
  rule. Re-run in the training format, both 4B arms chose epoch 1.
- **2B-R3 chose epoch 1 by 0.13 points** of validation claim precision. That
  epoch names the rules' mechanism less often (65% on T_C) than its epoch-2
  siblings (95–98%). H5 is therefore judged on the ablations against each other,
  not on their drop from the full model.
- **Rule (b) rests on 33 fresh single-cause labels** out of 51 made after the
  tag; 18 were "nothing concrete".
- **The quantisation delta uses T_AB, not T_C**, because the bf16 half ran on
  the rented GPU and the author's games never leave the laptop.
- **The Phase 6 0.8B rehearsal model trained on every token**, because TRL
  ignores `completion_only_loss` for chat-format rows. Found before the grid;
  the grid trains on the answer only, as pre-registered.

## 8. Limitations

- **Tactical and material only.** V1 evidence has nothing positional. On
  "nothing concrete" mistakes, the right answer is to abstain, and the model
  does on 92% of them.
- **An 8-ply horizon.** A consequence that lands later is invisible.
- **One player's games.** T_C is 890 mistakes by one club-level player. T_AB
  replicates the headline on public games, but the human-label comparisons rest
  on 100 + 33 labels by one labeller.
- **True claims can still tell the wrong story.** The verifier checks every
  claim; it cannot check emphasis or what was left out. Chain validity, 74%
  for the shipped arm, is the closest measure of the whole story.
- **No human pairwise preference study** was run. Claims are checked
  mechanically; readability is not measured.
- **The input format is Praxis-specific.** The adapter reads Praxis's evidence
  graph, not arbitrary chess text.

## 9. Lessons beyond chess

1. **Verify against what the system knows, not what the model saw.** Checking
   claims against the full graph is what made four evidence levels comparable.
2. **Structure beats volume.** A flat list of facts added nothing. The same
   facts organised as typed, citable nodes produced the entire gain.
3. **Small models learn formats quickly.** Two epochs on 7,057 rows moved a 2B
   from 50% to 99.7% true claims. Most of the gain is learning to read the
   evidence, not new chess knowledge.
4. **Pre-registration pays when the result is mixed.** The model is very
   accurate, and the rules still won the headline. That call was made before
   training, not after seeing which way it went.
5. **Ablations correct intuitions.** The component the design was built around
   mattered least; the cheapest probe mattered most.

## 10. Reproducing it

- **Commands, in order:** `training/README.md`.
  - Phase 6: dataset and teacher.
  - Phase 7: grid on a rented GPU.
  - Phase 8: exam, verify, report.
- **Fingerprints:** `python training/tools/prereg_hashes.py --check` confirms a
  run used the registered data.
- **Outputs:**
  - `outputs/grid_v1_results/`: the 11 adapters and GGUFs, with `SHA256SUMS`.
    The public release is Phase 11.

```bibtex
@misc{praxis_lora_v1_2026,
  title  = {Does structured evidence let a small model explain chess mistakes truthfully?},
  author = {Anand, Tanmay},
  year   = {2026},
  note   = {Praxis Chess, LoRA v1 research write-up},
  url    = {https://github.com/praxis-chess/Praxis-Chess}
}
```
