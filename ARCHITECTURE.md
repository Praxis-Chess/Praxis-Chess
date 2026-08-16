# Praxis Chess — Architecture & Design

> A personal chess improvement platform that syncs games from Chess.com, analyzes mistakes
> using Stockfish + a local LLM, identifies patterns across your portfolio, and generates
> a personalized training plan. Everything runs locally — no cloud AI calls.

---

## Table of Contents

1. [Design Principles](#design-principles)
2. [System Overview](#system-overview)
3. [Tech Stack](#tech-stack)
4. [High-Level Architecture](#high-level-architecture)
5. [Data Model](#data-model)
6. [Sync Pipeline](#sync-pipeline)
7. [Analysis Pipeline](#analysis-pipeline)
8. [AI Reasoning Flow](#ai-reasoning-flow)
9. [Pattern Aggregation](#pattern-aggregation)
10. [Prax — The Reasoning Layer](#prax--the-reasoning-layer)
11. [Prax — Presence & Voice](#prax--presence--voice)
12. [Frontend Architecture](#frontend-architecture)
13. [Backend Architecture](#backend-architecture)
14. [Progress Tracking](#progress-tracking)
15. [Key Design Decisions](#key-design-decisions)
16. [REST API Reference](#rest-api-reference)
17. [Non-Functional Considerations](#non-functional-considerations)
18. [Future Considerations](#future-considerations)
19. [Hardware Reality](#hardware-reality)
20. [How It All Connects (High Level)](#how-it-all-connects-high-level)

---

## Design Principles

These principles guided every major decision in the system design.

### 1. Structured outputs over free text
The LLM is always asked for strict JSON with a predefined schema. Free-form prose
would require parsing heuristics that break unpredictably. `OllamaAnalysisClient`
sets `"format": "json"` at the API level (Ollama JSON mode) and also strips markdown
code fences in case the model wraps its output anyway.

### 2. LLM analyzes; it does not orchestrate
The LLM receives data and returns an analysis. It never decides what to do next,
calls tools, or drives any control flow. Stockfish determines *which* positions to
analyze; the LLM only explains *why* a move was a mistake.

### 3. Offline by design
No cloud AI, no external analysis APIs. Ollama runs locally, Stockfish is a local
binary. The only external call is the Chess.com public REST API to fetch PGN data.
The system works with no internet connection after initial game sync.

### 4. Aggregate, don't just report
The system doesn't stop at identifying individual mistakes. It aggregates all errors
across an entire game library into statistical distributions (by phase, move range,
motif, opening) and asks the LLM to identify systemic patterns — weaknesses that
appear across many games, not just a single bad game.

### 5. Persistence-first
Every intermediate result (games, move errors, patterns, training plan) is persisted
to PostgreSQL. Analysis is expensive (35–50 min for 74 games); the results must
survive a server restart. The one current exception is the in-memory progress tracker,
which is intentionally ephemeral to avoid per-update DB writes.

---

## System Overview

Praxis has three independent data pipelines that run sequentially:

```
Chess.com API  →  Sync Pipeline  →  Analysis Pipeline  →  Pattern Aggregation
                    (fetch PGN)      (Stockfish + LLM)      (LLM summary + plan)
```

Each pipeline can be triggered manually. Results are stored in PostgreSQL and served
to the React frontend via a Spring Boot REST API.

---

## Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Frontend | React + TypeScript + Vite | React 18 |
| Server state | TanStack Query | v5 |
| Chess rendering | react-chessboard | latest |
| Chess logic (FE) | chess.js | latest |
| Backend | Spring Boot | 3.5.3 |
| Language | Java | 26 |
| ORM | Hibernate / Spring Data JPA | 6.6 |
| Database | PostgreSQL (Docker) | 17 |
| Chess engine | Stockfish | AVX2 binary |
| Chess parsing (BE) | chesslib | 1.3.3 |
| Local LLM runtime | Ollama | latest |
| Analysis model | `qwen2.5:7b` (move + report) | 7B params |
| Reasoning model | `qwen3:4b-instruct` (Prax agent, tool calling) | 4B params |
| Text-to-speech | Kokoro-82M ONNX, CPU-only, FastAPI sidecar | 82M params |
| Prax rendering | Three.js `Points` + custom GLSL | r160+ |
| Async executor | Spring `@Async` + `ThreadPoolTaskExecutor` | — |

The two LLM roles are deliberately separate models. The analysis models run in
batch and the reasoning model runs interactively; sharing one would make a
long re-analysis block every question the player asks, and on a 4 GB card the
two cannot be resident at once. See [Hardware Reality](#hardware-reality).

---

## High-Level Architecture

```mermaid
graph TB
    subgraph Browser
        UI[React + Vite\nport 5173]
    end

    subgraph Backend["Backend — Spring Boot :8086"]
        SC[SyncController]
        AC[AnalysisController]
        DC[DashboardController]
        GC[GamesController]
        ASS[AsyncSyncService]
        SS[SyncService]
        APO[AnalysisPipelineOrchestrator]
        PA[PatternAggregator]
        APT[AnalysisProgressTracker]
        PS[PgnParserService]
        PE[PositionEvaluator]
        MCF[MistakeCandidateFilter]
        OAC[OllamaAnalysisClient]
    end

    subgraph Storage
        PG[(PostgreSQL\nDocker :5432)]
    end

    subgraph Local["Local AI"]
        SF[Stockfish\nAVX2 binary]
        OL[Ollama\n:11434]
        QW[qwen2.5:7b]
    end

    subgraph External
        CC[Chess.com\nPublic API]
    end

    UI -- "REST /api/*" --> SC & AC & DC & GC
    SC --> ASS --> SS
    SS -- "new games" --> APO
    AC -- "reanalyze" --> APO
    APO --> PS --> PE --> MCF --> OAC
    PE -- "FEN positions" --> SF
    OAC -- "HTTP POST" --> OL --> QW
    APO --> PA --> OAC
    SS & APO & PA --> PG
    DC & GC & AC -- "SELECT" --> PG
    SS --> CC
    APT -. "volatile state" .-> AC
```

---

## Data Model

```mermaid
erDiagram
    GAME {
        uuid id PK
        string chess_com_id UK
        string username
        timestamp played_at
        string time_class
        string time_control
        string player_color
        string result
        text raw_pgn
        string opening_eco
        string opening_name
        int white_rating
        int black_rating
        double accuracy
        double max_advantage
        double avg_move_seconds
        enum analysis_status
        timestamp analyzed_at
    }

    MOVE_ERROR {
        uuid id PK
        uuid game_id FK
        int move_number
        string player_color
        string move_played
        string better_move
        string fen_position
        enum severity
        enum tactical_motif
        text explanation
        enum game_phase
        int clock_remaining
        enum analysis_state
    }

    SYNC_HISTORY {
        uuid id PK
        string username
        int year
        int month
        int games_fetched
        timestamp synced_at
    }

    PLAYER_PATTERN {
        uuid id PK
        string username
        int games_analyzed
        timestamp computed_at
        int mistakes_moves1to10
        int mistakes_moves11to20
        int mistakes_moves21to30
        int mistakes_moves31_plus
        int mistakes_opening
        int mistakes_middlegame
        int mistakes_endgame
        json motif_frequency
        json opening_accuracy
        string primary_weakness
        string secondary_weakness
        string tertiary_weakness
        string critical_move_range
        string dominant_motif
        string opening_assessment
    }

    TRAINING_PLAN {
        uuid id PK
        string username
        timestamp generated_at
        int based_on_games
        json plan_json
    }

    GAME ||--o{ MOVE_ERROR : "has"
    GAME }o--|| SYNC_HISTORY : "synced via"
```

**Key database indexes:**

```sql
-- Query performance for the most frequent access patterns
CREATE INDEX idx_games_username          ON games(username);
CREATE INDEX idx_games_analysis_status  ON games(username, analysis_status);
CREATE INDEX idx_move_errors_game_id    ON move_errors(game_id);
CREATE INDEX idx_move_errors_severity   ON move_errors(severity);
CREATE INDEX idx_move_errors_motif      ON move_errors(tactical_motif);
CREATE INDEX idx_player_patterns_user   ON player_patterns(username);
```

**Analysis status lifecycle:**

```mermaid
stateDiagram-v2
    [*] --> PENDING : game synced from Chess.com
    PENDING --> ANALYZING : pipeline picks up game
    ANALYZING --> ANALYZED : all moves processed
    ANALYZING --> FAILED : parse error / no moves
    FAILED --> PENDING : Re-Analyze All clicked
    ANALYZED --> PENDING : Re-Analyze All clicked
```

---

## Sync Pipeline

Triggered by **Sync Now** (new months only) or **Re-Sync** (forces re-check of last 3 months).

```mermaid
sequenceDiagram
    actor User
    participant FE as React Frontend
    participant SC as SyncController
    participant ASS as AsyncSyncService
    participant SS as SyncService
    participant SH as SyncHistoryRepo
    participant CC as Chess.com API
    participant DB as PostgreSQL
    participant APO as AnalysisPipeline

    User->>FE: clicks "Sync Now"
    FE->>SC: POST /api/sync
    SC->>SS: enqueueSyncFlag()
    SC->>ASS: syncAsync() [@Async]
    SC-->>FE: 202 Accepted (instant)

    Note over FE: banner shows "Fetching games..."

    ASS->>SS: sync(username, months)
    SS->>SS: syncing = true

    loop for each month (e.g. 3 months)
        SS->>SH: existsByUsernameAndYearAndMonth?
        alt month already synced
            SH-->>SS: true → skip
        else not synced
            SS->>CC: GET /player/{user}/games/{year}/{month}
            CC-->>SS: List[ChessComGame] JSON
            loop for each game
                SS->>DB: existsByChessComId?
                alt game already in DB
                    SS->>DB: backfill accuracy if missing
                else new game
                    SS->>DB: save Game (status=PENDING)
                end
            end
            SS->>DB: save SyncHistory record
        end
    end

    alt new games found
        SS->>APO: analyzeGames(newGames) [@Async queued]
    end

    SS->>SS: syncing = false
```

**Re-Sync** additionally calls `clearSyncHistory()` before `sync()`, forcing all months
to be re-fetched from Chess.com regardless of prior sync history. Only games with a new
`chess_com_id` (not already in the DB) are inserted.

### Chess.com API Details

| Endpoint | Used for |
|---|---|
| `GET https://api.chess.com/pub/player/{username}/games/{year}/{month}` | Fetch all games for a calendar month |
| `GET https://api.chess.com/pub/player/{username}/stats` | Player rating history (used by Dashboard) |

**Important constraints:**
- A `User-Agent` header is required on every request; Chess.com returns 403 without it.
- Requests must be made serially — parallel requests trigger 429 rate limiting.
- The API returns HTTP 304 Not Modified if the month hasn't changed since the last fetch;
  the `SyncHistory` table's year/month deduplication makes this largely a non-issue.

---

## Analysis Pipeline

The core pipeline. Runs asynchronously on a single-threaded executor so Stockfish and
Ollama are never called concurrently.

```mermaid
flowchart TD
    START([analyzeGames called\nwith List of Games]) --> TRACK[progressTracker.start\nclear queued flag]
    TRACK --> LOOP{for each game}

    LOOP --> PARSE[PgnParserService\nParse raw PGN via chesslib\nExtract moves + FEN + clock]
    PARSE --> EMPTY{moves empty?}
    EMPTY -- yes --> FAILED[status = FAILED\nsave to DB]
    FAILED --> NEXT

    EMPTY -- no --> STOCK[PositionEvaluator\nSend each FEN to Stockfish\nmovetime 100 fast pass]
    STOCK --> FILTER[MistakeCandidateFilter\nSkip book moves ply ≤ 12\nIdentify score drops ≥ 1.0p\nMax 8 candidates]
    FILTER --> SORT[Sort by swing severity\nworst first]
    SORT --> OLLAMA_LOOP{for each candidate}

    OLLAMA_LOOP -- "top 3 → MultiPV + Ollama" --> MULTIPV[Stockfish depth-18\nMultiPV 3\nGet top engine lines]
    MULTIPV --> PROMPT[Build prompt:\nFEN + move + better_move +\neval delta + engine lines]
    PROMPT --> LLM[POST to Ollama\nqwen2.5:7b\noverlapped via BlockingQueue]
    LLM --> PARSE_JSON[Parse JSON response:\nexplanation, tactical_motif]
    PARSE_JSON --> SAVE_ERR[Save MoveError\nstate=EXPLAINED]

    OLLAMA_LOOP -- "remaining → no LLM" --> SAVE_BARE[Save MoveError\nseverity from score\nstate=SKIPPED]

    SAVE_ERR --> MORE_CANDIDATES{more candidates?}
    SAVE_BARE --> MORE_CANDIDATES
    MORE_CANDIDATES -- yes --> OLLAMA_LOOP
    MORE_CANDIDATES -- no --> MARK[game.status = ANALYZED\ngame.analyzedAt = now\nsave to DB]

    MARK --> INC[progressTracker.increment]
    INC --> NEXT{more games?}
    NEXT -- yes --> LOOP
    NEXT -- no --> PATTERN[PatternAggregator.recompute\nset patternGenerating = true]
    PATTERN --> DONE([progressTracker.finish])
```

### Key thresholds (MistakeCandidateFilter)

| Parameter | Value | Meaning |
|---|---|---|
| `MISTAKE_THRESHOLD` | 1.0 pawns | Minimum score drop to be flagged |
| `BLUNDER_THRESHOLD` | 2.0 pawns | Score drop that classifies as a blunder |
| `TIME_PRESSURE_CUTOFF` | 30 seconds | Clock remaining below this → time pressure flag |
| `MAX_CANDIDATES` | 8 per game | Upper bound on mistakes identified |
| `BOOK_MOVES_PLY` | 12 ply (6 full moves) | Opening moves skipped — engine evals are near-equal and noisy |
| `MAX_OLLAMA_CALLS_PER_GAME` | 3 per game | Ollama only called for the 3 worst mistakes |

### Why only 3 Ollama calls per game?

Each Ollama call takes 7–16 seconds. With 74 games × 3 calls = 222 Ollama calls ≈ 35–50 minutes total.
Remaining mistakes (up to 5 more) are saved without natural language explanation to preserve
pattern count accuracy without multiplying inference time.

---

## AI Reasoning Flow

Three separate AI calls happen across the full pipeline, each using `qwen2.5:7b` via Ollama.

### Call 1 — Per-Move Analysis (×3 per game)

**Input prompt:**
```
You are a chess coach. Explain concisely why the played move is worse than the engine's move.
Do NOT suggest moves — the engine move is already determined.

Position (FEN): rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2
Move played: Nc6  (eval: +0.15 → -0.45, lost 0.60 pawns)
Engine best:  d5
Engine top lines:
  1. d5 e5d5 d8d5 b1c3
  2. Nf6 d2d4 e5d4 f3d4
  3. b8c6 d2d4 c6d4 f3d4
Phase: OPENING | Move #4 | Player: black

Respond ONLY with JSON:
{
  "explanation": "<one sentence: why the played move is bad>",
  "tactical_motif": "FORK" | "PIN" | "SKEWER" | "BACK_RANK" | "DISCOVERED_ATTACK" | "HANGING_PIECE" | "POSITIONAL" | "OTHER"
}
```

**Output (parsed into MoveError):**
```json
{
  "explanation": "Nc6 allows White to seize central control; the engine's d5 immediately contests the center.",
  "tactical_motif": "POSITIONAL"
}
```

Severity (`BLUNDER` / `MISTAKE`) and `better_move` (UCI) are set by Stockfish/Java — the LLM only supplies the human-readable *why*.

```mermaid
flowchart LR
    FEN[FEN position\nbefore move] --> PT[PromptTemplates\n.moveAnalysis]
    MOVE[SAN move\nplayer color\ngame phase\nmove number] --> PT
    PT --> OL[Ollama\nqwen2.5:7b]
    OL --> JSON[Raw JSON string]
    JSON --> STRIP[Strip markdown fences\nif present]
    STRIP --> PARSE[Jackson ObjectMapper\ndeserialize]
    PARSE --> RES[OllamaResult\nexplanation tacticalMotif\nanalysisState]
```

**JSON mode enforcement:** `OllamaAnalysisClient` sends `"format": "json"` in every
request body, activating Ollama's grammar-constrained JSON mode. This forces the model
to produce syntactically valid JSON. The fence-stripping fallback handles the edge case
where the model prepends ` ```json ` despite the format constraint.

---

### Call 2 — Pattern Report (×1 after all games analyzed)

Aggregated statistics across all games are compiled programmatically, then sent to the LLM
to produce a natural language coaching summary.

**What gets aggregated before the LLM call:**

```mermaid
flowchart TD
    ERRORS[All MoveErrors\nacross all analyzed games] --> MR[Move range distribution\n1-10, 11-20, 21-30, 31+]
    ERRORS --> PH[Phase breakdown\nOpening, Middlegame, Endgame]
    ERRORS --> MF[Motif frequency map\nFORK:12, PIN:8, etc.]
    ERRORS --> OA[Opening accuracy map\nC41:errors, B07:errors, etc.]
    MR & PH & MF & OA --> PROMPT[PromptTemplates\n.patternReport]
    PROMPT --> OL[Ollama\nqwen2.5:7b]
    OL --> PR[PatternReportResult\nprimary_weakness\nsecondary_weakness\ncritical_move_range\ndominant_motif\nopening_assessment]
    PR --> DB[(PlayerPattern\nsaved to DB)]
```

**Example output from the LLM:**
```json
{
  "primary_weakness": "You consistently blunder in endgames after move 30 under time pressure.",
  "secondary_weakness": "Hanging pieces in the middlegame are your most frequent tactical oversight.",
  "tertiary_weakness": "Your opening play in B-series defenses leads to early positional concessions.",
  "critical_move_range": "moves 21-30",
  "dominant_motif": "HANGING_PIECE",
  "opening_assessment": "Strong in C41 Philidor but frequently misplays B07 Pirc structures."
}
```

---

### Call 3 — Training Plan (×1 on demand)

Generated when the user visits the Training Plan page and triggers generation.

```mermaid
flowchart TD
    PAT[PlayerPattern JSON\nweaknesses + motifs] --> TP[PromptTemplates\n.trainingPlan]
    OS[Opening stats JSON\nwin rates per ECO] --> TP
    TP --> OL[Ollama\nqwen2.5:7b]
    OL --> PLAN[TrainingPlan JSON\n3 priorities with focus+action+reason\nopenings to drill\ntactical patterns to study]
    PLAN --> DB[(TrainingPlan\nsaved to DB)]
```

---

## Pattern Aggregation

After all games are analyzed, `PatternAggregator.recompute()` runs in the same async thread:

1. Loads all `MoveError` records for the user's analyzed games
2. Computes statistical distributions (move range, phase, motif) purely in Java
3. Calls Ollama once with the aggregated data
4. Saves `PlayerPattern` to DB (replaces any prior pattern for this user)

The pattern is the foundation for both the Dashboard's "Mistakes by Phase" bars and the
"Today's Focus" banner, and feeds directly into the Training Plan generation.

---

## Insights & Drills

Both features are **pure read-side aggregation** — no new AI calls. They reuse data already
persisted during analysis, so they are effectively free to compute at request time for a
single-user library of a few hundred games.

### Insights (`InsightsService` → `GET /api/insights`)

Loads all games + move errors once and computes, entirely in Java:

| Metric | Derived from |
|---|---|
| Winning-position conversion | `game.max_advantage ≥ 2.0` vs. `game.result` |
| Time-trouble blunder rate | `MoveError.severity = BLUNDER` with `clock_remaining ≤ 30s` |
| Avg time per move | `game.avg_move_seconds` (mean across timed games) |
| Accuracy trend | `game.accuracy` ordered by `played_at`, 10-game rolling average |
| Opponent strength | player vs. opponent rating bucketed at ±50 (`Stronger`/`Even`/`Weaker`) |
| Time-of-day / weekday | `played_at` hour and day-of-week win rates |
| Missed tactics | `MoveError.tactical_motif` frequency |
| Tilt / resilience | win rate in the game after a win vs. after a loss |
| Openings | win rate + avg accuracy grouped by `opening_eco` |

`max_advantage` (highest eval reached, player's perspective) and `avg_move_seconds` (from PGN
`[%clk]` deltas, increment-aware) are computed once in `GameAnalysisTransactionService.analyzeOne`
and stored on the `Game` row.

### Drills (`DrillsController` → `GET /api/drills`)

Every `MoveError` with a non-null `better_move` (engine UCI from MultiPV) is already a complete
puzzle: the stored `fen_position` is the position before the blunder, and `better_move` is the
solution. The endpoint returns a shuffled, capped set; the frontend `Drills` page replays the
position with `react-chessboard` and validates the user's move against the engine move using
`chess.js`. No backend state is added — drills are a projection of existing rows.

---

## Prax — The Reasoning Layer

`com.praxis.prax` is a tool-calling agent that answers questions about the
player's own games. It is architecturally separate from the analysis pipeline:
the pipeline writes rows, Prax reads them and reasons over them.

The governing constraint is that **a small local model must never be trusted to
originate a fact**. Every layer below exists to make invention structurally
impossible rather than merely discouraged.

```
prax/
  intelligence/ChessIntelligence      deterministic analytics over Postgres
  tools/ToolRegistry, ToolResult      11 tools + JSON schemas for Ollama
  chat/PraxAgent                      the bounded loop
  chat/OllamaChatClient               /api/chat with tools
  chat/PraxPrompt                     voice + rules of evidence
  chat/PraxResponse, EvidenceValidator  the reply contract and its enforcement
  evidence/PositionEvidenceBuilder    board-computed chess facts
  evidence/ChessFact, Evidence        fact and citation types
```

### The bounded loop

`PraxAgent.run()` is bounded in three dimensions — **6 turns, 10 tool calls,
120 s wall clock**. On breach it withholds the tool list, which is what forces a
final answer. An unbounded loop driven by a 4B model is a hang, not a feature.

Two subtleties that were load-bearing bugs before they were fixed:

- **Call ids are run-scoped, not response-scoped.** The chat client restarts its
  index every response, so three turns each produced `tc_1` and every citation
  resolved to whichever tool ran last — silently breaking the entire evidence
  guarantee while still looking valid.
- **Only the current turn's results are appended** to the message list. Re-sending
  the whole map each turn duplicated every earlier result, overflowed `num_ctx`,
  and Ollama answers overflow by truncating — producing an empty final message.

### The tools

| Tool | Returns | Provenance |
|---|---|---|
| `get_player_profile` | rating, games, accuracy, colour splits | PLAYER_DATA |
| `get_opening_performance` | per-opening games, win rate, accuracy | PLAYER_DATA |
| `get_phase_performance` | error counts by opening/middlegame/endgame | PLAYER_DATA |
| `get_mistake_patterns` | motif frequency, filterable by phase | PLAYER_DATA |
| `find_games` | game list by opening, colour, result, recency | PLAYER_DATA |
| `get_game` | one game with up to 8 errors, blunders first | PLAYER_DATA |
| `find_mistakes` | **worst individual moves, worst first** | PLAYER_DATA |
| `recommend_openings` | deterministic ranking with rationale | PLAYER_DATA |
| `get_progress` | drill deck health and recall rates | PLAYER_DATA |
| `analyze_position` | engine eval, best move, `verifiedFacts` | ENGINE |

`find_mistakes` closed a real gap: "show me my worst blunder" had no tool that
answered it. The aggregates report motif frequencies and `find_games` filters
whole games, so the model had to guess a game and hope it contained the blunder.
It guessed wrong every time, and twice covered for that by inventing an
explanation. Ordering is by **win-percentage drop**, not severity label — a
blunder that threw away a won position outranks one played in a lost game.

### Verified facts, not engine numbers

`analyze_position` does not hand the model a FEN and an evaluation. It returns
`verifiedFacts`: statements computed from the board with chesslib, each with an
id. Kinds include `WALKS_INTO_MATE`, `MISSED_MATE`, `EVAL_LOSS`, `NO_LOSS`,
`BEST_MOVE`, `PLAYED_MOVE`, `CAPTURE`, `CHECK`, `ATTACKS`, `HANGS`, `DEFENDED`,
`ESCAPES_ATTACK`, `PRINCIPAL_VARIATION`.

Three rules make these safe to show a player:

1. **Every eval names the side.** "Black is ahead by 6.92 pawns", never "-6.92
   from White's perspective". Signed-perspective phrasing is correct and
   unreadable, and the model inverted it — reading −6.77 as "Black was deeply
   behind" for a position Black was winning.
2. **Mate scores keep their owner.** `Math.abs(score) >= 90` made "I have mate"
   and "I get mated" the same value, so a move that walked into mate in 2 was
   reported as costing nothing. Scores are flipped into the mover's frame before
   any comparison.
3. **Both sides of a comparison use the same search.** `StockfishService.evaluate()`
   searches `movetime 100`; `evaluateWithMultiPV()` searches `go depth N`.
   Comparing them is meaningless and biased — the shallow search overstates, so
   the played move always looked as good as the engine's choice.
   `evaluateAtDepth()` exists solely to make the comparison valid.

### The auto-chain

`find_mistakes` names the move but says nothing about why it was bad, and the
model would not take the next step on its own — told the reason was
unavailable, it relayed *that* to the player ("I would need to call
analyze_position") rather than calling it.

`ToolRegistry.followUp()` declares the dependency and `PraxAgent` executes it.
The chained `analyze_position` gets **its own call id and its own step entry**,
so provenance stays exact and the extra call is visible in the UI rather than
hidden. Bounded to the top row, one extra engine run, `depth 14`.

### Evidence and citation

```mermaid
flowchart LR
    T[Tool result\ncallId · provenance · sampleSize] --> M[Model cites\nlabel · value · callId]
    M --> V{EvidenceValidator}
    V -->|id resolves| K[Kept]
    V -->|no resolvable source| D[Dropped]
    V -->|tool errored| D
    V -->|PLAYER_DATA with no digit| D
    K -->|sampleSize < 5| U[Kept, marked underpowered]
```

Three provenance classes never blur: `PLAYER_DATA` (Postgres + deterministic
analyzers), `ENGINE` (Stockfish), `KNOWLEDGE` (general chess). The frontend tags
the latter two so an engine number is never mistaken for something measured
about the player.

A claim whose `callId` does not resolve **never reaches the player**. This is
the mechanism, not the instruction — a prompt asking a model not to invent
statistics is a request.

### Template rendering

The final defect after all of the above was the model paraphrasing correct facts
into incorrect English. So it no longer writes them.

The model returns `facts: ["f8", "f2"]` — ids only — and the backend renders
those statements **word for word** into a `findings` array shown above the
prose. Selection is the model's; wording is not. The worst it can do is pick a
less relevant *true* statement. If it selects nothing usable, `PraxAgent` falls
back to payload order, which is sorted decisive-first, so the verified account
reaches the player even when the model's own output is poor.

> **Known limitation.** The prompt instructs the model not to describe the
> position in its prose, since the findings already do. It does not reliably
> comply. The prose is therefore redundant rather than load-bearing — a player
> reading only the findings gets an accurate account. Closing this properly
> means making the final call without position data in context, so there is
> nothing to describe.

---

## Prax — Presence & Voice

Prax is also a visual presence: a single persistent WebGL canvas rendering
~1,600 particles as one `THREE.Points` draw call, present on every page.

```
prax/
  core/         constants (all tunables), seeded RNG, event types
  geometry/     particle generation — seeded, so the form is stable across reloads
  motion/       Spring, Envelope, EnvelopeFollower primitives; state → motion params
  renderer/     PraxCanvas, createPraxPoints, GLSL shaders
  state/        runtime FSM, render policy, progress narration
  anchor/       per-page anchor registry and relocation controller
  interaction/  pointer, dwell-based focus intent
  voice/        Kokoro + mock voice adapters, audio graph
  ui/           PraxStack, PraxAsk, PraxProgress, PraxThought, PraxHitTarget
```

**Three-layer separation.** Semantic state (an FSM over `dormant`, `aware`,
`thinking`, `insight`, `speaking`) drives motion parameters, which drive GPU
uniforms. React never runs the animation loop — it mounts the canvas and emits
events into it.

**The vertex shader assembly order is a fixed contract:** breathing → drift
(coherence-decorrelated) → insight cluster contraction → analyze sweep band →
sync crater → expansion → pointer. Deformation uses fBm octaves where amplitude
falls as frequency rises; equal-amplitude waves cancel back into a sphere.

**Narration is deterministic.** During analysis, `progressNarrator` produces
operational, observational and resolved messages from structured progress
events — not from the LLM. A message about a run that is happening now should
not wait on a model, and cannot be allowed to invent numbers about it.

**Voice** is a local Kokoro-82M ONNX model in a FastAPI sidecar (`tts-service/`,
port 8087), deliberately **CPU-only** — the GPU is reserved for Ollama. It is
optional: with `praxis-chess.tts.enabled: false` or the service down, Prax stays
silent and fully functional.

> Adding a behaviour, wiring a new signal, or reshaping the organism itself is
> covered in depth by **[PRAX.md](PRAX.md)** — the event→state→motion→GPU
> pipeline, the motion primitives, the vertex assembly contract, the DOM
> placement system, and the invariants that are easy to break by accident.

---

## Frontend Architecture

```mermaid
graph TD
    APP[App.tsx\nRouter + QueryClientProvider] --> LAYOUT[Layout.tsx\nNav + SyncStatusBanner]
    LAYOUT --> DASH[Dashboard.tsx]
    LAYOUT --> GAMES[GameList.tsx]
    LAYOUT --> GAME[GameAnalysis.tsx]
    LAYOUT --> INS[Insights.tsx]
    LAYOUT --> DRILL[Drills.tsx]
    LAYOUT --> PAT[PatternReport.tsx]
    LAYOUT --> TRAIN[TrainingPlan.tsx]

    LAYOUT --> BANNER[SyncStatusBanner.tsx\nalways mounted]

    BANNER --> uSS[useSyncStatus\npolls /api/sync/status\nevery 3s when SYNCING]
    BANNER --> uAP[useAnalysisProgress\npolls /api/analysis/progress\nevery 2s when running/queued]

    GAME --> CB[ChessBoard.tsx\nreact-chessboard\ncustomArrows prop]
    GAME --> MEC[MoveErrorCard.tsx]
    GAME --> uGA[useGameAnalysis\nfetches move errors]

    DASH --> RC[RatingChart.tsx\nRecharts LineChart]
    DASH --> OD[OpeningDistribution.tsx\nclick-to-expand]
    DASH --> BWO[BestWorstOpenings.tsx]
    DASH --> RG[RecentGames.tsx]

    uSS & uAP --> QC[TanStack Query\nclient cache]
    QC --> API[api/client.ts\nfetch wrapper]
    API --> BE[Backend :8086]
```

### Key hooks

| Hook | Purpose | Polling |
|---|---|---|
| `useAnalysisProgress` | Polls `/api/analysis/progress` | 2s when `running`, `queued`, or `patternGenerating`; 20s warmup after any mutation |
| `useSyncStatus` | Polls `/api/sync/status` | 3s when `state === SYNCING` or `ANALYZING` |
| `usePatternReport` | Fetches pattern once | No polling |
| `useGameAnalysis` | Fetches move errors for a game | No polling |

### Chess board arrows (GameAnalysis)

When a mistake card is clicked, `chess.js` converts SAN notation to board squares
by replaying the position from the stored FEN:

```
FEN + SAN  →  chess.move(san)  →  { from: 'e2', to: 'e4' }
             (move_played)         red arrow

FEN + SAN  →  chess.move(san)  →  { from: 'd7', to: 'd5' }
             (better_move)         green arrow
```

---

## Backend Architecture

```mermaid
graph TD
    subgraph API["Controllers (REST)"]
        SC[SyncController\nPOST /api/sync\nPOST /api/sync/force-resync\nGET /api/sync/status]
        AC[AnalysisController\nPOST /api/analysis/reanalyze\nGET /api/analysis/progress\nGET /api/analysis/{gameId}]
        DC[DashboardController\nGET /api/dashboard/stats]
        GC[GamesController\nGET /api/games]
    end

    subgraph Services
        ASS[AsyncSyncService\n@Async wrapper]
        SS[SyncService\nsync + forceResync\nsyncQueued flag]
        APO[AnalysisPipelineOrchestrator\n@Async analyzeGames]
        APT[AnalysisProgressTracker\nrunning, queued, patternGenerating\nvolatile + AtomicInteger]
        PA[PatternAggregator\nrecompute]
    end

    subgraph Analysis
        PS[PgnParserService\nchesslib integration]
        PE[PositionEvaluator\nStockfish subprocess]
        MCF[MistakeCandidateFilter\nthreshold filtering]
        OAC[OllamaAnalysisClient\nHTTP to :11434]
        PT[PromptTemplates\nstatic factory methods]
    end

    subgraph Repositories
        GR[GameRepository]
        MER[MoveErrorRepository]
        SHR[SyncHistoryRepository]
        PPR[PlayerPatternRepository]
        TPR[TrainingPlanRepository]
    end

    SC --> ASS --> SS
    SC --> SS
    AC --> APO
    AC --> APT
    APO --> PS & PE & MCF & OAC & PA
    OAC --> PT
    SS & APO & PA --> GR & MER & SHR & PPR
    DC & GC --> GR & MER & PPR
```

### Thread model

```mermaid
sequenceDiagram
    participant HT as HTTP Thread\n(Tomcat)
    participant AE as analysisExecutor\n(1 thread)
    participant SF as Stockfish Process
    participant OL as Ollama HTTP

    Note over HT: POST /api/analysis/reanalyze
    HT->>HT: reset all games to PENDING\ndelete move errors\nset queued=true
    HT->>AE: submit analyzeGames() task
    HT-->>HT: return 202 immediately

    Note over AE: analysisExecutor thread
    AE->>AE: progressTracker.start(74)\nqueued=false, running=true

    loop 74 games
        AE->>SF: "go movetime 100" × N positions (fast pass)
        SF-->>AE: centipawn score per position
        Note over AE: filter candidates (skip ply ≤ 12, max 8)

        Note over AE,OL: top 3: Stockfish MultiPV + Ollama overlap via LinkedBlockingQueue
        par Stockfish MultiPV on analysisExecutor thread
            loop top 3 candidates
                AE->>SF: "go depth 18" MultiPV 3
                SF-->>AE: top 3 engine lines + bestmove
                AE->>AE: push enriched candidate to queue
            end
        and Ollama consumer on CompletableFuture thread
            loop top 3 candidates
                AE->>OL: POST /api/generate\n{FEN + move + engine lines}
                OL-->>AE: {explanation, motif}
            end
        end
        AE->>AE: join consumer thread\npersist in REQUIRES_NEW TX
    end

    AE->>AE: progressTracker.finish\npatternGenerating=true
    AE->>OL: POST /api/generate (pattern report)
    OL-->>AE: pattern JSON
    AE->>AE: progressTracker.finish
```

The `analysisExecutor` has `corePoolSize=1, maxPoolSize=1, queueCapacity=50`. The main analysis
thread drives Stockfish (serialized — single subprocess with synchronized methods). Ollama
inference runs on a separate `CompletableFuture.supplyAsync()` consumer thread drawn from the
JVM common pool, overlapping GPU inference with CPU evaluation via a
`LinkedBlockingQueue<Optional<CandidateMove>>` (poison-pill termination). Stockfish and Ollama
no longer block each other: the CPU runs the next MultiPV search while the GPU explains the
previous candidate.

---

## Progress Tracking

`AnalysisProgressTracker` is a Spring `@Component` singleton holding all pipeline state
in-memory via volatile fields and an `AtomicInteger`.

```mermaid
stateDiagram-v2
    [*] --> IDLE : initial state
    IDLE --> QUEUED : setQueued(true)\ncalled before async submit
    QUEUED --> RUNNING : start(totalGames)\ncalled inside async thread
    RUNNING --> PATTERN_GEN : all games done\nsetPatternGenerating(true)
    PATTERN_GEN --> IDLE : finish()

    note right of QUEUED
        isRunning() = false
        isQueued() = true
        Frontend polls because queued=true
    end note

    note right of RUNNING
        isRunning() = true
        completed increments per game
        ETA = elapsed / completed × remaining
    end note
```

**Why in-memory instead of DB?**

Progress updates happen every few seconds per game. Writing to PostgreSQL on every
increment would add unnecessary DB load and latency. Volatile fields give instant reads
at zero cost.

**DB fallback on restart:** If the server restarts mid-analysis, the in-memory `running`/`queued`
flags reset to false. The `/api/analysis/progress` endpoint has a DB fallback: if the in-memory
state says idle but `PENDING` or `ANALYZING` games exist in PostgreSQL, it reports `queued=true`
so the banner stays active until the user manually triggers **Analyze Pending** to resume.

**The race condition fix:**

Without the `queued` flag, there was a window between the HTTP response returning and
the async thread calling `start()`. The frontend would poll once, see `running=false`,
stop polling, and the progress banner would never appear. Setting `queued=true` synchronously
in the controller (before submitting the async task) means the very first poll after
`onSuccess` already sees activity.

---

## Key Design Decisions

### 1. Two-stage analysis: Stockfish evaluates, Ollama explains

Stockfish is deterministic and fast for numerical evaluation (centipawn scores per position).
Ollama provides the human-readable "why this was a mistake and what to play instead."
Neither alone is sufficient — Stockfish can't explain, Ollama can't reliably calculate.

### 2. Max 3 Ollama calls per game

With 74 games and 7–16 seconds per call, full Ollama coverage would take 2–3 hours.
Limiting to the 3 worst mistakes per game (sorted by centipawn swing) captures the
most instructive moments while keeping total analysis time under 50 minutes.
The remaining mistakes (up to 5) are still stored for pattern counting — just without
natural language explanation (`analysis_state = SKIPPED`).

### 3. Single-threaded analysis executor

Ollama's `qwen2.5:7b` loads 2.4 GB into non-pageable host RAM and 4.0 GB into VRAM.
Running two Ollama calls in parallel would require 8 GB VRAM (unavailable on RTX 3050)
and could exhaust system RAM. One thread serializes all AI work safely.

### 4. No Flyway — `ddl-auto: update`

This is a personal single-user tool, not a multi-environment deployment. Hibernate
auto-creates and migrates the schema on boot. Flyway adds ceremony (migration files,
version tracking) that slows iteration without meaningful benefit at this scale.

### 5. Chess.com sync history table

Rather than comparing every game UUID on every sync, `SyncHistory` records which
year/month combinations have been fetched. Sync checks this first and skips months
already processed — reducing Chess.com API calls from O(all games) to O(new months).
`forceResync` clears these records before syncing to force a complete re-check.

### 6. Per-game transactions via a separate bean

`GameAnalysisTransactionService.analyzeOne()` is annotated `@Transactional(REQUIRES_NEW)` in
a separate Spring bean (required because `@Async` and `@Transactional` cannot coexist safely
on the same bean proxy). Each game commits independently. This means:
- A JVM kill loses only the game in-flight; all prior games remain `ANALYZED`
- On the next startup, `@PostConstruct` in the orchestrator resets any `ANALYZING` games
  back to `PENDING` so they are immediately available for pickup
- Use the **Analyze Pending** button (non-destructive) to resume without re-running
  already-completed games

### 7. Snake_case JSON via Jackson naming strategy

The Spring Boot backend uses `MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES` and
`PropertyNamingStrategies.SNAKE_CASE`. Java camelCase fields (`percentComplete`)
serialize to `percent_complete` automatically, matching the TypeScript interface
conventions on the frontend without manual `@JsonProperty` annotations.

### 8. Async sync with queued flag

Prior to the refactor, `SyncService.sync()` ran synchronously on the HTTP thread,
blocking it for the duration of all Chess.com API calls (up to several minutes).
Buttons appeared frozen. Moving sync to `@Async` via a separate `AsyncSyncService`
bean (required because `@Async @Transactional` cannot coexist on the same bean)
returns `202 Accepted` immediately. The `syncQueued` volatile flag signals the frontend
before the async thread has had a chance to set `syncing=true`, eliminating the
race condition on the status poll.

---

## REST API Reference

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/sync` | Trigger incremental sync (new months only). Body: `{username, months}`. Returns 202. |
| `POST` | `/api/sync/force-resync` | Force re-fetch last N months from Chess.com. Body: `{months}`. Returns 202. |
| `GET` | `/api/sync/status` | Returns `{state, games_fetched, games_analyzed, games_pending, last_synced_at}` |
| `GET` | `/api/sync/new-count` | Games available upstream that are not yet stored — drives the "new games" banner |
| `POST` | `/api/analysis/reanalyze` | Reset all games to PENDING and queue full reanalysis. Returns 200. |
| `POST` | `/api/analysis/analyze-pending` | Queue only PENDING games (non-destructive resume). Returns `{message, games_queued}`. |
| `GET` | `/api/analysis/progress` | Returns `{running, pattern_generating, queued, completed, total, percent_complete, eta_seconds}` |
| `GET` | `/api/analysis/{gameId}` | Returns list of `MoveError` records for a game |
| `GET` | `/api/insights` | Aggregated analytics: conversion, time management, accuracy trend, opponent strength, time-of-day/weekday, missed tactics, tilt, per-opening stats |
| `GET` | `/api/drills?limit=N` | Randomized set of tactics drills built from the player's own mistakes (FEN + engine best move) |
| `GET` | `/api/games` | Paginated game list with filters (status, color, result, time_class) |
| `GET` | `/api/games/{id}` | Full game detail including PGN |
| `GET` | `/api/dashboard/stats` | Aggregated stats: win rates, accuracy distribution, opening performance, rating history |
| `GET` | `/api/patterns` | Current `PlayerPattern` for the configured user |
| `GET` | `/api/training-plan` | Most recent `TrainingPlan` |
| `POST` | `/api/training-plan/generate` | Generate a new training plan from current pattern data |
| `POST` | `/api/analysis/stop` | Request a cooperative stop of the running analysis. Returns 200. |
| `POST` | `/api/games/{id}/analyze` | Queue a single game for analysis |
| `GET` | `/api/today` | The day's session: what is due, what to work on |
| `GET` | `/api/progress` | Drill deck health — cards due, learning, review, per-phase recall |
| `GET` | `/api/sessions/{id}` | Drill session state |
| `GET` | `/api/sessions/{id}/next` | Next card in the session |
| `POST` | `/api/sessions/{id}/attempt` | Submit an attempt + FSRS rating. Body: `{card_id, rating, ...}` |
| `GET` | `/api/prax/status` | `{available}` — probed at startup; false hides the chat entry point |
| `POST` | `/api/prax/ask` | Ask Prax. Body: `{question}`. Returns `{answer, findings, evidence, steps, partial, model}` |
| `POST` | `/api/prax/reset` | Clear conversation history. Returns 204. |
| `GET` | `/api/voice/status` | `{available}` — whether the TTS sidecar is reachable |
| `POST` | `/api/voice/speak` | Synthesize speech. Body: `{text, voice, speed}`. Returns WAV audio. |

All endpoints return `application/json` (except `/api/voice/speak`, which returns
`audio/wav`). No authentication — single-user local tool.
Frontend proxies `/api/*` to `http://localhost:8086` via Vite's `server.proxy` config.

---

## Non-Functional Considerations

### Performance

- **Bottleneck:** Ollama inference at 7–16s per call. Analysis throughput is bounded by GPU
  speed, not Java, Stockfish, or the database.
- **Database:** All heavy queries (`MoveError` aggregations, game filtering) are indexed.
  PostgreSQL runs in Docker with data on the D: NVMe partition.
- **Stockfish:** AVX2 binary. Runs at depth 18, which takes milliseconds per position on a
  modern CPU. Not the bottleneck.
- **Frontend:** TanStack Query caches all fetched data client-side. Polling only activates
  when work is in progress (`running || queued || pattern_generating`).

### Reliability

- **Malformed JSON from Ollama:** `OllamaAnalysisClient` catches JSON parse errors and
  retries up to 3 times with exponential backoff (1 s, 2 s). On persistent failure the move
  error is saved with `analysis_state = FAILED` — still counted in pattern aggregation but
  without a natural-language explanation.
- **Chess.com rate limiting (429):** `ChessComApiClient` retries up to 3 times with 10 s / 20 s
  backoff before returning an empty result for that month.
- **Chess.com 304 Not Modified:** The `SyncHistory` deduplication means most months are
  skipped before any HTTP call is made; 304 handling is a secondary safety net.
- **Backend restart mid-analysis:** Each game commits in its own `REQUIRES_NEW` transaction.
  A JVM kill loses only the in-flight game. On next startup `@PostConstruct` in the orchestrator
  resets any `ANALYZING` games to `PENDING`. Use **Analyze Pending** to resume without
  re-running completed games.
- **Stockfish process death:** `StockfishService.ensureAlive()` is called before every
  synchronized operation. If the process has died, it is restarted via `destroy()` + `init()`
  before continuing.

### Privacy

- No game data or user information is sent to any external AI service.
- Chess.com credentials are stored only in `application.yml` (gitignored).
- PostgreSQL runs locally in Docker; no cloud DB.
- Ollama runs fully locally; inference stays on the machine.

---

## Future Considerations

| Feature | Description |
|---|---|
| **Fine-tuned model** | Fine-tune a smaller model (e.g. `qwen2.5:1.5b`) on chess coaching data using Unsloth. Would reduce inference time from 7–16s to ~2s, cutting total analysis time from 50 min to ~10 min. |
| **Opening Trainer** | Integrate a spaced-repetition opening drill module. Pattern report already identifies weak openings by ECO code — this would close the loop by generating drill positions from those lines. |
| **Opponent analysis** | Extend sync to fetch opponent's recent games for detected weaknesses; useful for pre-game preparation. |
| **Time pressure deep-dive** | Currently tracked as a boolean flag per move (clock < 30s). Future: correlate time pressure with mistake rate per opening/phase to identify specific situations where time management breaks down. |
| **Analysis versioning** | Tag each MoveError with an analysis version (model + depth + prompt hash). When the model or prompt changes, stale explanations can be detected and selectively re-run without a full re-analysis. |

---

## Hardware Reality

The system is tuned for this specific hardware configuration:

| Component | Spec | Role in Praxis |
|---|---|---|
| CPU | AMD Ryzen 7 7735HS (8c/16t) | Stockfish evaluation, Spring Boot |
| RAM | 24 GB DDR5 4800 MT/s | Ollama host model buffer (2.4 GB pinned) + JVM + OS |
| GPU 0 | AMD Radeon 680M (iGPU) | Display and compositing only — never inference |
| GPU 1 | NVIDIA RTX 3050 Laptop 4 GB | Ollama CUDA inference (partial offload — see below) |
| Storage | Micron 2500 NVMe 954 GB (C: + D: same disk) | PostgreSQL (Docker WSL2 on D:), page file on D: |

**Measured GPU placement** (`ollama ps` + `nvidia-smi`, reasoning model loaded):

```
NAME                SIZE     PROCESSOR
qwen3:4b-instruct   3.6 GB   18%/82% CPU/GPU

RTX 3050: 2948 MiB / 4096 MiB used
```

4 GB is a hard ceiling. 3.6 GB of weights plus the KV cache for `num_ctx: 4096`
does not fit, so Ollama leaves ~18% of layers on the CPU. Dropping `num_ctx`
would fit the rest, at the cost of the context the agent needs for tool results
— which is what caused empty-answer failures before it was raised.

> **Task Manager under-reports this.** Its GPU graphs default to the 3D / Copy /
> Video engines; CUDA work appears only under **Compute_0**, which must be
> selected manually. A reading of 0% on the NVIDIA GPU while the AMD iGPU sits
> at 70% is the expected appearance of a correctly working setup, not a
> misconfiguration.

**Stockfish is CPU-only by design** and is the dominant load during a
re-analysis run. The single synchronized engine process is shared with
`analyze_position`, so Prax questions asked during a re-analysis are slow.

**Memory pressure during analysis:**

```
Host RAM — model buffer:  ████████░░░░░░░░░░░░  2.4 GB pinned non-pageable
Host RAM — system + JVM:  ████████████░░░░░░░░  ~19 GB in use during analysis
Page file (D: drive):     grows to ~23 GB to back committed virtual memory
```

The page file was moved from C: to D: to prevent C: from filling during sustained
analysis sessions (Ollama's pinned RAM forces Windows to page other processes to disk).
Both partitions are on the same physical NVMe SSD, so performance is identical.

---

## Flow Summary

```mermaid
sequenceDiagram
    actor User
    participant FE as Frontend
    participant BE as Backend
    participant DB as PostgreSQL
    participant SF as Stockfish
    participant OL as Ollama

    User->>FE: Sync Now
    FE->>BE: POST /api/sync → 202
    BE->>BE: fetch Chess.com games (async)
    BE->>DB: save new games (PENDING)

    User->>FE: Re-Analyze All
    FE->>BE: POST /api/analysis/reanalyze → 200
    BE->>DB: reset all games to PENDING
    BE->>BE: queued=true (instant signal to frontend)

    loop 74 games
        BE->>SF: evaluate all positions (centipawns)
        SF-->>BE: score per position
        BE->>BE: filter mistakes by threshold\n(≥1.0 pawn drop)
        loop top 3 mistakes (overlapped with MultiPV)
            BE->>OL: FEN + move + engine lines → JSON
            OL-->>BE: explanation, motif
        end
        BE->>DB: save MoveError records
        BE->>DB: game.status = ANALYZED
    end

    BE->>OL: pattern report (aggregated stats)
    OL-->>BE: primary/secondary/tertiary weakness
    BE->>DB: save PlayerPattern

    FE->>FE: toast "Analysis complete"
    FE->>BE: GET /api/dashboard/stats
    BE->>DB: aggregate stats
    DB-->>BE: stats
    BE-->>FE: dashboard data
    FE->>User: updated Dashboard
```

---

## How It All Connects (High Level)

```mermaid
flowchart TD
    YOU([You]) -->|"① Sync Now"| CC[Chess.com API\nfetch PGN games]
    CC -->|store raw games| DB[(PostgreSQL)]

    DB -->|"② Re-Analyze All"| SF[Stockfish\nevaluate every position\nfind score drops]
    SF -->|top mistake candidates| OL[Ollama — qwen2.5:7b\nexplain why it was a mistake\nwhat to play instead]
    OL -->|save MoveErrors| DB

    DB -->|"after all games done"| PA[Pattern Aggregator\ngroup by phase · motif · move range\nask LLM for systemic weaknesses]
    PA -->|save PlayerPattern| DB

    DB -->|"③ Explore"| DASH[Dashboard\nrating · win rates · openings]
    DB --> GA[Game Analysis\nchessboard · move arrows · explanations]
    DB --> PR[Pattern Report\nyour recurring weaknesses]
    DB --> TP[Training Plan\nprioritized improvement list]
```
