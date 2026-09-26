# Pre-registration v1 (`prereg-v1`)

Written 2026-09-27, **before any Qwen3.5-2B or 4B model is fine-tuned**. The
commit that adds this file is tagged `prereg-v1`. The tag's timestamp shows the
hypotheses, metrics and decision rules came before the Phase 7 results. Any
choice made after the tag is listed as a deviation, with its reason, in the
Phase 8 report (`training/reports/grid_v1.md`).

## 1. What is already known

This was written with these results in hand. They shape the expectations, so
they are stated here rather than discovered later.

- **Phase 5, prompted models** (few-shot, frozen; `reports/phase5_baselines.md`).
  Claim precision on the player's 890 mistakes:
  - Qwen3.5-2B: R0 8%, R1 17%, R2 18%, R3 50%.
  - Qwen3.5-4B: R0 11%, R3 59%, on a 300-item sample.
  - qwen2.5:7b: R1 9%.
  - Chain validity is at or near 0% for every prompted system.
- **Phase 6, a 0.8B rehearsal.** Qwen3.5-0.8B was LoRA-trained on dataset
  **v0**, whose targets are the rules' own answers, at R3 only. It is not part
  of the grid.
  - On the same 890: claim precision 97%, chain validity 81%.
  - Agreement with hand labels is 79% / 66%, against the rules' 79% / 68%.
  - It named a cause on 17% of NOT_CONCRETE mistakes; the rules name none.
- **Dataset v1**, the training data for this grid:
  - v0 plus the verified answers of a teacher, Qwen3.5-27B-FP8.
  - train 7,057 rows (5,087 teacher targets); val 1,121.
  - Of the 2,102 verified composite answers, 1,422 answer UNCLEAR.
- **The 100 hand labels in the test set are in-sample for the rules.** The
  rules were revised against them in Phase 3. Any comparison of `S_rules` with
  those labels favours the rules. The ship decision therefore uses only labels
  made after this tag (§9).
- **No test item was used to train or tune anything.** Test positions and the
  few-shot examples are removed from training by position (placement and side
  to move). Test items and hypotheses were never used to select teacher answers
  or prompts; teacher pilots ran on public training rows only.

## 2. The question

Does fine-tuning a small model on verified, evidence-grounded diagnoses let it
explain chess mistakes as faithfully as hand-written rules? Does it do so better
the richer the evidence it reads, and does it go beyond the rules where they
abstain?

## 3. Systems

| System | R0 | R1 | R2 | R3 | Status |
|---|---|---|---|---|---|
| `S_rules` (the rule composer) | | | | ✓ | Scored by the verifier; frozen |
| Qwen3.5-2B prompted, 3-shot | ✓ | ✓ | ✓ | ✓ | Phase 5, frozen (hash in §5) |
| Qwen3.5-4B prompted, 3-shot | ✓ | | | ✓ | Phase 5, frozen (300 items) |
| **Qwen3.5-2B + LoRA** | ✓ | ✓ | ✓ | ✓ | Phase 7 |
| **Qwen3.5-4B + LoRA** | ✓ | | | ✓ | Phase 7 |
| 2B-R3 LoRA ablations | | | | −CF · −T · −Δ · −D · r=32 | Phase 7 |

**Training recipe, fixed for every LoRA arm:**
- **Data:** dataset v1 at the arm's own R level, the same targets at every level.
- **Adapter:** bf16 LoRA, r = 16, α = 32, dropout 0.
- **Targets:** the projections of both attention kinds, classic and linear
  (`q,k,v,o,in_proj_qkv,in_proj_z,in_proj_b,in_proj_a,out_proj`), plus the MLP
  (`gate,up,down`). The vision tower and `lm_head` are frozen.
- **Optimiser:** learning rate 2e-4, cosine schedule, 5% warmup, effective
  batch 8.
- **Length and loss:** max sequence 1,536 tokens, loss on the answer only.
- **Seed and epochs:** seed 17, 2 epochs.
- **Checkpoint:** the end-of-epoch checkpoint with the higher verifier claim
  precision on a fixed 200-row validation sample (the first 200 rows of that
  R's `val.jsonl`). Ties go to epoch 2. Test sets are never used for selection.

**The ablations** retrain 2B-R3 with one evidence component removed from every
render, in training and in test:
- **−CF:** the `[CF1]` line.
- **−T:** `[T1]`.
- **−Δ:** every `[Δ…]` line.
- **−D:** `[D1]`.

r=32 is a capacity check and belongs to no hypothesis.

**Inference, fixed for every LoRA arm:**
- The training format: the dataset's system message, no worked examples.
- Temperature 0, seed 0.
- JSON-schema decoding with the Phase 5 answer schema.
- 1,000-token answer cap.
- Served as a GGUF through Ollama, the build Praxis would serve: q4_K_M, or
  q8_0 if q4_K_M cannot be produced (recorded).
- The **quantisation delta** (bf16 against the served GGUF, 2B-R3, 300 items)
  is reported and never used for a verdict.

Every system is scored in its own best format. The prompted arms read three
worked examples; the LoRA arms read none. This is stated in every comparison.

## 4. Test sets

| Set | Items | Contents |
|---|---|---|
| **T_C** (primary) | 890 | The player's own mistakes: C 451 (blunders, mistakes), C′ 439 (inaccuracies). 416 single-cause, 82 composite, 392 NOT_CONCRETE. 100 hand-labelled (in-sample for the rules). |
| **T_AB** (replication) | 838 | Held-out Lichess positions: A 236 puzzles, B 602 rated games; 112 composite. Never trained on. |

- **Verdicts are made on T_C**, the product's own question. T_AB is the
  replication; where it disagrees, the verdict says so.
- **The 4B arms** answer every item if the Phase 7 budget allows. Otherwise
  they answer the Phase 5 fixed sample: the first 300 items of the seed-5
  shuffle in `baselines.ordered_items`. That choice is made by cost before
  evaluation, never after seeing results.
- **Fresh human labels** are labels made at `/labels` after this tag
  (`labelled_at` later than the tag's time). They are needed for decision rule
  (b).

## 5. Fingerprints

The data files are gitignored: they hold engine output and, for T_C, the
player's games. The tag therefore records their SHA-256, and a later run proves
it used exactly these files:

```bash
python training/tools/prereg_hashes.py --check
```

| File | SHA-256 |
|---|---|
| `training/data/phase6_v1/manifest.json` | `d8706b308b59c836dfae65b118b037ca23e4d118018e3bb904964bd8e14392cb` |
| `training/data/phase6_v1/R0/train.jsonl` | `8a0218d744192ba1f584d53f28eb324c3deb822e55b8ad8944fd814915c7454a` |
| `training/data/phase6_v1/R1/train.jsonl` | `d5c2b2a39e472b72a6c3641cc445730f0c68b038debd32c6fa7050efda8819f6` |
| `training/data/phase6_v1/R2/train.jsonl` | `67095e3ce64d01dd924cbe7bd789e045f28216c12c221bbe5226cfc9b6c971cd` |
| `training/data/phase6_v1/R3/train.jsonl` | `efa6876a3fb469b9cb9b96320a9d89128f2174e3c5d7fd7feabc69a5762f44a4` |
| `training/data/phase6_v1/R3/val.jsonl` | `292176148298ec2630098fcff28f0f84e1750dc6ae3657a752378750cac07f7e` |
| `training/data/phase5/testset.jsonl` | `172653fe30e486432389736521ad1ff0dfcb805d8ce9b57baa89f21c5ce067f2` |
| `training/data/phase6_v1/test_ab.jsonl` | `24189af17b2a6c4adec6b0ddf9cc52118b972b3b2e3941c4e66a939c98ce5172` |
| `training/data/phase5/fewshot.jsonl` | `94372a6dfe7e659722daef1d341d05ae83fc99beb12ac65c9e003b6b01425903` |
| `training/data/phase5/schema.json` | `5daea2c5e3762aa77661da6cf5ad6d946e6ac0e5f1331ba555461a16a68527fd` |
| `backend/src/main/java/com/praxis/evidence/diagnosis/DiagnosisVerifier.java` | `bf35517aa1df0deef2341e6be74ea5ff705391e175428560d7145d1f28a68afb` |
| `backend/src/main/java/com/praxis/evidence/diagnosis/DiagnosisRules.java` | `29357ee0eec49746979c89bd82c44e2d9ff74ee8590f6d3bc9e3d4a99afe835d` |
| `backend/src/main/java/com/praxis/evidence/graph/EvidenceGraphBuilder.java` | `396c911842bdd1564018242015d5225d1540e3b444327acbbc81daeb988b0293` |
| `training/results_private/phase5/full/verified.jsonl` | `513fbd1cf593739423b7537750db196c7abc4a38c3d129d4fd31478c2f93b04f` |

- **What else pins the instrument:** the evidence graph is version 2
  (`GRAPH_VERSION = 2`), and the verifier is the code at the tagged commit.
- **Line endings:** the Java sources are hashed as checked out on the
  registering machine (Windows). A checkout with other line-ending settings
  gives different hashes for those three files, and the tagged commit is then
  the reference.

## 6. Metrics

The metrics are computed by `baseline_metrics.score_system` and
`trained_metrics.py` at the tagged commit, from the verifier's output fields.
An answer that does not parse counts as wrong everywhere.

| Metric | Definition |
|---|---|
| **Claim precision** (headline) | Σ `claims_true` / Σ `claims` over parsed answers, claim mode, pooled over items |
| **Chain validity** | Share of items with `claim_passed` (no false claim, and rules 3–6 hold) |
| Mechanism accuracy vs rules | On single-cause items: `mechanism` = the rules' mechanism |
| Mechanism and consequence vs humans | On hand-labelled items: equal to the human label |
| **Composite named coverage** | On composite items: `mechanism` is one of the rules' fired causes **and** `claim_passed` |
| Composite named precision | Among composite answers naming one of the fired causes: share with `claim_passed` |
| Composite verified rate | On composite items: `claim_passed`, any mechanism including UNCLEAR |
| Abstention | On NOT_CONCRETE items: consequence NOT_CONCRETE and mechanism NONE |
| **Invented-cause rate** | On NOT_CONCRETE items: `mechanism` is one of the six causes |
| Faithfulness | Share of answers with no rule-5 violation (every move in the prose is earned) |
| Citation validity | R3 only: citation-mode pass |
| Latency, tokens in | p50 / p95 per answer; mean prompt tokens per R level (the H1 length confound) |

## 7. Statistics

- **Paired:** every system answers the same items.
- **Rates:** 95% intervals from a paired bootstrap, 1,000 resamples over items,
  seed 0. Claim precision is pooled per resample.
- **Chain validity:** McNemar's exact test.
- **Verdicts:**
  - **Supported:** the 95% interval of the pre-registered difference excludes
    zero in the predicted direction.
  - **Refuted:** it excludes zero in the opposite direction.
  - **Not resolved:** otherwise.
- **No multiple-comparison correction.** Only the comparisons in §8 and §9 give
  verdicts; everything else is descriptive and is reported as such.

## 8. Hypotheses, operationalised

**H1: claim correctness rises with evidence.** 2B-LoRA at R0 … R3, claim
precision on T_C.
- *Supported* if R3 − R0 > 0 and R3 − R1 > 0, and no adjacent step (R0→R1,
  R1→R2, R2→R3) falls significantly.
- *Refuted* if R3 − R1 < 0 (then it is the headline finding, §9).
- **Length control:** claim precision is also reported by prompt-length tertile.
  If the R3 advantage over R1 is not resolved within the tertile where both
  have items, the write-up says the gain may be length rather than structure.
- The R3-short arm (§15.3 of the plan) is reported if it is built before
  Phase 8, and never used for a verdict.

**H2: the LoRA gain is largest at R3.** gain(L) = claim precision of 2B-LoRA-L
minus that of 2B-prompted-L, per item, on T_C.
- *Supported* if gain(R3) − gain(L) > 0 for each of L = R0, R1, R2.

**H3: evidence substitutes for scale.** gap(L) = claim precision of 4B-LoRA-L
minus that of 2B-LoRA-L, on the items both answered.
- *Supported* if gap(R0) − gap(R3) > 0.

**H4: beyond the rules on composite mistakes.** Measured on the composite items
of T_C and T_AB pooled (82 + 112 = 194), because 82 alone is too few. `S_rules`
names no cause on any of them, so its named coverage is 0 by construction.
- *Supported* for 2B-R3-LoRA if named coverage is at least 10% with a 95%
  Wilson lower bound above 5%, and named precision is at least 80%.
- A model that answers UNCLEAR everywhere scores 0 here, and the composite
  verified rate is reported beside it.
- *Expectation, stated honestly:* 332 of the 2,102 verified teacher answers
  (16%) name a cause, so a modest coverage is the likely outcome.

**H5: the counterfactual matters most.** drop(X) = mechanism accuracy vs the
rules on T_C's 416 single-cause items for full 2B-R3, minus the same for the
−X ablation.
- *Supported* if drop(CF) − drop(X) > 0 for each X in {T, Δ, D}.
- Counterfactual correctness is reported alongside, descriptively.

## 9. Decision rules

For **2B-R3-LoRA**, on T_C:

| | Criterion |
|---|---|
| (a) | Claim precision ≥ 95%, with the 95% lower bound ≥ 93% |
| (b) | Not worse than `S_rules` against **fresh** human labels on single-cause mechanism. Paired difference (model − rules) with a 95% lower bound above −5 points, on at least 30 fresh single-cause labels. With fewer than 30, (b) is **not met**. |
| (c) | Invented-cause rate on NOT_CONCRETE ≤ 10% (`S_rules`: 0%; the 0.8B on v0: 17%) |
| (d) | H4 supported |

| Outcome | Product decision |
|---|---|
| (a), (b), (c) and (d) | **Ship the model.** The rules stay as the fallback; every model answer is still verified live, and a failing answer falls back to the rules. |
| (a), (b), (c), but not (d) | The model is ≈ `S_rules` with no composite gain: **ship the rules**. The graph and the "Why?" panel ship regardless. |
| 4B-R3-LoRA meets (a)–(d) and 2B does not | Document it. The rules stay in production, and serving the 4B becomes a hardware question for V3. |
| H1 refuted (R3 does not beat R1) | That is the headline finding of the write-up. |
| **Any outcome** | **Publish it**, including a negative result. |

## 10. What may change after the tag

- **Not deviations, but reported:**
  - bug fixes that leave the definitions above unchanged;
  - the budget fallbacks named above (the 4B on 300 items; q8_0 instead of
    q4_K_M).
- **Deviations:** anything else, listed with its reason in `grid_v1.md`.
- **One evaluation per system.** A crashed run is re-run with the same
  configuration. Seeds, prompts or checkpoints are never re-chosen after looking
  at test results.
- **Budget:** review at ₹1,500, hard cap ₹3,000. If the cap stops the grid, the
  missing arms are reported as missing, not estimated.
