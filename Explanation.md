# Praxis-Chess — Explanation

> **Status: real, working code.** Java 26 / Spring Boot 3.5.3 backend, React + TypeScript frontend,
> PostgreSQL in Docker, Stockfish as a subprocess, Ollama for local LLM. ~580 source files.
>
> **What I verified:** three defects in the analysis maths, each reproduced by computing the code's
> own arithmetic. They compound, and they all land in the same place — the numbers the product shows
> you. See §11.
>
> A note on process: this repo's `CLAUDE.md` says not to read `ARCHITECTURE.md` or `README.md` into
> context. That rule exists to save context during *editing* tasks. Writing this document requires
> them, so I read them — but stayed targeted, mapping section headers and reading only what mattered
> rather than loading 900+ lines.

---

## 1. The One-Liner

Praxis-Chess downloads your online chess games, has a chess engine find every mistake you made, has
a local AI explain *why* each one was a mistake, and then tells you which of your mistakes keep
happening — all on your own machine, with nothing sent to the cloud.

---

## 2. The Problem

### What chess improvement actually requires

Getting better at chess requires knowing what you specifically get wrong. Not "study tactics" —
*"you hang pieces on move 20 when you're under 30 seconds, in positions arising from the Sicilian."*

The tools that exist give you one half each:

- **Engines** (Stockfish) tell you the objectively best move. They will not tell you *why* yours was
  bad in terms a human can act on, and they have no memory across games.
- **Coaches** give human explanations and spot patterns, but cost money and don't scale to every
  game you play.
- **Cloud analysis** (Chess.com, Lichess) does both to a degree — and requires uploading your games
  and paying a subscription.

### The gap

Nobody joins the engine's precision to a human-readable explanation *and* aggregates across your
whole game history to find what recurs. That third part is the interesting one: a single game's
analysis tells you what you did wrong on Tuesday; a hundred games tell you what you're *systematically*
bad at, which is the only thing worth training.

### The constraint that shapes everything

Fully offline. The only outbound request is to the Chess.com public API to fetch your own games.
Every evaluation, explanation, pattern report and training plan runs locally.

That's a real design constraint rather than a marketing line, and it forces the interesting
decisions in §5: a local 7-billion-parameter model on consumer hardware is *slow* and *not very
good*, so you must be extremely careful about what you ask it to do.

---

## 3. How It Works — Plain English

### The analogy

Imagine two assistants reviewing your chess games, with very different skills.

**The first is a machine that plays perfectly** but cannot speak. Show it any position and it
instantly tells you a number: how good the position is. Show it the position before and after your
move, and the difference in those numbers tells you exactly how much your move cost you. It is never
wrong about this. It is also completely incapable of explaining anything.

**The second is a chess-literate writer** who can explain ideas clearly but is a much weaker player
than the machine — and occasionally makes things up.

The obvious mistake is to ask the writer to do the machine's job: "was this a blunder, and what
should I have played?" They'll answer confidently and sometimes be wrong, and you won't be able to
tell which times.

So you divide the work by what each is *reliable* at:

- The **machine** decides everything factual — which moves were mistakes, how bad each was, what
  should have been played instead.
- The **writer** is handed those facts and given exactly one job: *explain why, in a sentence.*

The writer never decides anything. It only narrates decisions already made.

And because the writer is slow, you do something else clever: while the writer is composing the
explanation for mistake #1, the machine is already analysing mistake #2. Two workers, two different
bottlenecks, both busy.

### Tracing one game

1. **Fetch.** The app calls the Chess.com public API for your games and stores the raw PGN — the
   standard text format recording every move.
2. **Parse.** The PGN becomes a list of moves, each with the board position before and after, and
   how much clock time you had left.
3. **Bulk evaluate.** Stockfish scores every position quickly (100 ms each). Now every move has a
   before-score and an after-score.
4. **Filter cheaply.** Any move where your score dropped by a pawn or more is a *candidate mistake*.
   The first six full moves are skipped as opening book. Maximum eight candidates per game, worst
   first. No expensive engine work yet — this pass is pure arithmetic on numbers you already have.
5. **Enrich deeply, and overlap.** For each candidate, Stockfish runs a proper depth-18 search
   returning its top three lines. Simultaneously, on another thread, the LLM is being asked to
   explain the *previous* candidate. CPU and GPU both stay busy.
6. **Explain — top three only.** The prompt contains the position, the move played, the engine's
   best reply, the eval swing in pawns, and the engine's lines. The model returns JSON: an
   explanation and a tactical motif. Nothing else.
7. **Persist per game.** Each game commits on its own. Kill the process mid-run and every completed
   game survives.
8. **Aggregate.** Once all games are analysed, error statistics are compiled and sent to the LLM
   *once* to identify what recurs across your whole library.
9. **Plan.** That report feeds a second LLM call producing a prioritised training list.

---

## 4. How It Works — Technical

### 4.1 The pipeline

```
Chess.com API ──► raw PGN ──► PostgreSQL (games)
                                   │
                      AnalysisPipelineOrchestrator
                      @Async("analysisExecutor")  core=1 max=1 queue=50
                                   │
                     for each game:  analyzeOne(game)
                                   │  @Transactional(REQUIRES_NEW)
        ┌──────────────────────────┴───────────────────────────┐
        │                                                       │
   PgnParserService                                             │
   chesslib → List<ParsedMove>{ moveNumber(ply), san,           │
                                fenBefore, fenAfter, clock }    │
        │                                                       │
   PositionEvaluator.evaluateAll()                              │
   per move: Chess.com %eval  →  Stockfish(fenAfter)            │
             →  material count     (priority order)             │
        │  List<Double> scores                                  │
        │                                                       │
   MistakeCandidateFilter.filterCandidates()                    │
   skip ply ≤ 12 · player's moves only · swing ≤ −1.0 pawn      │
   severity: ≤ −2.0 BLUNDER else MISTAKE · worst 8              │
        │  List<CandidateMove>                                  │
        │                                                       │
   ┌────┴──────────── OVERLAPPED ─────────────────────────┐     │
   │ MAIN THREAD (CPU)          │ CONSUMER THREAD (GPU)   │     │
   │ Stockfish MultiPV          │ Ollama qwen2.5:7b       │     │
   │   depth 18, 3 lines        │   3 retries, backoff    │     │
   │   per candidate            │   top 3 candidates only │     │
   │        │                   │        ▲                │     │
   │        └── LinkedBlockingQueue<Optional<CandidateMove>>     │
   │            offer(Optional.of(c)) … offer(Optional.empty())  │
   │                             ↑ poison pill              │    │
   └─────────────────────────────┴──────────────────────────┘    │
        │                                                        │
   persist MoveError rows (main thread, inside the TX)           │
   top 3 → EXPLAINED / LLM_FAILED   rest → SKIPPED               │
        └────────────────────────────────────────────────────────┘
                                   │
                    after ALL games: PatternAggregator.recompute()
                    group by phase · motif · move range · opening
                    → one LLM call → PlayerPattern
                                   │
                    TrainingPlanService → one LLM call → TrainingPlan
```

### 4.2 The overlap mechanism

The interesting 30 lines:

```java
LinkedBlockingQueue<Optional<CandidateMove>> ollamaQueue = new LinkedBlockingQueue<>();

CompletableFuture<List<OllamaResult>> ollamaFuture = CompletableFuture.supplyAsync(() -> {
    List<OllamaResult> results = new ArrayList<>();
    while (true) {
        Optional<CandidateMove> item = ollamaQueue.take();
        if (item.isEmpty()) break;                 // poison pill
        results.add(callOllamaWithRetry(item.get(), playerColor));
    }
    return results;
});

for (int i = 0; i < sorted.size(); i++) {
    MultiPVResult mpv = stockfishService.evaluateWithMultiPV(c.move().fenBefore(), 18, 3);
    // …build enrichedCandidate…
    if (i < MAX_OLLAMA_CALLS_PER_GAME) ollamaQueue.offer(Optional.of(enrichedCandidate));
}
ollamaQueue.offer(Optional.empty());               // signal consumer to stop
```

`Optional` as the queue element type is doing real work — `LinkedBlockingQueue` rejects `null`, so an
empty `Optional` is the idiomatic poison pill. `take()` blocks, so the consumer sleeps rather than
spinning.

Note the deliberate placement of the persistence loop: it runs **after** `ollamaFuture.join()`, on
the main thread, inside the `REQUIRES_NEW` transaction. The consumer thread does HTTP only and never
touches the database — which matters, because a Spring transaction is bound to a thread, and a
background thread writing through the same `EntityManager` would be a bug. The comment says so
explicitly: *"Runs on the consumer thread (no Spring TX context — HTTP only, no DB)."*

### 4.3 Durability

Three mechanisms, each small:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void analyzeOne(Game game) { … }
```

Each game is its own transaction, so killing the JVM mid-run loses at most the in-flight game.

```java
@PostConstruct
public void recoverStuckGames() {
    int count = gameRepository.resetAnalysisStatus(AnalysisStatus.ANALYZING, AnalysisStatus.PENDING);
}
```

On startup, anything stuck in `ANALYZING` from a crash is reset to `PENDING`. Simple and correct —
there's exactly one worker (`corePoolSize=1`), so no other process can legitimately hold that state.

And in the orchestrator, a failure in one game marks it `FAILED` and continues rather than aborting
the batch.

### 4.4 The three-tier evaluation fallback

```java
if (move.evalScore() != null)        scores.add(move.evalScore());        // Chess.com %eval
else if (stockfish.isAvailable())    scores.add(stockfish.evaluate(...)); // engine
else                                 scores.add(material(...));           // piece count
```

Graceful degradation: use the annotation Chess.com already computed, else run the engine, else fall
back to counting material — which only catches outright piece drops, and the comment says so.

---

## 5. The Core Concept: Let the Engine Decide, Let the Model Narrate

This is the idea worth taking to a whiteboard, and it generalises far beyond chess. Six moves.

### 5.1 The naive design

"Use AI to analyse my chess games" naturally becomes: send the position and the move to a language
model and ask *"was this a mistake, how bad, and what should I have played?"*

It will answer. Fluently. And it will frequently be wrong, because a 7-billion-parameter model
running on a desktop GPU is a much weaker chess player than a free engine — and worse, **you cannot
tell from the output which answers are wrong.** The confident sentence explaining a blunder and the
confident sentence explaining a perfectly good move look identical.

### 5.2 Sorting the work by what each tool is reliable at

Two components with opposite reliability profiles:

| | Stockfish | qwen2.5:7b |
|---|---|---|
| Is this move a mistake? | **Definitive** | Guesses |
| How bad, numerically? | **Exact** | Guesses |
| What was better? | **Definitive** | Guesses |
| Why is that better, in English? | **Cannot** | **Good** |
| Name the tactical theme | Cannot | Good |

Read down the columns and the division writes itself. The README states it as policy: *"Severity is
computed in Java — never by the LLM"*, and *"The LLM's job is limited to explaining why — severity
and the better move are already determined by Stockfish."*

### 5.3 What that looks like in the data

Severity is a pure function of the engine's numbers:

```java
if (swing >= -MISTAKE_THRESHOLD) continue;                    // not a mistake at all
Severity severity = swing <= -BLUNDER_THRESHOLD ? Severity.BLUNDER : Severity.MISTAKE;
```

Two constants, one comparison. Deterministic, reproducible, auditable.

The better move comes from a depth-18 MultiPV search. And the model returns exactly two fields:

```json
{
  "explanation": "Allows Bxf7+ winning the exchange; the engine's Nd5 maintains central control.",
  "tactical_motif": "HANGING_PIECE"
}
```

No score. No verdict. No recommended move. The prompt hands it the FEN, the move played, the
engine's best reply, the eval delta in pawns, and the engine's top lines — then asks only for prose.

### 5.4 Why this is stronger than "prompt it carefully"

The usual approach to LLM unreliability is better prompting, or asking it to show its reasoning.
This is different in kind: **the model is not in a position to be wrong about anything that matters**,
because the facts are computed elsewhere and passed *in*.

The failure modes shrink accordingly:

- Model hallucinates a tactic that isn't there → you get a poor explanation attached to a *correctly
  identified* mistake with a *correct* better move. Annoying; not corrupting.
- Model returns malformed output → constrained by Ollama's `"format": "json"` mode, and on failure
  the row is persisted with `AnalysisState.LLM_FAILED`. The mistake is still recorded with its
  severity and better move.
- Model unavailable entirely → candidates persist as `SKIPPED`, still carrying severity, phase and
  better move.

**The analysis degrades to a worse explanation, never to a wrong verdict.** That's the property to
articulate, and it's what "using AI responsibly in a pipeline" actually means — not trusting it less,
but *structuring the system so its unreliability can't reach the parts that must be right.*

### 5.5 The split is also what makes it affordable

The two decisions are connected, and this is the part people miss.

Because the model's job is narrow, you can cap it: `MAX_OLLAMA_CALLS_PER_GAME = 3`, out of up to 8
detected candidates. The other five still get recorded with full engine facts — you lose only prose.
If the model were deciding severity, you couldn't cap it, because skipping a call would mean not
knowing whether a move was a blunder.

And it enables the overlap. Stockfish is CPU-bound; Ollama is GPU-bound. Run them sequentially and
each waits for the other. Run them concurrently and the wall-clock cost of the LLM stage largely
disappears behind engine work already being done:

```
sequential:  [SF c1][LLM c1][SF c2][LLM c2][SF c3][LLM c3]
overlapped:  [SF c1][SF c2][SF c3][SF c4]…
                    [LLM c1][LLM c2][LLM c3]
```

The queue is what couples them, and the poison pill is what terminates cleanly. Neither is possible
if the LLM's answer is required before the engine can proceed — so **narrowing the model's role is
what unlocks the concurrency**, not a separate optimisation.

### 5.6 The generalisable rule

Strip out the chess and the pattern is:

> In a pipeline mixing deterministic and probabilistic components, give the deterministic component
> every decision it is capable of making, and reduce the probabilistic component to the smallest
> job only it can do. Then the system's worst case is *lower quality*, not *wrong answers*.

That transfers directly to every "add AI to X" problem. The question isn't *"what can the model
do?"* — it's *"what is the model the only thing that can do?"* Everything else belongs to code you
can test.

---

## 6. Key Decisions & Tradeoffs

**Fully offline.** *Why:* privacy, no API costs, no rate limits, works without internet after sync.
*Cost:* you're stuck with a 7B model, which is much weaker than a frontier model — which is precisely
why §5's division of labour is load-bearing rather than stylistic. Also: the user must install and
run Ollama, Stockfish, Docker and a JDK.

**Per-game `REQUIRES_NEW` transactions.** *Alternative:* one transaction for the whole batch. *Why:*
analysing a large library takes a long time, and losing all of it to a crash on game 97 is
unacceptable. *Cost:* no atomicity across the batch — you can end up half-analysed, which is why
"Analyze Pending" exists as a non-destructive resume.

**Single-threaded analysis executor** (`corePoolSize=1, maxPoolSize=1, queueCapacity=50`). *Why:*
Stockfish and Ollama each want the whole machine; two concurrent games would contend for the same
CPU and GPU and finish slower. *Cost:* no parallelism across games — the overlap is *within* a game
only.

**Cap LLM calls at 3 of up to 8 candidates.** *Why:* local inference is the slowest stage by a wide
margin. *Cost:* five mistakes per game get no explanation and no motif — and §11 notes what that does
to the motif statistics.

**Ollama JSON mode** (`"format": "json"`). *Why:* grammar-constrained output is always parseable,
which removes an entire class of "the model wrote a paragraph instead of JSON" failure. *Cost:*
slightly constrained expressiveness; still needs the retry loop for other failures.

**Chess.com `%eval` preferred over running Stockfish.** *Why:* it's already computed and free.
*Cost:* the two sources aren't identical — Chess.com's annotations come from their own engine at
their own depth — so a library can mix evaluation sources. This is the same class of problem as the
accuracy defect in §11.

**`ddl-auto: update`, no Flyway.** *Why:* personal project, one user, fast iteration. *Cost:* no
migration history, and no safe path if this were ever shared.

**Severity thresholds as constants** (`1.0` / `2.0` pawns). *Why:* transparent and tunable in one
place. *Cost:* they're absolute pawn values, so a 1.0 swing in a dead-drawn endgame counts the same
as a 1.0 swing in a sharp middlegame — where winning-percentage terms would be more meaningful.

---

## 7. Rubber Duck Walkthrough

*Reading my own code out loud.*

"`analyzeOne` is `REQUIRES_NEW`, so each game commits alone. Good — that's the resume story.

Parse, evaluate all positions, compute accuracy if Chess.com didn't give us one, compute max
advantage and average move time for the Insights page. Then the cheap filter, then the overlap.

The overlap first, because I like it. A `LinkedBlockingQueue<Optional<CandidateMove>>`, a consumer
future that `take()`s until it gets an empty Optional. `Optional` because `LinkedBlockingQueue`
rejects nulls, so you can't use `null` as your sentinel — that's a genuine constraint of the type
driving the design, not decoration.

Main thread runs MultiPV per candidate and offers the first three to the queue. Then offers the
poison pill. Then `join()`s. Then persists everything itself.

Persistence being on the main thread is the important detail. The transaction is bound to *this*
thread. If the consumer wrote `MoveError` rows it would either be outside the transaction or
corrupting the `EntityManager`. The comment on `callOllamaWithRetry` calls this out: HTTP only, no
DB. Correct, and the sort of thing that breaks subtly if someone later 'optimises' by persisting in
the consumer.

Index alignment: `ollamaResults.get(i)` matched against `enriched.get(i)` for `i < 3`. The consumer
appends in `take()` order, which is offer order, which is 0,1,2. So they line up. It's positional
coupling across a thread boundary — it works, and it would break silently if anyone ever offered
candidates out of order or filtered inside the loop.

Now `computeAccuracy`, and I want to check the maths rather than trust it.

`totalLoss` accumulates `max(0, -swing)` in pawns over the player's moves. Then:

```java
double acpl = Math.min((totalLoss / moveCount) * 100.0, 500.0);   // centipawns
double accuracy = 103.1668 * Math.exp(-0.04354 * acpl) - 3.1668;
```

That formula is Lichess's. But Lichess feeds it the drop in **win percentage**, not centipawn loss.
Those are wildly different scales — win% loss is typically single digits, ACPL is typically tens.

Let me put numbers on it. ACPL 20, which is a strong master-level game: 103.1668 × exp(−0.8708) −
3.1668 ≈ 40%. ACPL 50, a normal club game: 8.5%. ACPL 80: zero.

So every accuracy figure this computes is dramatically too low, and it saturates at 0 for anything
above roughly 80 centipawns — which is most club games. The formula is right; the input is the wrong
quantity.

And here's what makes it worse than a wrong number in isolation: it only runs `if
(game.getAccuracy() == null)`. Chess.com supplies accuracy for some games and not others. So the
database ends up holding Chess.com's ~75% for some games and this function's ~8% for others, in the
same column, feeding the same Insights accuracy-trend chart. The chart wouldn't look wrong — it would
look like violent, inexplicable form swings.

Next, phases. `detectPhase(moveNumber)`: ≤10 OPENING, ≤30 MIDDLEGAME, else ENDGAME.

What is `moveNumber`? `PgnParserService` does `new ParsedMove(i + 1, …)` over the flat move list. So
it's a **ply** counter — 1, 2, 3, 4 — not a chess move number. The filter knows this: `BOOK_MOVES_PLY
= 12 // skip first 6 full moves`. The comment does the ply→full-move conversion explicitly.

But `detectPhase`'s thresholds don't. 10 and 30 read exactly like full-move numbers. In ply terms
that makes ENDGAME start at ply 31 — full move 16. Move 16 is early middlegame. So most middlegame
errors get filed as endgame errors.

And then the two constants collide. The filter skips everything at ply ≤ 12; OPENING requires ply ≤
10. So the lowest ply that can reach `detectPhase` is 13, which is MIDDLEGAME. **`GamePhase.OPENING`
is unreachable for any mistake candidate.** The opening bucket in the Pattern Report is empty by
construction — and the README advertises 'where your opening preparation breaks down' as a feature
of exactly that report.

One more thing about the Pattern Report. Motif is only set for the top 3 candidates; the other five
persist as `SKIPPED` with `tacticalMotif` null. So 'which tactical motifs catch you repeatedly' is
computed from at most three-eighths of detected mistakes, biased toward the largest eval swings.
That's a defensible sampling choice — but it's a *sample*, and nothing labels it as one.

These three compound in a nasty way. The Pattern Report aggregates by phase and motif. Phase is
wrong and missing a bucket, motif is a biased subsample. That report is then sent to the LLM to
produce the Training Plan. So the top-of-funnel product output is derived from three skewed inputs,
and every layer downstream looks perfectly plausible."

---

## 8. Prerequisite Concepts

**PGN (Portable Game Notation).** The standard plain-text format for chess games — the moves plus
metadata like players, result, time control, and optionally clock times and engine evaluations.

**FEN (Forsyth–Edwards Notation).** A one-line string describing a complete board position: piece
placement, side to move, castling rights, en passant square, move counters. It's how a position is
handed to an engine.

**Ply vs move.** A *ply* is one player's turn; a *move* is a pair (White's and Black's). "Move 15"
is plies 29 and 30. Conflating them is a factor-of-two error, and §11 has one.

**Centipawn.** One hundredth of a pawn — the standard unit of engine evaluation. `+150` means White
is better by a pawn and a half.

**ACPL (Average Centipawn Loss).** Mean evaluation lost per move relative to the engine's best.
Lower is better; roughly 10 is world-class, 50 is a decent club player.

**Accuracy percentage.** A 0–100 score derived from how much you lost per move. Chess.com and Lichess
both publish one; Lichess's formula operates on *win-percentage* loss, not centipawn loss — a
distinction §11 turns on.

**UCI (Universal Chess Interface).** The text protocol engines speak over stdin/stdout. You send
`position fen …` and `go depth 18`; it replies with evaluations and `bestmove e2e4`.

**MultiPV.** An engine setting asking for the top *N* lines rather than just the best one. `MultiPV
3` at depth 18 gives three candidate continuations — richer evidence for an explanation than a single
best move.

**Stockfish.** The strongest open-source chess engine, run here as a subprocess over UCI.

**Ollama and local LLMs.** Ollama runs language models on your own hardware. `qwen2.5:7b` is a
7-billion-parameter model — small enough for a consumer GPU, and much weaker than a frontier model.

**Grammar-constrained / JSON mode.** Forcing a model's output to conform to a grammar so it *cannot*
emit malformed JSON. Removes parse failures as a category.

**Producer–consumer with a blocking queue.** One thread produces work, another consumes it, with a
queue between. `take()` blocks when empty, so the consumer sleeps rather than polling.

**Poison pill.** A sentinel value placed on a queue to tell the consumer to stop. Needed because a
queue can't signal "no more items" by itself. `LinkedBlockingQueue` forbids `null`, hence
`Optional.empty()` here.

**`REQUIRES_NEW` transaction propagation.** Spring starts a fresh, independent transaction that
commits on its own regardless of any surrounding one — which is what makes per-game durability work.

---

## 9. Explain It To Others

### 30 seconds — a non-technical friend

"It downloads all my online chess games and reviews them for me. A chess engine finds every mistake
and works out how bad each one was, then an AI running on my own laptop writes a plain-English
sentence explaining why it was bad and what to play instead. Then — the useful bit — it looks across
all my games and tells me what I get wrong *repeatedly*, and turns that into a training list.
Everything runs on my machine; nothing gets uploaded."

### 2 minutes — a developer

"Java/Spring backend, React frontend, Stockfish as a subprocess, Ollama for a local 7B model,
Postgres. Fully offline apart from fetching games.

The design decision worth talking about is the **division of labour between the engine and the LLM**.
The naive version asks the model 'was this a blunder and what should I have played?' — and a 7B model
is a much weaker player than a free engine, so it'll answer confidently and sometimes be wrong, with
no way to tell which.

So the engine owns every fact. Severity is `swing <= -2.0 ? BLUNDER : MISTAKE` — two constants in
Java. The better move comes from a depth-18 MultiPV search. The model gets handed all of that and
returns exactly two fields: an explanation and a tactical motif. It never decides anything.

The property that buys you is that the system **degrades to a worse explanation, never to a wrong
verdict.** If the model hallucinates, you get poor prose on a correctly-identified mistake. If it
fails entirely, the row persists as `LLM_FAILED` with severity and better move intact.

And the narrow role is what makes it fast. Because the model isn't in the decision path you can cap
it at three calls per game, and you can overlap it: Stockfish MultiPV is CPU-bound, Ollama is
GPU-bound, so a `LinkedBlockingQueue` lets the main thread run the engine on candidate N+1 while a
consumer thread calls the model on candidate N. `Optional.empty()` is the poison pill, because
`LinkedBlockingQueue` won't accept null.

Durability is per-game `REQUIRES_NEW` transactions plus a `@PostConstruct` sweep resetting anything
stuck in `ANALYZING`. Kill the JVM at game 97 and 96 games are still there."

### 5 minutes — an interviewer who will push back

Lead with the 2-minute version, then:

"**The transferable idea** is the rule the split comes from: in a pipeline mixing deterministic and
probabilistic components, give the deterministic one every decision it's capable of making, and
reduce the model to the smallest job only it can do. The question isn't 'what can the LLM do?' —
it's 'what is the LLM the *only* thing that can do?' Everything else belongs in code you can test.

**A subtlety I'd point out** is that the split and the concurrency are the same decision. If the
model decided severity you couldn't cap it at three calls, because skipping one would mean not
knowing whether a move was a blunder. Narrowing its role is what unlocked the overlap; they aren't
independent optimisations.

**And three bugs I found in my own analysis maths, which I'd rather raise than have you find.**

The accuracy calculation uses Lichess's formula but feeds it the wrong quantity — ACPL in centipawns
where the formula expects win-percentage loss. I computed it out: a strong master game at ACPL 20
scores 40%, a normal club game at ACPL 50 scores 8.5%, and anything above ~80 saturates at zero. And
it only runs when Chess.com didn't supply accuracy, so the same column mixes Chess.com's ~75% with
my ~8% and feeds one chart.

Second, `moveNumber` is a ply counter but `detectPhase` uses thresholds written for full moves, so
'endgame' starts at move 16. Third, those thresholds collide with the book-move filter — the filter
skips ply ≤ 12 and OPENING requires ply ≤ 10, so `GamePhase.OPENING` is unreachable and the opening
bucket in the Pattern Report is empty by construction.

They compound: the Pattern Report aggregates by phase and motif, and it feeds the Training Plan. So
the headline output is built on three skewed inputs, all of which look plausible."

---

## 10. Questions You'd Be Asked

**Q: Why not let the LLM judge whether a move was a mistake?**
Because a 7B model running locally is a far weaker chess player than a free engine, and its wrong
answers are indistinguishable from its right ones. So severity is two constants in Java —
`swing <= -2.0 ? BLUNDER : MISTAKE` — and the better move comes from a depth-18 MultiPV search. The
model is handed those facts and asked only to explain. The result is that the system degrades to a
worse *explanation*, never to a wrong *verdict*.

**Q: What happens when the LLM fails?**
It's structurally contained. Ollama runs in JSON mode so malformed output isn't a category. On other
failures there are three retries with linear backoff, and then the row persists with
`AnalysisState.LLM_FAILED` — still carrying severity, phase, and better move from the engine.
Candidates beyond the top three persist as `SKIPPED` with the same engine facts. Losing the model
loses prose, not analysis.

**Q: Why the blocking queue rather than just calling Ollama in the loop?**
Because the two stages bottleneck on different hardware. Stockfish MultiPV is CPU-bound; Ollama is
GPU-bound. Sequentially, each waits for the other. The queue lets the main thread run the engine on
candidate N+1 while a consumer thread calls the model on candidate N, so the LLM stage largely
disappears behind engine work. `Optional.empty()` is the poison pill because `LinkedBlockingQueue`
rejects nulls.

**Q: Why does the consumer thread not write to the database?**
Because a Spring transaction is bound to a thread. `analyzeOne` is `REQUIRES_NEW`, and that
transaction lives on the main thread — a background thread writing through the same `EntityManager`
would be outside it at best and corrupting at worst. So the consumer does HTTP only, and all
persistence happens on the main thread after `join()`, inside the transaction.

**Q: How does it survive a crash mid-analysis?**
Three things. Each game is its own `REQUIRES_NEW` transaction, so a kill loses only the in-flight
game. A `@PostConstruct` hook resets anything stuck in `ANALYZING` back to `PENDING` — safe because
there is exactly one worker, so no other process can legitimately hold that state. And a failure in
one game marks it `FAILED` and continues rather than aborting the batch.

**Q: Why only three LLM explanations per game when you detect up to eight mistakes?**
Local inference is by far the slowest stage, and the candidates are sorted worst-first, so the three
explained are the three biggest eval swings. The other five still persist with full engine facts.
It's a defensible trade — but it does mean the motif statistics in the Pattern Report are computed
from at most three-eighths of detected mistakes, biased toward large swings, and nothing labels that
as a sample.

**Q: Your accuracy numbers look low. Why?**
They are, and it's a bug I've verified. The formula is Lichess's, which takes average *win-percentage*
loss; the code feeds it ACPL in centipawns. Running the numbers: ACPL 20 (master level) → 40%, ACPL
50 (club level) → 8.5%, anything above ~80 → 0%. Worse, it only computes when Chess.com didn't supply
a value, so the column mixes two incompatible scales. The fix is to convert centipawns to win
percentage first.

**Q: Why fully offline if it makes the AI worse?**
Privacy, zero cost, no rate limits, and it keeps working without internet. And the weakness of the
local model is exactly why the engine/model split exists — the constraint forced a better
architecture than a frontier model would have. With GPT-4-class quality you'd be tempted to just ask
it everything, and you'd have built something less reliable.

**Q: What would you fix first?**
The accuracy formula, because it corrupts a headline number and mixes scales in one column. Then the
phase boundaries, because they're a factor-of-two error that makes one bucket unreachable and feeds
the Pattern Report and Training Plan. Both are a handful of lines. After that I'd add tests around
the analysis arithmetic — all three bugs are in pure functions that are trivially unit-testable, and
that's exactly why they survived.

---

## 11. Weak Points

### Verified defects

All three are in pure, easily-testable arithmetic, and I confirmed each by computing the code's own
formulas.

**1. The accuracy formula is fed the wrong quantity.**
`GameAnalysisTransactionService.computeAccuracy` (lines 268–270):

```java
double acpl = Math.min((totalLoss / moveCount) * 100.0, 500.0);   // centipawns
double accuracy = 103.1668 * Math.exp(-0.04354 * acpl) - 3.1668;
```

That is Lichess's accuracy formula, and it expects the average **win-percentage** loss — not
centipawn loss. Computed:

| ACPL (cp) | Typical strength | Praxis accuracy |
|---:|---|---:|
| 5 | super-GM | 79.8% |
| 10 | world-class | 63.6% |
| 20 | strong master | 40.0% |
| 35 | solid club player | 19.3% |
| 50 | average club player | 8.5% |
| 80+ | improver and below | 0.0% |

The formula behaves correctly on its intended input (win% loss of 2 → 91.4%, of 5 → 79.8%), so this
is an input-unit error rather than a wrong formula.

**It's compounded by only firing when Chess.com omits accuracy** (`if (game.getAccuracy() == null)`).
So one column holds Chess.com's ~75% for some games and ~8% for others, and the Insights
accuracy-trend chart with its 10-game rolling average renders both as if commensurable. The chart
won't look broken; it will look like violent form swings.

**2. Game-phase boundaries are off by a factor of two.**
`PgnParserService` assigns `new ParsedMove(i + 1, …)` over the flat move list, so `moveNumber` is a
**ply** counter. `MistakeCandidateFilter` knows this — `BOOK_MOVES_PLY = 12 // skip first 6 full
moves`. But `detectPhase` uses `≤10 OPENING, ≤30 MIDDLEGAME, else ENDGAME`, which are full-move
thresholds. In plies that puts the endgame boundary at ply 31 = **full move 16**, which is early
middlegame. Most middlegame errors are filed as endgame errors.

**3. `GamePhase.OPENING` is unreachable.**
The filter skips `moveNumber <= 12`; `detectPhase` requires `<= 10` for OPENING. The lowest ply that
can reach the classifier is 13 → MIDDLEGAME. So no mistake candidate can ever be labelled OPENING,
and the opening bucket in the Pattern Report is empty by construction — while the README advertises
*"where your opening preparation breaks down"* as a feature of that report.

**Why these matter together:** the Pattern Report aggregates by phase and motif, and that report is
the LLM's input for the Training Plan. Phase is wrong and missing a bucket; motif is a biased
three-of-eight subsample (only the top candidates get explained, so only they get a motif). The
product's headline output rests on three skewed inputs, each of which produces plausible-looking
results.

### Structural weaknesses

- **No tests around the analysis arithmetic.** All three defects live in pure functions taking
  numbers and returning numbers — the easiest thing in the codebase to unit-test, and the reason they
  survived is that nothing does.
- **Positional coupling across a thread boundary.** `ollamaResults.get(i)` is matched to
  `enriched.get(i)` by index, relying on the consumer appending in offer order. Correct today;
  silently wrong if anyone reorders or filters inside the producer loop. A key on the queue element
  would make it robust.
- **Mixed evaluation sources.** `PositionEvaluator` prefers Chess.com's `%eval` over running
  Stockfish. Different engines at different depths produce different numbers, so a single game's
  score series — and therefore its swings, severities and ACPL — can be internally inconsistent with
  another game's. Same class of problem as defect 1.
- **Absolute pawn thresholds for severity.** A 1.0-pawn swing in a dead-drawn endgame is treated the
  same as one in a sharp middlegame. Win-percentage terms would be more meaningful, and would also
  fix defect 1.
- **`ddl-auto: update` with no migrations.** Fine for one user; no path to sharing.
- **Single-worker executor** means analysing a large library is inherently serial. The within-game
  overlap helps; nothing parallelises across games, deliberately (both stages want the whole
  machine).
- **The first move's swing is always zero.** In both `computeAccuracy` and `filterCandidates`,
  `beforeIdx = max(0, i-1)` makes index 0 compare against itself. Harmless in practice — ply 1 is
  book anyway — but it's an off-by-one that would matter if the book filter were removed.

### What's genuinely good

The engine/model division of labour is the strongest idea here and it's applied consistently, not
just stated — severity in Java, better move from MultiPV, model restricted to two output fields, and
graceful degradation through `LLM_FAILED` and `SKIPPED` states that keep the engine facts. The
CPU/GPU overlap is real engineering with the right primitive, and the `Optional` poison pill is the
correct idiom rather than a workaround. Keeping persistence on the transactional thread — with a
comment explaining why the consumer must not touch the database — is the kind of detail that
prevents a nasty future bug. Per-game `REQUIRES_NEW` plus the `@PostConstruct` stuck-game sweep is a
small, complete durability story. And the three-tier evaluation fallback degrades sensibly with an
honest comment about what the last tier can and cannot catch.

---

## Appendix — the fixes, in order

1. **`computeAccuracy`** — convert centipawn loss to win-percentage loss before applying the
   formula. The standard conversion is
   `win% = 50 + 50 * (2 / (1 + exp(-0.00368208 * cp)) - 1)`, applied to the eval before and after
   each move, with the accuracy formula taking the average drop. Until then, every computed accuracy
   is far too low and incommensurable with Chess.com's.
2. **`MistakeCandidateFilter.detectPhase`** — either double the thresholds (`≤20` / `≤60` in plies)
   or convert to full moves first (`(moveNumber + 1) / 2`). Fixes the phase skew and makes OPENING
   reachable — though note the book filter at ply 12 means opening errors will only appear if
   `BOOK_MOVES_PLY` is also reconsidered.
3. **Add unit tests for the three arithmetic functions.** `computeAccuracy`, `detectPhase`, and
   `filterCandidates` all take plain values and return plain values. A table of known inputs and
   expected outputs would have caught every defect above.
4. **Rename `ParsedMove.moveNumber` to `ply`.** Two of the three bugs are the same misreading of that
   field. The name is the bug.

Fix 4 is the cheapest and prevents recurrence; fix 1 changes what the product tells you about
yourself.
