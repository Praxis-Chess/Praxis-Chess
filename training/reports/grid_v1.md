# Phase 8 — the grid against its pre-registration

Every number below is defined in `training/PREREGISTRATION.md` (tag `prereg-v1`) and computed from the app's verifier. 95% paired bootstrap intervals in brackets (1,000 resamples, seed 0). T_C is the player's own 890 mistakes (primary); T_AB the 838 held-out Lichess positions (replication).

## Decision (§9)

**Ship the rules: 2B-R3-LoRA does not meet (b), (d).**

| Criterion | 2B-R3-LoRA | 4B-R3-LoRA |
|---|---|---|
| (a) claim precision ≥ 95%, lower bound ≥ 93% | ✓ 100% (99–100) | ✓ 100% (99–100) |
| (b) vs `S_rules` on fresh labels, lower bound > −5 pts, n ≥ 30 | ✗ -3.0 pts (-18–12), n=33 | ✓ +9.1 pts (0–21), n=33 |
| (c) invented cause on NOT_CONCRETE ≤ 10% | ✓ 7% | ✗ 16% |
| (d) H4 supported | ✗  | ✗  |

## Disclosures

- **The 4B checkpoint choice was redone before any test answer existed.** The
  grid's first pass prompted the 4B in its template's default thinking mode.
  Qwen3.5-4B opens a thinking block; the 2B closes it empty.
  - Every 4B validation answer began "Thinking Process: …" and scored 0, so
    epoch-2 won only by the tie rule.
  - It was redone with the training format (`enable_thinking=False`) on the
    same 200 rows, and both 4B arms chose **epoch-1** (4B-R3 0.992 vs 0.987;
    4B-R0 0.276 vs 0.135). Both were re-exported.
  - Training was unaffected.
  - Details: `outputs/grid_v1_results/RESELECT_4B.md`.
- **2B-R3 chose epoch-1 by 0.13 points** (validation claim precision 0.9916
  vs 0.9903), as §3 prescribes.
  - The rule looks at claim precision only. The chosen epoch names the rules'
    mechanism far less often: 65% on T_C, where its epoch-2 siblings reach
    95–98%.
  - H5 therefore compares an epoch-1 full model with epoch-2 ablations. Its
    verdict rests on the ablations compared with one another (−T is the lowest
    at 76%), not on the drop from the full model.
- **Rule (b) uses 33 fresh single-cause labels**, all made after `prereg-v1`
  and all on T_C items. 51 fresh labels in all; 18 were NONE. The minimum
  (≥ 30) is met, but the interval is wide: 2B-R3 −3.0 pts (−18 to +12).
  Criterion (b) fails because 33 labels cannot show non-inferiority within
  5 points, not because the model was shown to be worse.
- **The quantisation delta uses T_AB, not T_C**, because the bf16 half ran on
  the rented GPU and the player's games never leave this machine (§4).
- **Before the grid, the Phase 6 0.8B dev loop was found to have trained on
  every token** (TRL ignores `completion_only_loss` for chat rows). The grid
  trains on the answer only, as §3 requires; the 0.8B is not part of the grid.
- **Harness fixes during Phase 8 changed no definitions:**
  - the ETA guess in the progress tool;
  - a fallback reader for the grid configs;
  - the export endpoint's timestamp format.

## Hypotheses (§8)

| | Verdict | Test |
|---|---|---|
| H1 | **supported** | R3−R0 +67.5 pts (64–70); R3−R1 +44.8 pts (43–47) |
| H2 | **supported** | gain(R3)-gain(R0) +24.8 pts (22–28); gain(R3)-gain(R1) +11.7 pts (9–14); gain(R3)-gain(R2) +13.4 pts (11–16) |
| H3 | **supported** | gap(R0)−gap(R3) +2.0 pts (0–4) |
| H4 | **not supported** | named coverage 14% (Wilson low 10%), named precision 60%, n=194 |
| H5 | **refuted** | drop(CF)-drop(T) -18.8 pts (-23–-15); drop(CF)-drop(DELTA) +1.7 pts (-0–4); drop(CF)-drop(D) +1.9 pts (0–4) |

## Every system on T_C (890)

| System | Items | Claim precision | Chain valid | Mechanism vs rules | Invents a cause | vs your labels (100, in-sample) | p50 latency |
|---|---|---|---|---|---|---|---|
| S_rules | 890 | 100% (100–100) | 100% | 100% | 0% | 79% / 68% | — |
| qwen3.5-2b R0 | 890 | 8% (7–8) | 0% | 22% | 98% | 14% / 24% | 15.6 s |
| qwen3.5-2b R1 | 890 | 17% (16–18) | 0% | 13% | 100% | 27% / 14% | 14.6 s |
| qwen3.5-2b R2 | 890 | 18% (17–19) | 0% | 16% | 93% | 14% / 17% | 14.6 s |
| qwen3.5-2b R3 | 890 | 50% (49–52) | 1% | 15% | 48% | 32% / 32% | 13.6 s |
| qwen3.5-4b R0 | 300 | 11% (9–13) | 0% | 15% | 59% | 24% / 33% | 33.3 s |
| qwen3.5-4b R3 | 300 | 59% (57–62) | 6% | 32% | 45% | 70% / 55% | 38.1 s |
| lora-2b-r3 | 890 | 100% (100–100) | 74% | 65% | 7% | 81% / 69% | 4.6 s |
| lora-2b-r1 | 890 | 55% (53–57) | 14% | 46% | 22% | 75% / 60% | 5.1 s |
| lora-2b-r0 | 890 | 32% (29–35) | 1% | 0% | 1% | 44% / 41% | 4.0 s |
| lora-2b-r2 | 890 | 54% (52–56) | 14% | 54% | 26% | 72% / 59% | 5.1 s |
| lora-4b-r3 | 890 | 100% (99–100) | 85% | 92% | 16% | 79% / 69% | 14.6 s |
| lora-4b-r0 | 890 | 34% (31–37) | 1% | 0% | 0% | 44% / 41% | 9.0 s |
| lora-2b-r3-nocf | 890 | 98% (98–99) | 84% | 95% | 15% | 80% / 68% | 5.3 s |
| lora-2b-r3-not | 890 | 99% (99–100) | 79% | 76% | 10% | 78% / 59% | 5.2 s |
| lora-2b-r3-nodelta | 890 | 99% (99–100) | 86% | 97% | 18% | 79% / 67% | 5.3 s |
| lora-2b-r3-nod | 890 | 79% (77–80) | 31% | 97% | 16% | 80% / 67% | 5.3 s |
| lora-2b-r3-r32 | 890 | 100% (100–100) | 88% | 98% | 18% | 79% / 67% | 5.6 s |

## Replication on T_AB (838)

| System | Items | Claim precision | Chain valid | Mechanism vs rules | Invents a cause |
|---|---|---|---|---|---|
| S_rules | 838 | 100% (100–100) | 100% | 100% | 0% |
| lora-2b-r3 | 838 | 100% (100–100) | 78% | 79% | 12% |

## Quantisation delta (§3, descriptive)

2B-R3 on 300 T_AB items, no JSON schema on either side: bf16 100%, q4_K_M 100% claim precision; q4_K_M − bf16 -0.1 pts (-0–0).
