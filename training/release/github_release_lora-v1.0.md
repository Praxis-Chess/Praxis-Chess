A small model that explains **why** a chess move was a mistake, using only evidence it is handed, with every claim checked by a program. Released as a complete, reproducible study, not only as weights.

**Everything on Hugging Face:** [Praxis Chess LoRA v1 collection](https://huggingface.co/collections/praxis-chess/praxis-chess-lora-v1)

| Repo | What |
|---|---|
| [praxis-chess-reasoner-qwen3.5-2b-lora](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-2b-lora) | The released model: Qwen3.5-2B + LoRA, trained on evidence graphs |
| [praxis-chess-reasoner-qwen3.5-2b-GGUF](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-2b-GGUF) | The same, q4_K_M, ready for Ollama; this is what Praxis loads |
| [4B adapter](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-4b-lora) · [4B GGUF](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-4b-GGUF) | The 4B sibling |
| [praxis-chess-grid-v1-arms](https://huggingface.co/praxis-chess/praxis-chess-grid-v1-arms) | The comparison models: board only, engine numbers, flat facts |
| [praxis-chess-evidence-graphs](https://huggingface.co/datasets/praxis-chess/praxis-chess-evidence-graphs) | Public data, graphs, renders, every published answer, verifier test vectors |

### Results on 838 held-out public Lichess positions

| | True claims (95% CI) | Answers that pass every check |
|---|---|---|
| 2B, board only (R0) | 34.8% (31.4–38.1) | 0.5% |
| 2B, engine numbers (R1) | 56.9% (54.7–58.9) | 14.4% |
| **2B, evidence graph (R3)** | **99.7% (99.6–99.9)** | **78.0%** |
| 4B, evidence graph (R3) | 99.7% (99.5–99.9) | 86.8% |

Hypotheses and ship rules were fixed and tagged (`prereg-v1`) before training. By those rules the model did **not** replace Praxis's rule-based diagnosis: it failed agreement with fresh human labels and the composite-mistake rule. In the app it writes an optional commentary, shown only when every claim passes the verifier.

### Read and reproduce

- [Write-up](https://github.com/Praxis-Chess/Praxis-Chess/blob/lora-v1.0/training/reports/writeup_v1.md): question, method, results, limitations
- [Public table](https://github.com/Praxis-Chess/Praxis-Chess/blob/lora-v1.0/training/reports/public_test_v1.md)
- [REPRODUCE.md](https://github.com/Praxis-Chess/Praxis-Chess/blob/lora-v1.0/training/REPRODUCE.md): rerun the table, or re-score the published answers with no GPU
- `praxis_eval`: the verifier in Python, matching the Java one on all 5,866 published answers and 1,959 test vectors

### Use it in Praxis

README step 3b: download the GGUF, `ollama create praxis-grid-2b-r3 -f Modelfile`, then set `commentary-model: praxis-grid-2b-r3`.

Only public Lichess data is published; the author's own games never left the laptop.
