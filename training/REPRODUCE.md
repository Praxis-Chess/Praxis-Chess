# Reproducing Praxis Chess LoRA v1

Four ways in, from cheapest to fullest. Each starts from a clone of the Praxis
repo at the release tag:

```bash
git clone --branch lora-v1.0 https://github.com/Praxis-Chess/Praxis-Chess.git
cd Praxis-Chess/training
```

Everything lives on the Hugging Face Hub under
[praxis-chess](https://huggingface.co/praxis-chess), tagged `v1.0`:

- the dataset,
  [praxis-chess-evidence-graphs](https://huggingface.co/datasets/praxis-chess/praxis-chess-evidence-graphs);
- the released adapters, 2B and 4B;
- their GGUFs;
- the comparison arms.

`reproduce.py` downloads what it needs.

**What counts as reproduced:** your claim precision and chain validity fall
inside the published 95% intervals in `results/public_test_v1.json`. The
script prints each comparison and a verdict, and exits with 0 when every
number is inside its interval.

## 1. Reproduce the table (about 1.5 hours on a 4 GB GPU)

The published numbers came from the q4_K_M GGUF in Ollama, greedy, with the
answer's JSON schema enforced. This step runs exactly that.

**Needs:**
- Python 3.12 or newer;
- [Ollama](https://ollama.com) 0.23 or newer (the published run used 0.23.1);
- any GPU Ollama can use (CPU works, much slower);
- about 2 GB of disk for the 2B, 3 GB for the 4B.

```bash
pip install python-chess huggingface_hub pyyaml
ollama serve        # if it is not already running
python reproduce.py run --model 2b-r3
```

It runs in six steps:
1. Downloads the test set and the GGUF.
2. Loads the GGUF into Ollama as `praxis-repro-2b-r3`.
3. Answers the 838 public positions, which can be interrupted: run it again
   to continue.
4. Checks every answer with `praxis_eval`.
5. Prints the table against the published one.
6. Writes everything to `reproduce_runs/2b-r3-ollama/`.

The other rows: `--model 4b-r3` (about 4 hours on the same GPU), `2b-r0`,
`2b-r1`, `2b-r2`, `4b-r0`. For a quick look first, `--limit 50` answers 50
positions; a sample is not judged against the intervals.

**If you reproduce it for us**, please send:
- `reproduce_runs/<model>-ollama/measured.json`;
- your Ollama version;
- your GPU.

Open an issue on the Praxis repo, or a discussion on the model's Hub page.

**Transformers instead of Ollama** (`--backend transformers`): this runs the
bf16 adapter through Transformers and PEFT.
- **Needs:** a CUDA GPU with about 8 GB, plus
  `pip install torch transformers==5.17.0 peft==0.21.0 accelerate`.
- **Why it can differ:** `generate()` cannot apply the JSON schema, so this is
  a cross-check, not the published setup.
- **How much:** on 300 positions, the GGUF and bf16 differed by 0.1 points of
  claim precision. Chain validity can move more.

## 2. Re-score without a GPU (minutes)

The dataset includes every answer behind the table (`results/outputs.jsonl`).
Checking them again needs no model:

```bash
pip install python-chess huggingface_hub pyyaml
python reproduce.py score
```

This runs `praxis_eval`, a Python port of the Java checker that judged the
published numbers:
- It reproduces the Java verdict on every published answer.
- It also reproduces the Java verdict on 1,959 test vectors, including answers
  broken on purpose.

To confirm the port on your machine:

```bash
python tests/test_parity.py
```

To score answers of your own, write them in the same shape as
`results/outputs.jsonl`:

```bash
python reproduce.py score --outputs my_outputs.jsonl
```

## 3. Retrain (about 1 GPU-hour per 2B arm)

Each model repo carries the configuration it was trained with, in
`training_config.yaml`, with the base model's revision pinned. The grid ran on
one 48 GB NVIDIA A40.

**Environment.** The pinned stack is in `tools/pod_setup.sh`:
- torch 2.14.0 (CUDA 13.0), transformers 5.17.0, peft 0.21.0, trl 1.13.0,
  datasets 5.0.1, accelerate 1.15.0;
- llama.cpp, for the GGUF;
- Java 21 or newer, for the checker that picks the checkpoint, with the
  libraries from step 4's Maven command.

**Data.** Put the dataset where the configuration expects it:

```bash
hf download praxis-chess/praxis-chess-evidence-graphs --repo-type dataset --revision v1.0 --local-dir hub_data
for R in R0 R1 R2 R3; do
  mkdir -p data/phase6_v1/$R
  cp hub_data/data/$R/train.jsonl data/phase6_v1/$R/train.jsonl
  cp hub_data/data/$R/validation.jsonl data/phase6_v1/$R/val.jsonl
done
mkdir -p data/phase7 && cp hub_data/data/graphs/validation.jsonl data/phase7/val_gold.jsonl
```

**Train, choose the epoch, export.** These are the same commands as
`tools/pod_grid.sh`. Copy the model repo's `training_config.yaml` to
`config/grid_v1/2b-r3.yaml` first.

```bash
python -m praxis_train.train_sft --config config/grid_v1/2b-r3.yaml
python -m praxis_train.select_checkpoint --config config/grid_v1/2b-r3.yaml
python -m praxis_train.export --config config/grid_v1/2b-r3.yaml --outtype Q4_K_M \
    --quantize <llama.cpp>/build/bin/llama-quantize --llama-cpp <llama.cpp> --relative --merge-on-gpu
python reproduce.py run --model 2b-r3 --gguf outputs/grid_v1/2b-r3/praxis-grid-2b-r3-q4_k_m.gguf
```

What to expect:
- Training is seeded (seed 17), but GPU kernels are not bit-exact across
  hardware.
- A retrained adapter should land near the published numbers, not on them.
  This checks the method, not the file.

## 4. Diagnose your own games

The adapter needs an evidence graph. Praxis builds one from a FEN, the move
played and Stockfish, with no database or server, through the headless
`GraphCli`.

**Needs:** Java 21 or newer, Maven once (to fetch the libraries), and
[Stockfish](https://stockfishchess.org). On Windows, run it from Git Bash with
`JAVA_HOME_26` set to your JDK folder; elsewhere, `JAVA_HOME`.

```bash
mvn -q -f ../backend/pom.xml dependency:copy-dependencies -DincludeScope=compile \
    -DoutputDirectory=../training/.javabuild/lib
bash tools/javacli.sh GraphCli --stockfish /path/to/stockfish \
    --fen "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3" --move Nf6 --level R3
```

With the flags:
- **`--level R3`** prints the text the adapter reads;
- **`--level json`** prints the graph that `praxis_eval` checks the answer
  against;
- **`--level diagnosis`** prints the rules' own diagnosis;
- **`--ply`, `--phase` (`OPENING`, `MIDDLEGAME` or `ENDGAME`) and `--severity`
  (`INACCURACY`, `MISTAKE` or `BLUNDER`)** describe the mistake. They appear in
  the rendering's header, so pass them as your analysis found them.

Stockfish runs deterministically (one thread, hash cleared), so running twice
for the R3 text and the JSON gives the same graph.

Send the R3 text to the model as the user message, using the system message
from the model card. Then check the answer:

```python
from praxis_eval import parse_diagnosis, verify
report = verify(graph, parse_diagnosis(answer))   # graph: the --level json output, parsed
```

Use the answer only if `report.passed`. That is how Praxis uses the model.

## Where each number comes from

| File | What |
|---|---|
| `reports/public_test_v1.json` / `.md` | The public table (`python -m praxis_train.public_table`) |
| `reports/grid_v1.md` | Every pre-registered verdict, including the author's private games (aggregates only) |
| `reports/writeup_v1.md` | The write-up |
| `PREREGISTRATION.md` (tag `prereg-v1`) | Hypotheses, metrics and decision rules, fixed before training |
