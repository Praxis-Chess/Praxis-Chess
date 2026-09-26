# Phase 6 — the trained praxis-0p8b-dev against the prompted baselines

Every answer judged by the app's verifier. 95% bootstrap intervals in brackets. The trained model learned the rules' answers, so its agreement with the rules is agreement with its teacher; claim precision and chain validity are what say it reads the evidence.

## Your games (C and C′)

### All 890 questions

The 4B answered only the shared sample; it is in the next table.

| System | Parsed | Claim precision | Chain valid | Citations valid | Consequence vs rules | Mechanism vs rules | Abstains (NOT_CONCRETE) | Invents a cause | vs your labels (cons / mech) | p50 latency |
|---|---|---|---|---|---|---|---|---|---|---|
| S_rules | 100% | 100% (100–100) | 100% (100–100) | 100% | 100% | 100% | 100% | 0% | 79% / 68% (n=100) | — |
| qwen3.5-2b R3 (few-shot) | 100% | 50% (49–52) | 1% (0–1) | 1% | 39% | 15% | 52% | 48% | 32% / 32% (n=100) | 13.6 s |
| praxis-0p8b-dev R3 | 100% | 97% (97–98) | 81% (78–83) | 81% | 90% | 97% | 83% | 17% | 79% / 66% (n=100) | 4.5 s |

### The shared sample (300 questions)

The only table with all three models.

| System | Parsed | Claim precision | Chain valid | Citations valid | Consequence vs rules | Mechanism vs rules | Abstains (NOT_CONCRETE) | Invents a cause | vs your labels (cons / mech) | p50 latency |
|---|---|---|---|---|---|---|---|---|---|---|
| S_rules | 100% | 100% (100–100) | 100% (100–100) | 100% | 100% | 100% | 100% | 0% | 82% / 79% (n=33) | — |
| qwen3.5-2b R3 (few-shot) | 100% | 48% (46–50) | 0% (0–1) | 0% | 37% | 13% | 50% | 50% | 27% / 30% (n=33) | 13.7 s |
| qwen3.5-4b R3 (few-shot) | 100% | 59% (57–62) | 6% (3–8) | 6% | 60% | 32% | 55% | 45% | 70% / 55% (n=33) | 38.1 s |
| praxis-0p8b-dev R3 | 100% | 98% (97–99) | 85% (81–89) | 85% | 92% | 96% | 87% | 13% | 85% / 76% (n=33) | 4.5 s |

### Paired: claim precision, trained minus prompted

| A vs B | Questions | Difference | 95% interval | Real? |
|---|---|---|---|---|
| praxis-0p8b-dev R3 vs qwen3.5-2b R3 (few-shot) | 890 | +47.1 pts | +45.3 to +48.8 | yes |
| praxis-0p8b-dev R3 vs qwen3.5-4b R3 (few-shot) | 300 | +38.7 pts | +35.7 to +41.4 | yes |

### Paired: whole answer valid (McNemar exact)

| A vs B | Questions | A only passes | B only passes | p |
|---|---|---|---|---|
| praxis-0p8b-dev R3 vs qwen3.5-2b R3 (few-shot) | 890 | 713 | 2 | 0.0 |
| praxis-0p8b-dev R3 vs qwen3.5-4b R3 (few-shot) | 300 | 242 | 3 | 0.0 |

### Which claims praxis-0p8b-dev gets right (your games)

| Claim type | Made | True |
|---|---|---|
| VISIBILITY | 890 | 100% |
| MATERIAL_CHANGE | 535 | 100% |
| COUNTERFACTUAL | 532 | 99% |
| CRITICAL_REPLY | 348 | 98% |
| THREAT_EXISTS | 135 | 100% |
| DOES_NOT_ADDRESS | 135 | 100% |
| TACTIC_GEOMETRY | 119 | 71% |
| MOVED_INTO_ATTACK | 52 | 100% |
| DEFENDER_REMOVED | 23 | 78% |
| LOSING_CAPTURE | 20 | 70% |
| MATE_IN | 17 | 88% |

## Held-out Lichess positions (never trained on)

### All (838 questions)



| System | Parsed | Claim precision | Chain valid | Citations valid | Consequence vs rules | Mechanism vs rules | Abstains (NOT_CONCRETE) | Invents a cause | vs your labels (cons / mech) | p50 latency |
|---|---|---|---|---|---|---|---|---|---|---|
| S_rules | 100% | 100% (100–100) | 100% (100–100) | 100% | 100% | 100% | 100% | 0% | — | — |
| praxis-0p8b-dev R3 | 100% | 97% (96–98) | 77% (75–80) | 77% | 88% | 97% | 79% | 21% | — | 4.6 s |

### Slice A — puzzles (236 questions)



| System | Parsed | Claim precision | Chain valid | Citations valid | Consequence vs rules | Mechanism vs rules | Abstains (NOT_CONCRETE) | Invents a cause | vs your labels (cons / mech) | p50 latency |
|---|---|---|---|---|---|---|---|---|---|---|
| S_rules | 100% | 100% (100–100) | 100% (100–100) | 100% | 100% | 100% | 100% | 0% | — | — |
| praxis-0p8b-dev R3 | 100% | 98% (97–98) | 74% (68–79) | 74% | 91% | 98% | 57% | 43% | — | 6.1 s |

### Slice B — rated games (602 questions)



| System | Parsed | Claim precision | Chain valid | Citations valid | Consequence vs rules | Mechanism vs rules | Abstains (NOT_CONCRETE) | Invents a cause | vs your labels (cons / mech) | p50 latency |
|---|---|---|---|---|---|---|---|---|---|---|
| S_rules | 100% | 100% (100–100) | 100% (100–100) | 100% | 100% | 100% | 100% | 0% | — | — |
| praxis-0p8b-dev R3 | 100% | 97% (96–98) | 79% (76–82) | 79% | 87% | 96% | 81% | 19% | — | 4.4 s |

