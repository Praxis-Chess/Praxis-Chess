# Public test set (T_AB, 838 held-out Lichess positions)

Every answer checked claim by claim by the Java verifier. Latency is on a 4 GB RTX 3050 laptop GPU (q4_K_M, Ollama), so read it as relative.

| System | Items | Parsed | Claim precision (95% CI) | Chain valid (95% CI) | Mechanism vs rules | Invents a cause | Median latency |
|---|---|---|---|---|---|---|---|
| Rules (no model) | 838 | 100% | 100.0% (100.0–100.0) | 100.0% (100.0–100.0) | 100% | 0% | — |
| 2B LoRA, R0 (board only) | 838 | 100% | 34.8% (31.4–38.1) | 0.5% (0.1–1.1) | 1% | 1% | 4.0 s |
| 2B LoRA, R1 (+ engine facts) | 838 | 100% | 56.9% (54.7–58.9) | 14.4% (12.1–16.8) | 50% | 26% | 6.6 s |
| 2B LoRA, R2 (+ tactics) | 838 | 100% | 56.8% (54.6–58.8) | 12.6% (10.6–14.9) | 56% | 27% | 5.9 s |
| 2B LoRA, R3 (full evidence graph) | 838 | 100% | 99.7% (99.6–99.9) | 78.0% (75.4–80.8) | 79% | 12% | 5.7 s |
| 4B LoRA, R0 (board only) | 838 | 100% | 32.6% (29.5–35.8) | 0.6% (0.1–1.2) | 0% | 0% | 7.9 s |
| 4B LoRA, R3 (full evidence graph) | 838 | 100% | 99.7% (99.5–99.9) | 86.8% (84.4–89.0) | 95% | 16% | 16.1 s |
