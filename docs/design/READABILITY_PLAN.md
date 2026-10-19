# Readability: audit and plan

The goal is the same data with less to read. This document covers Phase 0 (the audit) and Phase 1 (the plan). **No app code has changed.** I'll start Phase 2 only after you approve, including the decisions in §8.

---

## 0. How it was measured

- **Word count.** A throwaway Playwright script loaded every screen with the existing fixtures (the same mocks as `docs/design/screens/`) at 1440×900. It counted the words in `main.innerText` in the default state, with disclosures closed. A "word" is any whitespace-separated token containing a letter or digit, so `17.6`, `−34%` and `23.Qxc6` count; separators such as `·` don't.
- **Excluded from the count:** chessboard coordinates (`[data-boardid]`), chart axis ticks (`svg`) and the dev-only Prax pill. Without these exclusions a board alone adds 16 "words".
- **Phone:** the 390px counts equal the desktop counts on the two phone screens (same DOM).
- **Repeatability:** the script is saved outside the repo. Milestone 0 turns it into a helper in the suite.

### Baseline (visible words, default state)

| # | Screen | Words | Budget target |
|---|---|---:|---|
| 1 | Today | 55 | −40% → ≤ 33 |
| 2 | Today, first run | 123 | −40% → ≤ 74 |
| 3 | Progress | 65 | budgets only |
| 4 | Library | 36 | budgets only |
| 5 | Game Analysis | 49 | budgets only |
| 6 | Play & Improve | 99 | budgets only |
| 7 | Practice review | 40 | budgets only |
| 8 | Ask Prax | 55 | budgets only |
| 9 | Insights: Overview | 255 | −40% → ≤ 153 |
| 10 | Insights: Pattern Report | 404 | −40% → ≤ 242 |
| 11 | Insights: Training Plan | 237 | −40% → ≤ 142 |
| 12 | Drills (empty) | 18 | budgets only |
| 13 | Drill session | 21 | budgets only |
| 14 | Settings | 324 | −40% → ≤ 194 |

"Budgets only" means the screen has to meet the reading budget (takeaway, intro, item, paragraph); it has no percentage target.

**Paragraph, for the budget.** A visible block of running text, outside a table or data row, that is longer than 15 words or contains two or more sentences. The test counts these on the report and plan pages.

---

## 1. Findings that change the plan

These came out of the audit. Several are existing violations of the hard rule.

1. **The board popover can't be reached on a phone.** `ReportParts.BoardPopover` opens on hover or focus. On a phone, tapping the move follows the link to the game, so the board never shows. Today it is hover-only in practice.
2. **The ⓘ hints in Settings can't be reached either.** They are `title=` tooltips on a non-focusable `<span>` (`Settings.tsx:86`, `:378`), which means hover only, with no keyboard or tap access. Ten help texts are unreachable on phones today.
3. **Two training systems, no link between them.** Today's "Start session" runs the spaced-repetition deck (`/session/:id`, `Session.tsx`). The Training Plan runs its own sessions (`/drills?positions=…`, `Drills.tsx`). Today never mentions the plan, and Progress never counts plan sessions.
4. **Today and the Pattern Report disagree on the takeaway.** Today says "You lose material in the middlegame more than anywhere else" (`TodayInsightService`, by phase). The report says "Your costliest habit: ignoring your opponent's threat" (by cause). Both are true, but "what now?" and "why?" point at different things.
5. **Next-action violations (principle 10).**
   - Today has no solid button: "Start session →" is ghost.
   - Settings has two solid buttons: Sync Now and Save settings. On first run it has three, because the username Save is also solid.
   - `SyncStatusBanner` adds another solid button on whichever page it appears.
6. **Mislabelled data (principle 3).** The Overview's "Mistakes by Phase" shows `phase_accuracy` (74.8, 64.1, 69.7), which is accuracy, not mistakes. Its "(36%)" is each accuracy as a share of the three summed, which measures nothing.
7. **Format drift (principle 7).**
   - Report and plan footers use `toLocaleDateString()` (`10/16/2026`).
   - Thrown-Away Wins prints raw ISO (`2026-08-20T17:44:00Z`).
   - Game Analysis prints the engine's best move in UCI (`g7g6`), while every other move on the page is SAN.
8. **A chess glyph used as a UI marker.** The setup checklist uses `!` for "in progress" (AI models). `!` means "good move" in chess.
9. **Existing tests assert exact sentences.** `reports.spec.ts` checks wording that this work shortens. The principle each test names still holds, but its expected strings have to change (§8, D3).
10. **Plan sessions record only a total.** `Drills.tsx` posts `sessionDone(solved, total)` and nothing per position. The SRS flow records an `Attempt` per card (`correct`, `rating`, `response_ms`). This limits what a "did it work?" loop can show (§6).

---

## 2. Phase 0: audit by screen

Classes: **D** Data · **C** Caveat · **I** Instruction · **M** Method · **R** Repetition · **N** Navigation.
Treatments: keep · structure (convert to a row, bar, table or tile) · shorten · disclose (one click or tap) · delete (with evidence).

### 10. Insights: Pattern Report (404 words)

| Block (words) | Class | Treatment | Evidence or note |
|---|---|---|---|
| Insights subtitle "Practical analytics… actually leaks." (11), repeated on all 3 tabs | N | **delete** | No number, move, date or state in it. The page title and tabs already name the page |
| "Your costliest habit: ignoring your opponent's threat." (7) | D | keep | Takeaway, ≤ 20 words |
| "It cost you more winning chances than any other cause: 32 of 145 (22%) · 19 blunders · −27% winning chance each." (20) | R | **delete** | Cause 1's stat line, on the same screen, is character for character `32 of 145 (22%) · 19 blunders · −27% winning chance each`. "More than any other cause" is rank 1 in a list labelled as ranked by winning chances lost |
| "Across all causes, your serious mistakes are most frequent in the middlegame: 17.6 per 100 moves there, against 13.6 over all your moves." (23) | D+R | **structure**, as the label "Most often: middlegame · 17.6 vs 13.6 per 100 moves" (8) | 17.6 and 13.6 are both in "Where they happen". The label keeps the claim and its baseline in the summary |
| "Ranked by the winning chances each cause cost you in total, not by how often it happened." (17) | M (a truthful label) | **shorten** to the micro-label "Ranked by winning chances lost" (4) | It must stay visible: it defines "costliest" (principle 3) |
| Cause title (4) and stat line (10) | D | keep | Canonical format |
| Drill advice (10–15 per item) | I | **shorten** to an imperative of ≤ 8 words, with the full text in a disclosure | Example: "Name the threat before each move" (6) |
| Positions: move (1) + "Scandinavian Defense · 6 Sep · −34%" (5) ×3, plus "Show all 5" | D | **structure**: the move and its loss stay visible (`23.Qxc6 −34%`); opening, date, board and Open game go in a popover that opens on tap or focus | See D1. This also fixes finding 1 |
| "The rest: unclear 17, no cause found 40." (8) | D+C | keep | Principle 6 |
| Tactics intro (35): "The tactic your opponent had… It is named only when the board proves it: no tactic was proven in 96 of your 145 serious mistakes." | M+C | **split.** The caveat stays visible as "No proven tactic in 96 of 145" (7). The method goes in the "How this is measured" disclosure | The 96 of 145 changes how the tactic counts read, so it stays visible |
| Overlap note "1 of these 14 are also under 'Ignoring…': the same mistakes, seen as a tactic." (18) | C | **shorten**, still visible: "1 of 14 also under: Ignoring your opponent's threat" (8) | A caveat, so never disclosed |
| "The rest: pins 5." (4) | D | keep | |
| "Where they happen" intro (31): rate definition, baseline 13.6, amber meaning, threshold rule | M+C | **split.** Visible legend: "Per 100 of your moves · line = your rate, 13.6" (9). The threshold and amber rule go in the disclosure | The "few" suffix already marks every row below the threshold |
| Bar rows (7 × 4–5) | D | keep | |
| Clock note "Clock data exists for only 4 of 145… nothing to say about time trouble." (19) | C | **shorten**, still visible: "Time trouble: clock data on 4 of 145 · too few" (9) | |
| "Your average: 4.7 serious mistakes per game." (7) and the table | D (baseline) | **shorten** the caption to "Your average: 4.7 per game" (5); keep the table | The column header already says "Per game" |
| Footer "From 31 analyzed games · 10/16/2026" | D | **shorten**: "31 analyzed games · 16 Oct" | Fixes the date format (finding 7) |

### 11. Insights: Training Plan (237 words)

| Block (words) | Class | Treatment | Evidence or note |
|---|---|---|---|
| Insights subtitle (11) | N | **delete** | As on the report |
| "This week, 16 Oct to 22 Oct: 2 sessions of about 20 minutes." (13) | D | keep | Takeaway, ≤ 20 words |
| "1 of 2 done. Next: session 2." (7) | D (state, `aria-live`) | keep | |
| Session micro-label, goal heading, focus chips | D | keep. The chips become buttons (see "What the sessions work on" below) | |
| Positions in session cards (5 × 6 words) | D | as on the report: move and loss visible, the rest on tap | |
| "✓ Done 17 Oct · 2 of 3 solved" and the buttons | D, N | keep | |
| "What the sessions work on": 2 priorities and 1 tactic, each with a title, stat line and full drill (88 words) | **R** | **delete the section.** Each focus chip on a session card opens the plan's own figures for that focus: rank, stat line, full drill, and a "See in Pattern Report" link | Every focus already appears as a chip on a session card. The stat lines are the plan's snapshot, which can differ from a later report, so they move into the chip popover instead of becoming a bare link. Rank becomes "Priority 1 of 2"; the tactic becomes "Tactic your opponent got" |
| "Center Game: 6.0 serious mistakes per game against your 4.7 (5 games)" (12) | D | **structure**: the row "Center Game · 6.0 vs your 4.7 per game · 5 games" (10) | |
| "Made 10/16/2026 from 31 analyzed games" (6) | D | **shorten**: "Made 16 Oct · 31 games" | Date format |
| "A new plan starts a new week. This one, with its progress, stays in your history." (16) | I+C | **shorten**, next to the button: "Keeps this plan in your history" (6) | It tells you what the button does, so it stays visible |

### 9. Insights: Overview (255 words)

| Block (words) | Class | Treatment | Evidence or note |
|---|---|---|---|
| Subtitle (11) | N | **delete** | |
| Speed filter, 3 tiles with their sublabels | D | keep | |
| Drill tile "♟ Drill · Practice your own blunders · Open trainer →" (7) | N | **shorten**: "Drill · Open trainer" | |
| Chart description (32) | M+C | **split.** The legend already says "Rolling avg (up to 10 games)". Keep the caveat visible as "First 9 points: fewer games" (5). "Left axis / right axis / to scale" go in the disclosure | The axes are labelled by their own ticks |
| Card hints: "Win rate vs opponents ±50 rating.", "Where your flagged mistakes cluster…", "Tactical motifs you miss most often.", "Win rate by part of day.", "Win rate by weekday.", "Games where you reached ≥ +2.0…", "Win rate and average accuracy per opening…" (≈ 46) | M (labels) | **shorten** each to a micro-label of ≤ 4 words ("Win % · ±50 Elo bands", "≥ +2.0, not won", "Most-played first") or delete where the column headers already say it | Openings: the column headers already say Win % and Accuracy |
| "Mistakes by Phase" rows: 74.8 (36%) and the others | D, mislabelled | **relabel** as "Accuracy by phase" and keep 74.8, 64.1 and 69.7. The "(36%)" share: see D5 | Finding 6 |
| Missed Tactics: Positional 154, Hanging Piece 63, Fork 28 | D | Positional moves to "The rest: positional 154", and the bars turn neutral | Fixes drift 1 (pink for data) and 4. The number stays (D6) |
| Resilience "Do you tilt after a loss?…" (12) | M | **shorten** to the micro-label "Next game's win %" | |
| Resilience warning "⚠ You perform notably worse after a loss — consider a short break…" (15) | C+I | **shorten**: "13.3 points lower after a loss · take a break" (8) | 52.3 − 39.0 is shown; the em dash goes (drift 7) |
| Thrown-Away Wins row, with an ISO timestamp | D | keep, as `20 Aug` | Finding 7 |
| Openings table | D | keep | |

### 14. Settings (324 words)

| Block (words) | Class | Treatment | Evidence or note |
|---|---|---|---|
| "Games are kept per username: switching shows…" (15) | M | **disclose** under the field, in the shared disclosure | |
| Library status, four buttons, the "Setup" line | D, N | keep | |
| Coverage summary line (15) | D | **structure** as a "Total" row in the month table, with the date range and practice counts in the table caption | The totals equal the column sums |
| Month table (≈ 20) | D | **disclose** behind "By month (2)" | One tap; the totals stay visible |
| Range hints "Empty: this month…", "Only ticked speeds are analysed." (13) | I | keep, shortened to ≤ 6 words each | They explain what an empty field means, so they stay visible |
| Two engine cards with identical labels (2 × ~30) | R (labels) | **structure** as one table: rows are settings, columns are "Chess.com games" and "Practice games" | Each label appears once; every value and range stays |
| "Written explanations per game" ×2 | D (retired feature) | see D7 | Drift 8 |
| ⓘ help (10 texts, hover-only today) | M | **disclose** through one tappable "?"-free info control (the shared disclosure) | Finding 2 |
| AI models (3 rows × "THIS LAPTOP") | D | keep (already aligned) | |
| Backups status line (21) | D | keep | |
| Backups policy paragraph (31) | M | **disclose** as "How backups work" | Not a caveat on any figure |
| Snapshot table, "How to restore" | D, M | keep (restore is already a `<details>`) | |
| Estimated time, 2 sentences (36) | D | **structure** as a 2-row table: per game · games in range · total · measured on | |
| "Save settings" (solid) | N | solid only when something has changed, ghost otherwise | Finding 5 |

### 1–2. Today (55 words; first run 123)

| Block (words) | Class | Treatment | Evidence or note |
|---|---|---|---|
| "One clear focus. No overwhelm." (5) | N | **delete** | No data |
| Tiles (8), week dots and labels (7), streak (5) | D | keep | |
| "This week" (2), "TODAY'S FOCUS" (2) | N | delete "This week" (the streak label already says it); keep the eyebrow | |
| Headline (10) | D | keep (see D8) | |
| "Drill 8 tactical positions from your own games" (8) plus "~12 min" | D | **structure**: "8 positions · ~12 min" | |
| "Start session →" | N | becomes **solid** | Finding 5 |
| First run: checklist rows, "Name — detail" (≈ 30) | D | show only the rows that block or are in progress, plus "3 ready" (which expands to the full list) | One tap away. The `!` becomes a neutral "in progress" dot (finding 8), and the em dashes go (drift 7) |
| First run: model list and "Syncing and engine analysis work already…" (15) | D+C | keep the progress rows; shorten the caveat to "Engine analysis works now; AI features wait" (7) | |

### 3–8, 12–13. Other screens

| Screen | Block (words) | Class | Treatment |
|---|---|---|---|
| Progress | "Deck health · recall rate · phase trends" (6) | N | delete: it names the three sections that are already titled below |
| Progress | "Phase recall — last session" | N | "Phase recall · last session" (drift 7) |
| Progress | "Every day you examined your chess. Gaps are shown, not hidden." (11) | M | disclose |
| Library | "All your games and drill archive" (6) | N | keep (≤ 15) or delete; no data either way |
| Game Analysis | "Select a mistake to see the position" + "· Click to see position" | R | keep one of the two |
| Game Analysis | "Engine best: g7g6" | D | show in SAN (finding 7), when this card is touched for flow idea F7 |
| Play | Subtitle (17, with an em dash) | N | ≤ 15 words |
| Play | "Your opponent", 4 sentences (68) | D | **structure** as 4 labelled rows: Steers toward · Blunders · Most frequent pattern · Strength. All numbers kept; "Positional" handled as in D6 |
| Practice review | "Your opponent's move. Only your side was analysed." (8) | C | keep |
| Ask Prax | Subtitle (12) + intro (25) | R | merge into one sentence of ≤ 15 words. Both say "from your games or cited"; the intro adds only "general chess questions are cited" |
| Drills, empty | 1 sentence (18, with an em dash) | I | ≤ 12 words, plus a link to Library |
| Drill session | — | | already lean; flow only (F4) |

---

## 3. Phase 1: the overall shape

**Which screens change, and in what order:**

| Order | Milestone | Screens | Why here |
|---|---|---|---|
| M0 | Harness | none (tests only) | Captures the "old rendering" golden data **before** anything changes. Without it, parity can't be proven |
| M1 | Pattern Report + shared parts | 10 | The largest page (404). It introduces the shared parts (disclosure, tap popover, short drill text) that M2 reuses, and the plan's chips will link to its anchors |
| M2 | Training Plan | 11, 16 | Reuses M1. It removes the largest repetition (88 words) |
| M3 | Insights Overview | 9 | Independent of M1–M2. It needs D5 and D6 |
| M4 | Settings | 14 | Independent. It fixes the unreachable ⓘ help |
| M5 | Today (and first run) | 1, 2, 15 | Lean already, and its shape depends on which flow ideas you pick (F1, F3) |
| M6 | Light pass | 3–8, 12–13 | Small edits to meet the budgets |
| M7+ | Flow ideas you choose | various | One per milestone |

**New shared components** (in `components/report/ReportParts.tsx`, documented in `DESIGN_SYSTEM.md`):

- **`Disclose`**: a native `<details>`/`<summary>`. Tap, keyboard and screen readers work without JavaScript. The summary is a micro-label: "How this is measured" for method, "Full drill" for advice. One per section at most.
- **`PositionChip`** (replaces the link inside `Positions`): the move and its loss are visible. Tap, click or focus opens the popover, which already exists: the board, "White to move", opening · date · loss, **Open game**, and **Drill this**. Escape or a tap outside closes it. On desktop, hovering still previews the board.
- **Short drill text**: a `drill_short` field (≤ 8 words) next to `drill` in `ReportFactsBuilder` (D4).

---

## 4. Before and after sketches

### Pattern Report

```
BEFORE (404 words)                                 AFTER (~215 words, projected)
Insights                                           Insights
Practical analytics … actually leaks.              [Overview][Pattern Report][Training Plan]
[Overview][Pattern Report][Training Plan]
                                                   Your costliest habit: ignoring your
Your costliest habit: ignoring your                opponent's threat.
opponent's threat.                                 MOST OFTEN  middlegame · 17.6 vs 13.6 per 100 moves
It cost you more winning chances than any
other cause: 32 of 145 (22%) · 19 blunders ·       ── What goes wrong ──────────────────────
−27% winning chance each.                          RANKED BY WINNING CHANCES LOST
Across all causes, your serious mistakes are       1  Ignoring your opponent's threat
most frequent in the middlegame: 17.6 per 100         32 of 145 (22%) · 19 blunders · −27% …
moves there, against 13.6 over all your moves.        Name the threat before each move  ▸ Full drill
                                                      [23.Qxc6 −34%] [17...g6 −34%] [30...Qe4 −34%]
── What goes wrong ──                                 Show all 5 · Drill these 5
Ranked by the winning chances each cause cost      2  Missing your own chances  …
you in total, not by how often it happened.        The rest: unclear 17, no cause found 40.
1 Ignoring your opponent's threat
  32 of 145 (22%) · 19 blunders · −27% …           ── Tactics your opponent got ─────────────
  Before each move, name what your opponent's      NO PROVEN TACTIC IN 96 OF 145 · ▸ How this is measured
  last move threatens.                             –  Forks  14 of 145 (10%) · 11 blunders · −30% …
  23.Qxc6 Scandinavian Defense · 6 Sep · −34%         Find the double attack first  ▸ Full drill
  17...g6 Scandinavian Defense · 6 Sep · −34%         1 of 14 also under: Ignoring your opponent's threat
  …                                                   [23.Qxc6 −34%] [25.g5 −34%]
                                                   The rest: pins 5.
── Tactics your opponent got ──
The tactic your opponent had after your move,      ── Where they happen ─────────────────────
or a piece of yours left undefended. It is         PER 100 OF YOUR MOVES · LINE = YOUR RATE, 13.6
named only when the board proves it: no tactic     ▸ How this is measured
was proven in 96 of your 145 serious mistakes.     [bars, unchanged, "· few" suffix stays]
…                                                  Time trouble: clock data on 4 of 145 · too few
── Where they happen ──
Serious mistakes per 100 of your moves. The        ── Openings above your average ──────────
line is your overall rate, 13.6; amber is          Your average: 4.7 per game   [table]
above it. Fewer than 10 mistakes behind a
figure: faded, and never called the worst.         31 analyzed games · 16 Oct   [Rebuild]
…                                                                    [Train this week →] (solid)
```

### Training Plan

```
BEFORE (237)                                        AFTER (~105, projected)
This week, 16 Oct to 22 Oct: 2 sessions of          This week, 16 Oct to 22 Oct: 2 sessions of
about 20 minutes.                                   about 20 minutes.
1 of 2 done. Next: session 2.                       1 of 2 done. Next: session 2.
┌ SESSION 1 · 3 POSITIONS ┐┌ SESSION 2 … ┐         ┌ SESSION 1 · 3 POSITIONS  ┐┌ SESSION 2 · 2 ┐
│ Spot the threat …       ││ Find your … │         │ Spot the threat …        ││ Find your own…│
│ (chip)(chip)            ││ (chip)      │         │ (Ignoring…▾)(Forks▾)     ││ (Missing…▾)   │
│ 23.Qxc6                 ││ 18...Rxf8   │         │ 23.Qxc6 −34%  17...g6 …  ││ 18...Rxf8 −34%│
│ Scandinavian · 6 Sep …  ││ Scandinav…  │         │ ✓ Done 17 Oct · 2 of 3   ││ [Start sess 2]│
│ ✓ Done … [Do it again]  ││ [Start 2]   │         │ [Do it again]            ││   (solid)     │
└─────────────────────────┘└─────────────┘         └──────────────────────────┘└───────────────┘
── What the sessions work on ──                     chip ▾ opens: Priority 1 of 2 · 32 of 145 (22%) ·
1 Ignoring … 32 of 145 … Before each move …        19 blunders · −27% each · full drill ·
2 Missing … 22 of 145 … Look at your own …         See in Pattern Report →
AND ONE TACTIC YOUR OPPONENT GOT
Forks 14 of 145 … Before you move, look …          ── Openings to review ──
── Openings to review ──                            Center Game · 6.0 vs your 4.7 per game · 5 games
Center Game: 6.0 serious mistakes per game …
Made 10/16/2026 from 31 analyzed games             Made 16 Oct · 31 games  [Make a new plan]
[Make a new plan]                                   Keeps this plan in your history
A new plan starts a new week. This one, …
```

### Insights Overview

```
BEFORE (255): subtitle · tiles · chart + 32-word description · 7 cards each with a sentence hint ·
              Resilience question + warning sentence · ISO date
AFTER (~145): tiles · chart (legend + "First 9 points: fewer games" + ▸ How this is measured) ·
              cards with ≤ 4-word micro-labels · "Accuracy by phase" (relabelled) ·
              tactics bars in neutral + "The rest: positional 154" ·
              Resilience: 52.3% | 39% · "13.3 points lower after a loss · take a break" · "20 Aug"
```

### Settings

```
BEFORE (324): Player + sentence · Library · Coverage line + month table · ranges + hints ·
              two engine cards with duplicated labels · AI models · Backups line + 31-word policy ·
              Estimated time as 2 sentences · two solid buttons
AFTER (~190): Player (▸ How usernames work) · Library · Coverage table with Total row
              (▸ By month (2)) · ranges with short hints · ONE engine table
              [setting | Chess.com games | Practice games] with tappable help ·
              AI models · Backups line (▸ How backups work) · Estimated time table ·
              one solid button at a time
```

### Today

```
BEFORE (55): Today · tagline · 4 tiles · This week · days · streak ·
             TODAY'S FOCUS · headline · "Drill 8 tactical positions from your own games" ·
             [Start session →] (ghost) ~12 min · Show evidence
AFTER (~40): Today · 4 tiles · days · streak · TODAY'S FOCUS · headline ·
             8 positions · ~12 min · [Start session] (solid) · ▸ Evidence (aria-expanded)
             (+ the plan's next session line, if F1 is chosen)
First run, BEFORE (123) → AFTER (~60): only the blocking rows · "3 ready ▸" · model progress
             · "Engine analysis works now; AI features wait"
```

### Projected word counts

| Screen | Before | Projected | Change |
|---|---:|---:|---:|
| Pattern Report | 404 | ~215 | −47% |
| Training Plan | 237 | ~105 | −56% |
| Overview | 255 | ~145 | −43% |
| Settings | 324 | ~190 | −41% |
| Today, first run | 123 | ~60 | −51% |
| Today | 55 | ~40 | **−27%** (D8) |
| Play | 99 | ~55 | −44% |
| Ask Prax | 55 | ~30 | −45% |
| Progress | 65 | ~50 | −23% |

These are hand estimates. Each milestone measures the real figure.

---

## 5. Flow: does each screen hand off to the next?

Target loop: **Today** (what now?) → **Pattern Report** (why?) → **Training Plan** (what this week?) → **Drill session** (do it) → **Progress** (is it working?) → Today.

| Step | Today | Problem |
|---|---|---|
| Today → why | No link to the report. Its headline (by phase) differs from the report's (by cause) | Findings 3 and 4 |
| Report → plan | No link to the plan; no solid next action; "drill these" doesn't exist although `drillLink()` does | Dead end |
| Report → game → ? | The game opens with no mistake selected ("Select a mistake…"), and its only exit is Back | No "drill this"; the user has to find the move again |
| Plan → drill | Works | |
| Drill (plan) → done | "Session 1 done: 2 of 3 solved" + "Back to the plan" link | No "Start session 2"; no route to Progress |
| Drill (SRS) → done | 🎉 + "N cards reviewed" + "Back to Today" (ghost) | An emoji outside the system; the correct count isn't shown; no route to Progress |
| Drill position → game | Plan drills link to the game (`Drills.tsx:137`); SRS cards don't, though `CardDto.gameId` exists | Missing back-link |
| Progress → ? | No next action | Dead end |

---

## 6. Functionality ideas (you choose)

Cost: XS < ½ day · S ≈ 1 day · M 2–3 days. "Backend" means you run Maven and restart.

| # | Idea | Data it needs | Exists? | Cost | Risk |
|---|---|---|---|---|---|
| F1 | Today shows the plan's next session ("Plan · session 2 of 2 · Find your own chances first") as the solid action when one is undone this week; otherwise the SRS session | `training-plan` + `progress` | Yes | S, frontend | Needs a rule for which comes first. I propose: plan first, SRS when none is left |
| F2 | Today's "Start session" becomes solid; Settings and the banner keep one solid button at a time | none | Yes | XS | none |
| F3 | Today's evidence panel gets a "Why → Pattern Report" link | none | Yes | XS | none. A bigger option is making Today's headline the report's top cause (backend, M); not recommended now |
| F4 | SRS "Session complete": no emoji; "4 of 6 correct" from `Attempt.correct`; buttons "See progress" (solid) and "Back to Today" | `Attempt` rows for the session | Yes | S, small backend DTO field or a client count | none |
| F5 | Plan session done: "Start session N+1" (solid) when one remains, else "See progress"; keep "Back to the plan" | `progress` | Yes | XS | none |
| F6 | Report cause: "Drill these 5" (ghost) per cause; page next action "Train this week →" (solid) to the plan | positions; `drillLink()` | Yes | XS | none |
| F7 | Game Analysis opened from a position selects that mistake (`/games/:id?ply=45`), adds "Drill this", and shows the engine's best move in SAN | game + ply; the FEN for SAN | Yes | S | none |
| F8 | SRS drill card links to its game | `CardDto.gameId` | Yes | XS | none |
| F9 | Progress gets a next action: "Today's session" or "Next plan session" | as F1 | Yes | XS | none |
| F10 | **Loop: "since this plan"** on each plan focus: the cause's rate per 100 moves **in games played after the plan was made** against the rate before, silent until 10 serious mistakes are behind the after-figure (`min_sample`) | game `played_at`, mistakes with cause, move counts from PGN | **Yes**, all already used by `ReportFactsBuilder` | M, backend query + DTO | Usually silent for the first one or two weeks. That is truthful, and expected |
| F11 | **Loop: recall on the plan's positions**: for plan positions that also have an SRS card, show "recall 3 of 4 since 16 Oct" | card ↔ (game, ply) via its move error; `Attempt.correct` | Partly: only positions that have a card | M, backend | Coverage gaps; show "n of m positions tracked" |
| F12 | **Loop, missing data:** record each plan-drill answer (game, ply, correct, time), so a position-level "solved before / now" exists for every plan position | none yet. **Needs** a table (or an `Attempt` without a card) written by `Drills.tsx` | **No** | M, backend + migration via `ddl-auto` | Without it, F11 covers only the positions that have a card |

**Not recommended:** a "vs previous report" delta. Each rebuild saves a new `PlayerPattern` row, so the data exists. But two reports cover overlapping games, and the delta mostly reflects new games rather than any change in how you play. That makes it unverified (principle 11). F10 measures the right thing.

---

## 7. Milestones and acceptance criteria

Every milestone ends with the full suite (chromium + narrow) green, `DESIGN_SYSTEM.md` updated, and a stop for your review.

**M0. Harness (tests only).**
- `e2e/fixtures/readability.ts` provides:
  - `visibleWords(page)`, the method from §0;
  - `paragraphs(page)`, the §0 definition;
  - `expandAll(page)`, which opens every `<details>` and every `[aria-expanded=false]`, then opens each `PositionChip` popover in turn and collects its text.
- A golden capture of every data token from the **current** rendering of every screen in the inventory. Tokens are numbers (including `%`, `/`, `×`, `−`, decimals), SAN moves and dates. They go into `e2e/fixtures/parity/<screen>.json`.
- Acceptance:
  - the golden files exist and are reviewed by you;
  - the baseline counts in §0 are reproduced;
  - no product change.

**M1. Pattern Report and shared parts.**
- Adds `Disclose`, `PositionChip` and `drill_short` (backend). Old reports without `drill_short` show the full drill until you Rebuild.
- Changes the board popover squares to `--board-light` / `--board-dark` (drift 3), because this component is being changed.
- Acceptance:
  - every golden token is present after `expandAll`;
  - ≤ 242 words; takeaway ≤ 20 words; section intros ≤ 15 words; items ≤ 8 words; 0 paragraphs;
  - the board opens on tap at 390px;
  - one solid button.

**M2. Training Plan.**
- Chip popovers (rank, the plan's stat line, full drill, link) and the openings row.
- Acceptance: parity; ≤ 142 words; 0 paragraphs; the 390px session cards work by tap.

**M3. Overview.**
- Micro-labels, the chart caveat, "Accuracy by phase", positional moved to the rest, neutral tactic bars, `20 Aug`.
- Acceptance: parity, with D5 applied; ≤ 153 words.

**M4. Settings.**
- The engine table, disclosures, tappable help, the Total row, the estimated-time table, one solid button at a time, D7.
- Acceptance: parity; ≤ 194 words; every help text reachable by tap and keyboard.

**M5. Today and first run.**
- The solid start button, compact checklist, no `!` marker, `aria-expanded` on the evidence toggle, plus the F1 and F3 ideas if you pick them.
- Acceptance: parity; first run ≤ 74 words; Today per D8.

**M6. Light pass.**
- Play rows, Ask Prax merge, Progress, Library, Drills empty state, Game Analysis duplicate instruction.
- Acceptance: parity; every screen meets the reading budget.

**M7+.** The flow ideas you choose, one milestone each.

---

## 8. Decisions I need from you

| # | Decision | My recommendation |
|---|---|---|
| D1 | What stays visible on each position: (A) the move only; (B) move + loss, with opening, date and board on tap; (C) as now | **B.** Keeping the loss means you can see what each mistake cost without opening it |
| D2 | Clicking a move on desktop: (A) opens the popover, then **Open game** (2 clicks to the game); (B) hover previews and click navigates on desktop, tap opens the popover on touch | **A.** One behaviour everywhere is easier to test and to learn |
| D3 | `reports.spec.ts` asserts exact sentences that this work shortens. I'd keep every test and the principle it names, and update the expected strings: "It cost you more…", "Across all causes…", "The line is your overall rate…", "fewer than 10: nothing to say…", "Your average: 4.7 serious mistakes per game.", "A new plan starts a new week…", the focus-region test, and the position-context check (now inside the popover) | Approve |
| D4 | `drill_short` as a backend field. This needs your Maven run, a restart and a Rebuild; until then the full drill shows | Approve (one source for the text) |
| D5 | Overview "(36%)" is each phase's accuracy as a share of the three summed. It is a number on screen, but it measures nothing. Drop it, or keep it behind a disclosure | **Drop it** and record it in the parity file as a deliberate removal. Keeping it would break principle 3 |
| D6 | "Positional 154" (Overview and Play) isn't a tactic | Move it to "The rest: positional 154" on the Overview, and "Most frequent pattern: positional (154)" on Play. Every number stays |
| D7 | "Written explanations per game" (retired) in Settings | Put both values in a disclosure marked "Retired"; remove them later |
| D8 | Today can reach only about −27% (≈ 40 words) without losing data. Its words are mostly tiles, weekday labels and the 10-word headline. The 40% target would need the headline templates in `TodayInsightService` shortened to ≤ 6 words (backend) | Accept ~−27% for Today, or approve shortening the headline templates |
| D9 | Amber's double meaning (drift 2) can't be fixed without a new colour, which isn't allowed | Keep it and document it in `DESIGN_SYSTEM.md` |

---

## 9. Tradeoffs, said plainly

- **Full drill advice behind "Full drill".** Most people will read only the 6–8-word imperative. That's acceptable because the imperative is the action. The longer text explains it, and the people who want it will open it.
- **Method behind "How this is measured"** (how tactics are proven, the threshold rule, amber, the chart axes, backup policy, username storage). Few people will open these. That's acceptable because each one explains how a figure is made; it doesn't change how the figure reads. Every caveat that does change the reading stays visible: few, the baseline, the overlap, too little clock data, the first nine points, no proven tactic in 96 of 145.
- **Opening and date move into the position popover** (D1). Recognising a position from its opening gets one tap harder, which weakens principle 9 a little. The loss stays visible, so ranking and scanning still work.
- **Two clicks from a move to its game on desktop** (D2-A). This is slower for desktop users. In return, phones get the board at all, which they don't today.
- **"What the sessions work on" goes away as a section.** The plan's reasons are one tap away inside each chip, and each session's goal heading says what it trains. Someone who never taps a chip won't see the stat lines on the plan page; they will see them on the Pattern Report.
- **Settings' month table goes behind "By month".** The totals stay visible, and the per-month split is one tap away. It's mostly checked when something is wrong.
- **Two figures change format** (`10/16/2026` → `16 Oct`, ISO → `20 Aug`). The parity test maps old to new explicitly; nothing is silently dropped.

---

## 10. Revision 1: after review (approved with changes)

**Measurement (applies from M0 on)**
- **Two numbers per screen.** *Prose words*: tokens without a digit that aren't SAN; these are cut against the budget. *Data tokens*: tokens with a digit, or SAN; these should stay flat. When data tokens drop, the milestone summary says what moved behind an interaction and why.
- **Parity uses (scope, value) pairs with counts**, not a bag of tokens. Scope is the chain of named regions, list items, rows and cards around the value. The check matches on the innermost two scope names, so an outer re-layout doesn't break it, but a value that leaves its item does.
- **Text data is captured too:** a vocabulary built from the fixtures (openings, causes, tactics, models, results, months) plus state words (few, done, verified, the rest…). It's matched in data-shaped runs: runs that contain a number or have ≤ 6 words. Links are captured as `@href`, and hover-only help (`title=`) is captured as method text.
- **Many fixture states**, not one: 23, including a report with few rows, an old-shape report, no report, long names, a plan with 0, 1 and 2 sessions done, an old-shape plan, no plan, first run, empty drills and a plan drill session.
- **`e2e/fixtures/parity/parity-exceptions.json`** is the one reviewed manifest for deliberate removals (D5), format mappings (dates, UCI → SAN) and scope moves (for example, plan priorities moving into chips).
- **The review artefact is `e2e/fixtures/parity/SUMMARY.md`:** per state, the metrics and the counts per section. The raw golden files aren't for review.
- **Paragraph** = one block of running text with more than 15 words, outside tables. No sentence splitting.
- **`expandAll` is scoped to `main`**, and to `[data-disclose]` / `[data-popover]` once components carry them. The legacy openers (`details`, `aria-expanded` buttons, "Show …" buttons, hover links) stay only until each screen migrates.
- **Phone:** the `narrow` project is a 683px desktop window, not a phone. Parity also runs at 390×844 with touch, and tap reachability is tested there.

**Decisions**
- **D1:** B. Principle 9 is amended to "context one tap away" in M1, with a long-name fixture.
- **D2:** A. The chip is a `<button aria-expanded aria-controls>`. The popover is non-modal and holds real `<a>` links. Escape closes it and returns focus. At 390px it is anchored to the bottom of the screen.
- **D3:** Tests assert meaning (the region, its values, the visible caveat), not exact strings.
- **D4:** `drill_short` is derived from the cause or tactic key when the DTO is built, and isn't persisted, so old reports get it without a Rebuild. All drill texts are templates (`ReportFactsBuilder.CAUSES` / `TACTIC_DRILLS`), not LLM output.
- **D5:** First establish what the card is meant to show. If it is meant to show mistakes by phase, link to the report's "Where they happen" instead of relabelling.
- **D6:** The Overview uses the report's verified classification. Play shows the top cause or top proven tactic, not "positional".
- **D7:** Approved.
- **D8:** Accept about −27% for Today. The headline templates stay.
- **D9:** "Above your average" becomes a brighter neutral fill against the tick, so amber means severity only.

**Revised order:** M0 harness → M1 Pattern Report (+F6, board colours, `drill_short`, semantic tests) → **M1.5 F12** (record plan-drill answers) → M2 Training Plan (+F5, interactive chip spec, a compact focus line keeping the priority order) → M3 Overview (D5 intent first; D6 via the verified source) → M4 Settings (solid Save when the form has changes, otherwise solid Sync; the engine table stacks at 390px) → M5 Today (F2; F3 linking to `#where-they-happen`; F1 only after an SRS priority rule is decided) → M6 light pass → M7+ F4, F7, F10, F11. F8 goes into whichever milestone touches the SRS card.

**Smaller rules**
- Drill text uses one section toggle, "Show full drills", not one disclosure per item.
- The Pattern Report's solid action reads "Train this week →" when a current plan exists, and "Make my plan" otherwise.
