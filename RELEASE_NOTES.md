# Praxis Chess v1.3.0 — install with Docker

Praxis now installs with Docker alone: no Java, Node, Stockfish or Ollama to
set up by hand.

```bash
git clone https://github.com/Praxis-Chess/Praxis-Chess.git && cd Praxis-Chess
docker compose -f docker/compose.yaml up -d --build
```

Then open http://localhost:8086 and enter your Chess.com username in Settings.

- **One image** serves the app and its UI, with **Stockfish 19** built in from
  its source tag (x86-64 and ARM64; the CPU's fastest variant is picked at run
  time). Published images for both architectures on `ghcr.io/praxis-chess`.
- **Models download themselves** on first start (~8.5 GB, or ~3.8 GB with the
  light set). The trained commentary model is built from its Hugging Face
  release and checked against its published SHA-256.
- **Setup checklist** on Today and in Settings: database, Stockfish (with its
  version), username, each model with live download progress, voice and web
  research, each with the exact fix. The startup log shows the same checks.
- **Chess.com username in Settings → Player**, checked against Chess.com.
  `application.yml` still works as the fallback, so existing installs change
  nothing. Sync is refused, with a reason, until a username exists.
- **Add-ons:** NVIDIA GPU (`compose.gpu.yaml`), Ollama on the host
  (`compose.host-ollama.yaml`, the Mac route), Prax's voice and web research
  as Compose profiles.
- **Backups:** `docker compose -f docker/compose.yaml run --rm backup`.
- Only port 8086 is published, on 127.0.0.1. No secrets in the image: the
  build fails if a local `application.yml` reaches it, and CI checks the JAR.

Details: [docker/INSTALL.md](docker/INSTALL.md).

---

# Praxis Chess LoRA v1 — research release (`lora-v1.0`, 5 October 2026)

A small model that explains why a chess move was a mistake, using only
evidence it is handed, with every claim checked by a program. Released as a
complete, reproducible study, not only as weights.

**On Hugging Face:** the [Praxis Chess LoRA v1 collection](https://huggingface.co/collections/praxis-chess/praxis-chess-lora-v1)

| Repo | What |
|---|---|
| [`praxis-chess-reasoner-qwen3.5-2b-lora`](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-2b-lora) | The released model: Qwen3.5-2B + LoRA, trained on evidence graphs |
| [`praxis-chess-reasoner-qwen3.5-2b-GGUF`](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-2b-GGUF) | The same, q4_K_M, ready for Ollama; this is what Praxis loads |
| [`praxis-chess-reasoner-qwen3.5-4b-lora`](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-4b-lora), [`-4b-GGUF`](https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-4b-GGUF) | The 4B sibling |
| [`praxis-chess-grid-v1-arms`](https://huggingface.co/praxis-chess/praxis-chess-grid-v1-arms) | The comparison models (board only, engine numbers, flat facts) |
| [`praxis-chess-evidence-graphs`](https://huggingface.co/datasets/praxis-chess/praxis-chess-evidence-graphs) | Public data, graphs, renders, every published answer, verifier test vectors |

**Results** on 838 held-out public Lichess positions:
- **99.7%** of the 2B model's claims are true with the evidence graph, **34.8%**
  with the board alone.
- **78.0%** of its answers pass every check; the 4B's do 86.8% of the time.
- By its own pre-registered rules it did not replace Praxis's rule-based
  diagnosis. In the app it writes an optional commentary, shown only when every
  claim passes the verifier.

**In this repository:** [the write-up](training/reports/writeup_v1.md), the
[public table](training/reports/public_test_v1.md), [`REPRODUCE.md`](training/REPRODUCE.md),
and `praxis_eval`, the verifier in Python. To use the model in Praxis, see
README step 3b.

Earlier app releases (v1.1.0, v1.2.0) are described on
[GitHub Releases](https://github.com/Praxis-Chess/Praxis-Chess/releases).

---

# Praxis Chess v1.0.0

A self-hosted chess improvement platform that analyzes your Chess.com games
using Stockfish (engine) and a local LLM (Ollama) — no cloud, no subscriptions,
your game data stays on your machine.

---

## What's in v1.0.0

### Game analysis
- Syncs games from Chess.com automatically (rapid, blitz, bullet)
- Stockfish depth-18 analysis with top-3 engine continuation lines per mistake
- Filters the first 6 moves as book (no false blunder alerts in opening theory)
- LLM explanation of each mistake: what the better move achieves and why
- ACPL-based accuracy score per game (`103.17 × e^(−0.04354 × ACPL) − 3.17`)

### Insights dashboard
A full analytics page derived from your existing games — no extra AI calls:
- **Conversion rate** — how often you win after reaching a +2.0 advantage
- **Time-trouble blunder rate** — share of blunders under 30 seconds on the clock
- **Average time per move** — derived from PGN clock annotations, increment-aware
- **Accuracy trend** — per-game accuracy with a 10-game rolling average
- **Win rate vs. opponent strength** — Stronger / Even / Weaker (±50 rating)
- **Time-of-day and day-of-week** win rates
- **Missed tactics frequency** — which tactical motifs you miss most often
- **Tilt / resilience** — win rate in the game immediately after a win vs. after a loss
- **Per-opening stats** — win rate and accuracy by ECO code

### Drills
Every mistake with an engine best move becomes an interactive puzzle:
- Board shows your exact blunder position; you drag pieces to find the best move
- Wrong moves flash red; solved moves reveal the engine explanation
- "Show answer" reveals the engine move with a green arrow
- "View game" links back to the full game analysis

### Reliability
- Per-game transactions — a crash mid-run leaves completed games intact
- Startup crash recovery — any game stuck in ANALYZING is reset to PENDING
- Stockfish supervisor — subprocess is restarted automatically if it dies
- Ollama retry — 3 attempts with backoff before marking a move as LLM_FAILED
- Chess.com 429 retry — 3 attempts with 10 s / 20 s backoff

---

## Prerequisites

| Dependency | Version | Notes |
|---|---|---|
| Java (JDK) | 21+ | JRE is not enough — needs `java` on PATH |
| Docker Desktop | Any current | Runs PostgreSQL |
| Ollama | Any current | Install from https://ollama.com |
| Stockfish | SF 16+ | Download from https://stockfishchess.org/download/ |

---

## First run (~10 minutes)

**1. Start PostgreSQL**
```
docker compose up -d
```

**2. Pull the LLM model** (~4 GB download, one-time)
```
ollama pull qwen2.5:7b
```

**3. Edit `application.yml`** — two fields marked `← EDIT THIS`:
- `chess-com.username` — your Chess.com username
- `stockfish.path` — full path to the Stockfish executable

**4. Run the app**
```
# Windows
start.bat

# Mac / Linux
chmod +x start.sh && ./start.sh
```

Or directly:
```
java -jar praxis-chess-1.0.0.jar --spring.config.location=./application.yml
```

**5. Open http://localhost:8086**

**6. Click Sync** — fetches your last 12 months of games from Chess.com

**7. Click Analyze All** — first run takes ~8–12 minutes per 50 games depending on your GPU

---

## Startup preflight output

On each startup the app logs a preflight check:

```
[OK]   PostgreSQL reachable
[OK]   Ollama reachable and model 'qwen2.5:7b' found
[OK]   Stockfish found at 'C:/tools/stockfish/stockfish.exe'
===    open http://localhost:8086
```

Any `[FAIL]` or `[WARN]` line tells you exactly what to fix.

---

## Hardware notes

- Ollama runs natively (not in Docker) so it uses your GPU directly
- Tested on RTX 3050 (4 GB VRAM) with qwen2.5:7b — ~8–10 s per move explanation
- CPU-only works but is significantly slower; `qwen3:4b` is a faster alternative at slightly lower quality
- Stockfish + LLM run on overlapping threads to keep the pipeline moving

---

## Known limitations

- Single-user only (one Chess.com username per instance)
- Daily games are synced but time-per-move analytics are unavailable (no clock data)
- Existing games analyzed before v1.0.0 have null `max_advantage` / `avg_move_seconds` — re-analyze to populate them
