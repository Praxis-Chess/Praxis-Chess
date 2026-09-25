# Phase 5 — prompted baselines: results

Run 2026-09-24 → 25 on the RTX 3050 Laptop (4 GB). 4,460 model answers, 0 failures,
each marked by the app's own verifier (`EvalCli verify`, claim mode). Aggregates
only: per-question outputs on the player's games stay in `results_private/`
(plan §11.6).

**Setup.**
- **Questions:** 890 of the player's own mistakes. C = 451 blunders and
  mistakes; C′ = 439 inaccuracies (the abstention test).
- **Models:** Qwen3.5-2B answered all 890 at R0–R3. Qwen3.5-4B (R0, R3) and
  qwen2.5:7b (R1) answered a shared fixed sample of 300.
- **Prompt:** few-shot, with three textbook worked examples. Greedy decoding,
  seed 0, answer schema enforced.
- **Routes:** Qwen3.5 through raw ChatML; qwen2.5 through `/api/chat`.

## Findings

1. **More structured evidence makes untrained models far more truthful. H1
   holds for prompted models.** Qwen3.5-2B's claim precision rises from 8% (R0,
   board only) to 17% (R1) and 18% (R2), then to **50% at R3**. R3 against R2
   is **+32.4 points** (95% interval +30.8 to +34.0, paired, 890 questions). The
   4B shows the same shape: 11% → 59%.
2. **Flat facts add nothing; the graph adds a lot.** R2 over R1 is +0.7 points
   (interval −0.1 to +1.6: not a real difference). The gain at R3 comes in the
   claim types whose facts only R3 contains:
   - THREAT_EXISTS: 12% → 63% true;
   - COUNTERFACTUAL: 19% → 63%;
   - VISIBILITY: 36% → 96%;
   - MATERIAL_CHANGE: 30% → 58%.

   The threat test, the "what if" test and the depth curve are what the
   models were missing.
3. **Today's product is the weakest AI system measured.** qwen2.5:7b at R1:
   - 9% claim precision;
   - it invents a cause on **90%** of the mistakes where nothing concrete
     happened;
   - it never abstains.

   The new 2B at the same R1 is +7.5 points better (+5.2 to +9.8). The 2B at R3
   is **+38.7 points** better, at under a third of the time per answer (13.6 s
   against 48.2 s).
4. **Size helps, but less than evidence.** At R3 the 4B beats the 2B by +11.4
   points (+8.5 to +14.3). From R0 to R3, evidence gives the 2B +42.7 points.
5. **No untrained model produces a fully correct explanation.**
   - Answers with zero false claims: 0–1% for every 2B arm, and 6% for the
     4B at R3.
   - Honest abstention on NOT_CONCRETE mistakes: about 50% at R3, 0–7% below
     it. So at R3 a model still invents a cause about half the time.
   - The rules: 100% of answers valid by construction, and 79% / 68% agreement
     with the hand labels on consequence / mechanism, against 70% / 55% for
     the best model (4B R3, n=33).

   This is the gap training has to close.
6. **Consequence and mechanism accuracy stay low even at R3.** The 2B agrees
   with the rules on consequence 39% of the time and on single-cause mechanism
   15%. Reading true facts off the evidence (claim precision) is easier than
   assembling them into the right diagnosis.

## Honest limits

- **Prompt length is not controlled.** R3 prompts are about twice R2's (531 vs
  261 tokens). The per-claim-type table says the gain comes from facts only R3
  contains, which is structure, not length. But the plan's R3-short arm
  (§15.3), which truncates R3 to R2's length, was not run. It belongs with the
  trained arms.
- **VISIBILITY at R3** is partly the model copying a field the graph states
  outright. That is reading the evidence correctly, but it inflates R3's
  precision a little. With VISIBILITY claims removed entirely, the 2B's
  precision is R0 1% · R1 12% · R2 14% · **R3 39%**: the R3 gain
  stands. R3 leads R2 on every common claim type except MOVED_INTO_ATTACK and
  DOES_NOT_ADDRESS.
- **"Agrees with the rules" uses the rules as the answer key**, and the rules
  agree with the player 66% of the time on single-cause mistakes (Phase 3,
  in-sample). Claim precision is judged against the graph, not the rules, so
  it does not depend on them.
- **Sample sizes:** the 4B and 7B ran on 300 questions, and hand-label
  agreement on the shared 300 rests on n = 33.
- **Held-out slices A+B** (Lichess) don't exist until Phase 6. These numbers
  are on the player's own games only.
- **Whole-answer validity sits at the floor** for every model, so McNemar on it
  cannot separate systems. Claim precision, with paired bootstrap intervals, is
  the working headline for the prompted half.

The player's own mistakes: C = blunders and mistakes, C′ = inaccuracies. 95% bootstrap intervals in brackets. S_rules agrees with the rules by construction, so its rule-agreement cells say nothing; its cells against your hand labels do.

## Every system, the shared sample (300 items: 146 C, 154 C′)

The only table where systems are compared with each other.

| System | Claim precision | Chain valid | Consequence vs rules | Mechanism vs rules | Abstains (NOT_CONCRETE) | Invents a cause | vs your labels (cons / mech) | p50 latency | Tokens in |
|---|---|---|---|---|---|---|---|---|---|
| S_rules | 100% (100–100) | 100% (100–100) | 100% | 100% | 100% | 0% | 82% / 79% (n=33) | — | 532 |
| qwen2.5-7b R1 | 9% (7–11) | 0% (0–0) | 23% | 27% | 0% | 90% | 39% / 30% (n=33) | 48.2 s | 186 |
| qwen3.5-2b R0 | 7% (6–8) | 0% (0–1) | 9% | 19% | 1% | 99% | 24% / 21% (n=33) | 15.6 s | 120 |
| qwen3.5-2b R1 | 17% (15–19) | 0% (0–1) | 21% | 13% | 1% | 99% | 30% / 12% (n=33) | 14.4 s | 186 |
| qwen3.5-2b R2 | 17% (15–19) | 0% (0–1) | 10% | 16% | 7% | 93% | 24% / 21% (n=33) | 14.4 s | 259 |
| qwen3.5-2b R3 | 48% (45–51) | 0% (0–1) | 37% | 13% | 50% | 50% | 27% / 30% (n=33) | 13.7 s | 532 |
| qwen3.5-4b R0 | 11% (9–13) | 0% (0–1) | 23% | 15% | 39% | 59% | 24% / 33% (n=33) | 33.3 s | 120 |
| qwen3.5-4b R3 | 59% (57–62) | 6% (3–8) | 60% | 32% | 55% | 45% | 70% / 55% (n=33) | 38.1 s | 532 |

## Each system on everything it answered (up to 890 items)

Qwen3.5-2B answered every item at every level; this is the R0–R3 comparison at full size.

| System | Claim precision | Chain valid | Consequence vs rules | Mechanism vs rules | Abstains (NOT_CONCRETE) | Invents a cause | vs your labels (cons / mech) | p50 latency | Tokens in |
|---|---|---|---|---|---|---|---|---|---|
| S_rules | 100% (100–100) | 100% (100–100) | 100% | 100% | 100% | 0% | 79% / 68% (n=100) | — | 531 |
| qwen2.5-7b R1 | 9% (8–11) | 0% (0–0) | 23% | 27% | 0% | 90% | 39% / 30% (n=33) | 48.2 s | 186 |
| qwen3.5-2b R0 | 8% (7–8) | 0% (0–0) | 11% | 22% | 2% | 98% | 14% / 24% (n=100) | 15.6 s | 120 |
| qwen3.5-2b R1 | 17% (16–18) | 0% (0–0) | 23% | 13% | 0% | 100% | 27% / 14% (n=100) | 14.6 s | 186 |
| qwen3.5-2b R2 | 18% (17–19) | 0% (0–1) | 11% | 16% | 7% | 93% | 14% / 17% (n=100) | 14.6 s | 261 |
| qwen3.5-2b R3 | 50% (49–52) | 1% (0–1) | 39% | 15% | 52% | 48% | 32% / 32% (n=100) | 13.6 s | 531 |
| qwen3.5-4b R0 | 11% (9–13) | 0% (0–1) | 23% | 15% | 39% | 59% | 24% / 33% (n=33) | 33.3 s | 120 |
| qwen3.5-4b R3 | 59% (57–62) | 6% (3–9) | 60% | 32% | 55% | 45% | 70% / 55% (n=33) | 38.1 s | 532 |

## Paired comparisons: claim precision (the headline test)

A minus B on the questions both answered; paired bootstrap 95% interval. A difference is real only when the interval excludes zero.

| A vs B | Questions | Difference | 95% interval | Real? |
|---|---|---|---|---|
| qwen3.5-2b R3 vs qwen3.5-2b R2 | 890 | +32.4 pts | +30.8 to +34.0 | yes |
| qwen3.5-2b R2 vs qwen3.5-2b R1 | 890 | +0.7 pts | -0.1 to +1.6 | no |
| qwen3.5-2b R1 vs qwen3.5-2b R0 | 890 | +9.5 pts | +8.6 to +10.5 | yes |
| qwen3.5-2b R3 vs qwen3.5-2b R0 | 890 | +42.7 pts | +41.2 to +44.4 | yes |
| qwen3.5-4b R3 vs qwen3.5-4b R0 | 300 | +48.4 pts | +45.3 to +51.7 | yes |
| qwen3.5-4b R3 vs qwen3.5-2b R3 | 300 | +11.4 pts | +8.5 to +14.3 | yes |
| qwen3.5-4b R0 vs qwen3.5-2b R0 | 300 | +4.0 pts | +2.7 to +5.3 | yes |
| qwen3.5-2b R1 vs qwen2.5-7b R1 | 300 | +7.5 pts | +5.2 to +9.8 | yes |
| qwen3.5-2b R3 vs qwen2.5-7b R1 | 300 | +38.7 pts | +35.7 to +41.6 | yes |
| qwen3.5-4b R3 vs qwen2.5-7b R1 | 300 | +50.2 pts | +47.6 to +52.9 | yes |

## Which claims come out true (Qwen3.5-2B, all 890 questions)

| Claim type | R0 | R1 | R2 | R3 |
|---|---|---|---|---|
| COUNTERFACTUAL | 0% of 902 | 22% of 895 | 19% of 894 | 63% of 908 |
| VISIBILITY | 37% of 888 | 37% of 884 | 36% of 840 | 96% of 861 |
| CRITICAL_REPLY | 1% of 867 | 6% of 883 | 6% of 818 | 21% of 737 |
| MOVED_INTO_ATTACK | 7% of 302 | 16% of 714 | 20% of 645 | 16% of 614 |
| MATE_IN | 1% of 681 | 2% of 381 | 1% of 640 | 6% of 297 |
| MATERIAL_CHANGE | 1% of 203 | 13% of 514 | 30% of 322 | 58% of 785 |
| THREAT_EXISTS | 1% of 581 | 6% of 172 | 12% of 145 | 63% of 110 |
| DOES_NOT_ADDRESS | 2% of 537 | 11% of 138 | 20% of 130 | 16% of 97 |
| BLOCKS_LINE | 0% of 39 | 0% of 34 | 15% of 86 | 0% of 1 |
| DEFENDER_REMOVED | — | — | 5% of 37 | — |
| LOSING_CAPTURE | 0% of 2 | 0% of 1 | 5% of 19 | 0% of 6 |
| OPENS_LINE | — | 0% of 1 | 0% of 6 | — |
| TACTIC_GEOMETRY | 0% of 3 | — | 0% of 2 | — |

## Paired comparisons: whole answer valid (McNemar exact)

Untrained models almost never produce an answer with zero false claims, so this test sits at the floor and cannot separate them. Reported for completeness.

| A vs B | Items | A only passes | B only passes | p |
|---|---|---|---|---|
| qwen3.5-2b R3 vs qwen3.5-2b R1 | 890 | 5 | 0 | 0.0625 |
| qwen3.5-2b R3 vs qwen3.5-2b R0 | 890 | 5 | 0 | 0.0625 |
| qwen3.5-2b R3 vs qwen3.5-2b R2 | 890 | 5 | 3 | 0.7266 |
| qwen3.5-4b R3 vs qwen3.5-2b R3 | 300 | 16 | 0 | 0.0 |
| qwen3.5-2b R3 vs qwen2.5-7b R1 | 300 | 1 | 0 | 1.0 |
| qwen3.5-4b R3 vs qwen3.5-4b R0 | 300 | 16 | 0 | 0.0 |
