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
