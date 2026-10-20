# Praxis Chess v1.5.0 — one frame, your own positions, a sample to explore

Every screen now sits in the same calm frame, shows the positions behind its
numbers, and says what to do next. A new install has something to look at
before its first game is analysed.

- **One frame on every page.** Pages are centred on one of three widths
  instead of hugging the left edge. A new top bar: the logo on the left, the
  six sections centred, Settings on the right, and new games or games waiting
  as small "16 new · Sync" and "3 waiting · Analyse" chips. The sync banner
  appears only while a run is going or has failed. A page the nav already names
  no longer repeats its title, and Library and Insights share one tab style.
- **Today, as a cockpit.** The day's focus shows one of your own positions
  behind it, and a rail beside it carries your rating trend, your last game,
  your next plan session and your top habit. "Due today" is the deck's real
  count.
- **A drill deck that fills itself.** Every analysed blunder and mistake
  becomes a review card, up to 120 active cards and 10 new a day, due the day
  it is made: blunders first, then the positions your training plan is aimed
  at. Games analysed before this release are added on first start. Before, the
  deck stayed empty until a session was started.
- **Boards in lists.** Library → Drills is a gallery of positions; each
  Pattern Report habit shows its costliest position; each Training Plan session
  shows its positions; each thrown-away win shows the position where it
  slipped.
- **Game Analysis shows the whole game.** Your winning chance after every
  move, with your mistakes marked on the curve; the full move list, where any
  move shows its position; and the page opens on your costliest mistake.
  Curves are kept for games analysed from this release on; an older game
  offers "Re-analyse this game".
- **Accuracy fixed.** Games analysed before the eval-sign fix kept accuracies
  that were far too low (some under 20%). Accuracy is now recomputed every time
  a game is analysed, so re-analysing corrects them.
- **Insights says what the chart says.** A takeaway line ("Your Elo has risen
  from 1171 on 23 Jul to 1278, and your play with it…"), with a link to what
  goes wrong when results fall. Win rates are coloured against your own rate,
  not 50%, so an average day is no longer red. No half-empty cards, and the
  thrown-away wins come with "Drill these positions".
- **Openings named at sync.** A game shows its opening as soon as it is
  synced, not only after analysis; existing games are filled in on first start.
- **Ask Prax starts somewhere.** Before the first question: what Prax is
  answering from (your analysed games, report and plan), questions drawn from
  your latest report, and your recent questions.
- **Explore with sample games.** Empty screens say what they will show and
  what they are built from. Until your own games are in, "Explore with sample
  games" shows the whole app on 16 games Stockfish played as a made-up player,
  analysed by the real pipeline. It is read-only, and a banner on every page
  says so and is the way out.
- **Feels responsive.** Every button, row and tab answers hover and press, in
  one 120–180 ms motion scale. With reduced motion set, nothing moves: no
  transitions, no press travel, smooth scrolls jump, and the loading mark holds
  still.
- **Prax without the particle body.** Prax is its voice, its answers and its
  progress and question cards, which sit beside the page when there is room.
  The ambient background setting is hidden for now; the background is off.
- **Smaller fixes.** Today's rating panel always said "Not enough rated games
  yet"; it now shows your rating trend. Due dates, "due today" and streaks all
  use your practice timezone. "Start session" says why when it cannot start.
  The week strip shows the days still to come. The local `application.yml`
  template now includes `commentary-model`, without which a fresh install had
  no trained commentary, and `practice.timezone`.

**Upgrading.** Docker with the published images:
`docker compose -f docker/compose.yaml pull`, then
`docker compose -f docker/compose.yaml up -d`. If you set `PRAXIS_VERSION`,
change it to `1.5.0` or remove it to follow `latest`. Building it yourself:
`git pull`, then `docker compose -f docker/compose.yaml up -d --build`. From
source: `git pull`, `npm ci` in `frontend` (new font packages), rebuild the
backend and restart; add `commentary-model: praxis-grid-2b-r3` under
`praxis-chess.ollama` in your `application.yml` if it is missing. The database
updates itself on start (the drill deck and opening names are filled in
there); nothing is deleted. To get curves and corrected accuracy on games
analysed before this release, re-analyse them: per game from Game Analysis, or
all at once in Settings → Library (a backup is taken first, and drill cards
keep their history). Re-analysis runs Stockfish over every game again, so a
large library takes a while.

---

# Praxis Chess v1.4.0 — checked reports, two models, nothing deleted

Every pattern, drill and report now comes from the verified diagnosis of every
mistake, and the numbers on them are computed in code.

- **Pattern Report and Training Plan, rebuilt.** Both are tabs in Insights,
  every figure and sentence computed in code: your costliest habit; what goes
  wrong and which tactics caught you, as two lists with your own positions;
  mistakes per 100 of your moves by phase and move number; openings against
  your own average. The plan is a week of three sessions of your own positions,
  each started with one click. `/patterns` and `/training` redirect.
  Model-written paragraphs are optional and off: a checker can hold them to
  the numbers but not to their claims.
- **Verified everywhere.** Insights, drill sessions and cards, free drills,
  Prax's mistake analytics and practice patterns read the rules' verified
  diagnosis instead of the old per-move model's labels, which covered about a
  third of mistakes and were never checked. Practice games are diagnosed when
  they are saved.
- **Two models.** `qwen3:4b-instruct` runs Ask Prax (and the optional report prose);
  the trained `praxis-grid-2b-r3` writes checked commentary. `qwen2.5:7b` is
  retired: in a blind comparison on real data both passed the checker every
  time, the 4B on the first try every time (the 7B 1 in 5), in 6 s instead of
  40 s, and was preferred in 4 of 5 pairs. The default download drops from
  ~8.5 GB to ~3.8 GB.
- **Per-move explanations off.** Recorded as a new settings version; it does
  not make analysed games "outdated". The old text stays in the database and
  is no longer shown.
- **Re-analysis keeps history.** Old mistake rows are kept as superseded,
  drill cards follow their move with their review history (or are paused when
  the move is no longer a mistake), and every earlier version of a diagnosis is
  archived.
- **Automatic backups.** A snapshot a day when something changed, one before
  every re-analysis, a weekly test restore, never deleted. Settings → Backups,
  `docker/backup/restore.sh`.
- **Less to read, nothing lost.** The Pattern Report, Training Plan, Insights,
  Settings and Today show about 40% fewer words. Sentences that repeated a
  number on screen are gone; how a figure is measured sits behind "How this is
  measured"; caveats stay visible. A test checks that every number, move and
  date shown before is still on screen or one tap away.
- **Works by tap.** A move opens its board, opening and date on tap, with
  links to the game and to a drill of that position. Help that was hover-only
  in Settings opens on tap too. One solid button per screen marks the next step.
- **From report to practice and back.** Each cause offers "Drill these
  positions"; a game opened from a move selects it and offers "Drill this
  position"; a finished session leads to the next one or to Progress; review
  cards link to their game. The plan shows each focus since the plan was made
  (silent until there has been enough play) and how reviews of its positions
  went. Every answer in a plan session is now kept per position.
- **Smaller fixes.** Today's focus states real counts; the setup checklist
  folds into Settings → Library and only blockers reach Today; the Insights
  accuracy chart has real dates; report move ranges are full moves; engine
  moves are shown in SAN; dates read "6 Sep"; Play names the most frequent
  proven tactic instead of "positional".

**Upgrading.** Docker: `git pull`, then `docker compose -f docker/compose.yaml
up -d --build` (or pull the new images). From source: set
`report-model: qwen3:4b-instruct` under `praxis-chess.ollama` in
`application.yml` if yours names `qwen2.5:7b`, then restart. The database
updates itself on start; nothing is deleted.

---

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
