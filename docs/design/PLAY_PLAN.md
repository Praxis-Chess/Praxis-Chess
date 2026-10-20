# Play & Improve: audit and plan

**Scope:** Phase 0 (audit) and Phase 1 (plan). **No product code has changed.** Two things were added on the test side only:
- three Play fixture states, with their parity goldens;
- a `PARITY_ONLY` filter, so capturing these goldens re-baselined nothing else.

I start Phase 2 after you approve the plan and answer the decisions in §5.

Sources read: `PlayImprove.tsx`, `PracticePatterns.tsx`, `OpponentProfileService`, `PlaySessionService`, `OpponentService` (book moves), `ChessIntelligence` (opening, phase and motif queries), `PracticePatternService`, `PerGameDetector`, `ImprovementService`, `PracticeGameArchiver`, `PlayController` and the Play fixtures.

---

## 0. Why your screenshot shows an empty left column

The "Your opponent" card in your screenshot comes from **my M6 change running against a backend that hasn't been restarted**.

- M6 changed the server to write each rationale line as "Label: facts". The page now renders those lines as a two-column list: label, then facts.
- Your running server still sends the old sentences. They have no label, so every label cell is empty. That is the 9.5rem gap.
- After your Maven run and restart, the labels appear.

The redesign below replaces this card anyway. Finding 3 is therefore "leftover layout, caused by version skew", not a failed render.

---

## 1. Baseline (two-part metric, desktop, default state)

| State | Fixture | Prose words | Data tokens | Paragraphs (>15 words) |
|---|---|---:|---:|---:|
| `play/new-player` | Not personalised: no analysed games | 51 | 0 | 1 |
| `play/default` | Opponent shown, **no practice games** | 51 | 7 | 0 |
| `play/one-game` | 1 analysed practice game, **below threshold** (your screenshot) | 141 | 11 | 3 |
| `play/six-games` | 6 games, **at or above threshold**, 2 patterns | 238 | 22 | 3 |

- **Measured against the current code**, which includes M6's "Label: facts" rationale.
- **Goldens:** `play.new-player`, `play.one-game` and `play.six-games` were captured now from this rendering. `play.default` keeps its pre-M6 golden plus its reviewed exceptions.
- **Targets** (your acceptance criteria): prose at most 50% of these baselines; data tokens no lower, unless explained.

---

## 2. Phase 0: findings 1–14, verified

| # | Verdict | Where | What the code actually does |
|---|---|---|---|
| 1 | **Confirmed**, and worse than it looks | `PlayImprove.tsx:484` | "Start a game" has `className="btn-primary"`, **a class that doesn't exist** in `index.css`, so it renders as the default ghost button. It is the page's last element, below the opponent card, history and patterns. At 1440×900 it is below the fold once any practice game exists. |
| 2 | **Confirmed** | `PlayImprove.tsx:501`; `OpponentProfileService.weakerColour` | "Your weaker colour" is resolved only on the server, at game start. The rule: both colours need at least 5 analysed games; then lower average accuracy, if the gap is 1 point or more, otherwise lower win rate; with too few games, White. The figures exist (`playerProfile` holds `white` and `black` ColorStats: games, wins, win %, average accuracy), but the preview doesn't send them. |
| 3 | **Explained** (§0) | `PlayImprove.tsx:208` | Version skew, not a missing board or avatar. |
| 4 | **Confirmed, and the definitions differ** | `OpponentProfileService.weakestOpening` vs `ReportFactsBuilder.openings` | **Play:** the lowest *average accuracy* among openings with at least 5 analysed games. **Training Plan** ("Openings to review", the same list as the report): *serious mistakes per game* at least 1.2× your average, with at least 3 games and at least 10 serious mistakes. Two measures and two thresholds, so the pages can name different openings. In the fixture both happen to name the Center Game. In Play, the 83% baseline sits two sentences after the 81%. |
| 5 | **Confirmed** | `OpponentProfileService.weakestPhase` → `ChessIntelligence.phasePerformance` | The phase with the most **blunders**, as a raw count: all analysed Chess.com games, blunders only, no denominator, no per-move rate. The report uses serious mistakes per 100 of your moves against your overall rate. |
| 6 | **Confirmed: three populations** | `ChessIntelligence.mistakePatterns`; `InsightsService.missedTactics`; `ReportFactsBuilder` | **Play (121):** every mistake *of every severity, inaccuracies included*, grouped by verified motif. **Overview (154 in the fixture):** verified motifs over diagnosed mistakes *in the selected speed* (rapid by default). **Report (96 of 145):** diagnosed *serious* mistakes only. Three filters, three numbers. M6 already stopped Play naming "positional" (it shows the top proven tactic), but the count still comes from the all-severity population. |
| 7 | **Confirmed, plus a truth problem** | `OpponentProfileService.calibrateSkill`, `MAX_SKILL = 14` | The scale is Stockfish's UCI "Skill Level", 0–20 (`StockfishPlayService`). Calibration is `(accuracy − 45) / 2.5`, clamped to 1–14. **83% maps to 15.2, so 14 is the cap**, and "calibrated to your 83%" overstates the calibration. The double negative is still in the strings your server sends; M6 removed it from new strings. |
| 8 | **Confirmed: eight thresholds, two units** | see the table below | |
| 9 | **Confirmed, and the text is untrue** | `PracticePatternService` lines 75–82 and 141 | Below 5 games the service returns **an empty pattern list** with the caveat "Here is what they show". It shows nothing. Above it are a 30-word opening caveat and the "Not measured" line: three caveats, no data. |
| 10 | **Confirmed, and it is tautological** | `PracticePatternService.openingCaveat`; `PracticeGameArchiver:126`; `OpponentService.bookMove` | The opponent forces only **its first two moves**, from the ECO family. Every practice game is then **archived with the target opening's ECO and name, whether or not the game reached it**. "Every one of these games is the Center Game" is read from that label, so it is always true, even when a game left the opening on move 3. The same label drives the history row and the post-game "Accuracy in Center Game" comparison. |
| 11 | **Confirmed** | `PlayImprove.tsx:142` | `toLocaleDateString()`: `1/10/2026` in your locale, `10/1/2026` in en-US. |
| 12 | **Confirmed: needs backend data** | `PlayController.history` → `PracticeGameSummary` | A row has result, date, opening, skill, rated and analysed. **Accuracy and mistake count aren't sent.** Both exist: `Game.accuracy` and `move_errors` on the archived game. |
| 13 | **Confirmed** | `PracticePatternService.UNTIMED_NOTE`; `PracticePatterns.tsx:31, 159`; the old opponent strings | Em dashes come from server strings and from the "Not measured — " and "— on this evidence so far" joins on the page. |
| 14 | **Confirmed** | `PracticePatterns.tsx:118` | "My playing patterns". Spelling is mixed app-wide: the UI has 41 "analysed" and 44 "analyzed"; the backend 15 and 9. The rest of the copy is British ("colour", "practise"). |

### New findings

| # | Finding | Where | Principle |
|---|---|---|---|
| 15 | **The opponent only uses the opening and the strength.** `targetPhase` and `targetMotif` are stored, but `OpponentService` never reads them. So the middlegame and tactic lines aren't reasons for *this opponent*; they're context. Listing them under "why this opponent" would mislabel them. | `PlaySessionService.start`, `OpponentService` | 3 |
| 16 | **"Practise this" is a dead link.** It goes to `/drills?motif=HANGING_PIECE`, but Drills reads only `positions`, `session` and `plan`, so you get a random set. | `PracticePatterns.tsx:52`, `Drills.tsx:166` | 10 |
| 17 | **Two rate units.** Practice patterns and the post-game report count mistakes per **40** moves (`PER_MOVES = 40`); the Pattern Report counts per **100**. | `ImprovementService`, `PhaseWeaknessDetector` | 7 |
| 18 | **Colour as decoration.** Pattern cards cycle loss, orange and amber stripes by index; "What to do:" is green. | `PracticePatterns.tsx:8, 45` | 2 |
| 19 | **Copy bugs:** "Show the 1 games"; "Early signal4/6" (no space between label and count); evidence labels print ISO dates "(2026-08-28)". | `PracticePatterns.tsx` | 7 |
| 20 | **The new-player state says one fact twice:** "No analysed games yet … Sync and analyse some games and it will be." and "Analyse some games and this opponent will be built from them." | `OpponentProfileService`, `PlayImprove.tsx:223` | 8 |
| 21 | **The patterns cards break the reading budget:** 4 paragraphs per card ("Why it costs you" is 23 words). | `PracticePatterns.tsx` | 12 |

### Finding 8: every threshold the page uses

| Threshold | Value | Unit | Defined in | Used for |
|---|---|---|---|---|
| `MIN_GAMES_FOR_TARGET` | 5 | games per opening, and per colour | `OpponentProfileService:29` | Targeting an opening; telling the weaker colour |
| Colour gap | 1.0 | accuracy points | `OpponentProfileService.weakerColour` | Accuracy decides, else win rate |
| `PerGameDetector.MIN_SAMPLE` | 5 | analysed practice games | `PerGameDetector:21` | Any practice pattern ("5 are needed") |
| `MIN_RECURRENCE` | 3 | practice games affected | `PerGameDetector:18` | A pattern must recur in at least 3 games |
| `ESTABLISHED_AT` | 10 | practice games | `PerGameDetector:24` | "Early signal" becomes "Pattern" |
| `ImprovementService.MIN_SAMPLE` / `WINDOW` | 5 / 5 | rated practice games | `ImprovementService:39, 42` | Post-game "better / worse" direction |
| `PracticePatternService.WINDOW` | 20 | practice games | `PracticePatternService:43` | Patterns use the last 20 |
| Report `MIN_SAMPLE` | 10 | serious mistakes | `ReportFactsBuilder:40` | Rates, causes, tactics, clock, "since this plan" |
| Report openings | 3 games, 10 serious, 1.2× | games, mistakes, ratio | `ReportFactsBuilder.openings` | Openings above your average, and Openings to review |
| Rate unit | per 40 moves vs per 100 | moves | `ImprovementService`, `PhaseWeaknessDetector` vs report | Every rate |

---

## 3. Text blocks by state (D Data · C Caveat · I Instruction · M Method · R Repetition · N Navigation)

| Block (words) | Class | Treatment |
|---|---|---|
| "Play & Improve" / subtitle "An opponent built from your games, then measured the same way." (11) | N | Replace the subtitle with the **takeaway**: "Next game: Center Game as Black · engine skill 14 of 20". That's data, and it states the promise by showing it |
| Opponent: "Steers toward …" (13) | D | Becomes a row: Opening · your accuracy vs your average · games. Changes source per decision P2 |
| Opponent: "Most blunders: middlegame · 97 of them" | D (wrong unit) | Replace with the report's rate and baseline, from the same source, under "Watch for" (finding 15) |
| Opponent: "Most frequent tactic: hanging piece · 63 times" | D (all-severity population) | Replace with the report's top cause, and its top proven tactic (D6 replacement) |
| Opponent: "Strength: … calibrated to your 68%" | D (overstated at the cap) | "Engine skill 14 of 20 · the cap; your 83% average maps to 15" |
| Start a game / "Your weaker colour" | N | Solid Start, moved to the top; the colour states which and why |
| New player: 2 sentences (33) | R | One line: "No analysed games yet: this opponent is a default (engine skill 5 of 20)" plus "Your games →" |
| "Earlier practice games" rows | D | A table: Result · Date (`1 Oct`) · Aimed at · Accuracy · Mistakes · Review. Accuracy and mistakes are added (finding 12) |
| "My playing patterns" + "Across your last N analysed practice games" | N | "Practice so far" with "1 of 5 games · patterns from 5" |
| Opening caveat (36) | C | Stays visible, short: "All aimed at the Center Game by design" (finding 10) |
| Small-sample card (19) | C (untrue: "here is what they show") | Folded into the progress label above |
| "Not measured — Time …" (14–19) | C | "Untimed: no clock data" in the same caveat line |
| Pattern card: title, "Early signal 4/6", finding | D | A row: title · 4 of 6 games · Early signal |
| Pattern card: "Why it costs you" (15–23) | M | Behind "Why" (one toggle for the section, as in the report) |
| Pattern card: "What to do" (10–11) | I | Kept, at 8 words or fewer, visible |
| "Practise this" / "Show the N games" | N | "Drill these positions" (a real `positions=` link, finding 16) / "Show 2 games" |

---

## 4. Phase 1: wireframes

### Above the fold, every state with an opponent (1440×900 and 390×844)

```
Play & Improve
Next game: Center Game as Black · engine skill 14 of 20                    ← takeaway (data)
[ Start a game ]  (solid, the only one)     Colour  Black ▾  · 79% vs 85% accuracy as White

THIS GAME                                      ← what the opponent actually does (finding 15)
Opening     Center Game · your accuracy 81% vs 83% average · 5 games
            ▸ Why this opening   (= the rule: most above your mistake average, from your Pattern Report)
Strength    engine skill 14 of 20 · the cap; your 83% average maps to 15
```

### Below it: "Watch for", the same source as the report and labelled as not targeted

```
WATCH FOR · from your Pattern Report · the opponent doesn't aim at these
Most often   middlegame · 17.6 vs 13.6 per 100 moves        → Pattern Report
Costliest    ignoring your opponent's threat · 32 of 145
```

### State: no practice games (`play/default`)

```
BEFORE (51 prose): subtitle · opponent card (4 sentence rows) · [Start a game] (ghost, last) · Your weaker colour ▾
AFTER  (~25 prose): takeaway · [Start a game] · colour · THIS GAME · WATCH FOR
                    (no practice section: nothing played yet is not a finding)
```

### State: one game, below the threshold (`play/one-game`)

```
BEFORE (141 prose, 3 paragraphs): … history row "Lost 1/10/2026 Center Game Review" · "My playing patterns"
       · "Across your last 1…" · 36-word caveat · "Only 1 … Here is what they show…" (nothing shown) · "Not measured — …"
AFTER (~55 prose, 0 paragraphs):
PRACTICE SO FAR                                   1 of 5 games · patterns from 5
Result  Date   Aimed at     Accuracy  Mistakes
Lost    1 Oct  Center Game  80%       1          Review · Drill these
Center Game accuracy · practice 80% vs your games 81%        ← the one cross-population comparison (P5)
Untimed: no clock data · all aimed at the Center Game by design
```

### State: six games, at the threshold (`play/six-games`)

```
AFTER (~110 prose):
PRACTICE SO FAR                                   6 games · patterns from 5 · last 20 counted
[table of games, as above; abandoned and in-progress rows stay out]
PATTERNS                                                    ▸ Why (one toggle)
You castle late · 4 of 6 games · Early signal
   Castle inside the first ten moves                      Show 2 games
The same tactic keeps catching you · 5 of 6 games · Early signal
   Drill the hanging-piece positions                      Drill these positions · Show 1 game
Untimed: no clock data · all aimed at the Nimzovich-Larsen Attack by design
```

### State: new player

```
Next game: open opening as White · engine skill 5 of 20 (a default: no analysed games)
[ Start a game ]   Colour White ▾
Your games →   (one line; the duplicate sentence goes)
```

---

## 5. Decisions I need

| # | Decision | My recommendation |
|---|---|---|
| **P1** | **Thresholds** (finding 8) | Two named thresholds and one unit. **`MIN_GAMES = 5`** for any claim about games (opening target, weaker colour, practice patterns, post-game direction); **`MIN_MISTAKES = 10`** for any rate or share. Recurrence (3 games affected) and "established" (10 games) stay as named pattern rules. Every rate is **per 100 moves**, so practice moves from 40 to 100. The report's opening rule would move from 3 to 5 games (with 10 serious mistakes): a small change to the report and plan, documented in `DESIGN_SYSTEM.md` |
| **P2** | **One definition of "weakest opening"** (finding 4) | The **report's**: the opening most above your average serious mistakes per game, read from the **stored report** (`PlayerPattern.facts_json`), so Play, the report and the plan name the same opening from the same numbers. When none qualifies, leave the opening open and say so. The fallback would be lowest accuracy, but that brings the second definition back |
| **P3** | **D6 replacement and finding 15** | Split "This game" (opening and strength: what the opponent does) from "**Watch for**" (worst phase and top cause, from the stored report, labelled as not targeted). The facts are the same as the report's, from the same source, with no new count |
| **P4** | **Skill wording** | "Engine skill 14 of 20". At the cap, say so: "the cap; your 83% average maps to 15" |
| **P5** | **"Measured the same way" vs real games** | Keep `ImprovementService`'s rule. Only **opening accuracy** crosses populations (practice 80% vs your games 81% in that opening); everything else compares practice with practice. It argues that Chess.com opponents are a different population, and I agree. Your sketch's example is exactly the case it allows |
| **P6** | **Truthful opening label** (finding 10) | Label practice games "**aimed at** Center Game", in the history and the caveat. Classifying the opening actually reached (an ECO lookup from the moves) is idea I6 below |
| **P7** | **Spelling** | British, matching "colour" and "practise": "**analysed**", in one sweep across the UI and the backend's player-facing strings. A separate small milestone, because it touches many screens |
| **P8** | **Above the fold at 390×844** | The takeaway, Start and colour come first; everything else follows. On a phone the colour figure drops under the select |

---

## 6. Ideas (you choose)

| # | Idea | Data needed | Exists? | Cost | Risk |
|---|---|---|---|---|---|
| I1 | Override opening and strength, with the recommended choice preselected | `start(color, skill)` and `preview(skill)` already accept a skill; an opening override needs a `targetEco` parameter | Skill yes; opening no | S (skill, frontend only) · M (opening, backend) | Players may avoid their weak opening. Mitigate by showing which is recommended |
| I2 | After a game: "Drill this game's mistakes" | The archived game's id and its mistakes' plies (`move_errors`) | Yes | XS (the `drillLink` the report already uses) | None |
| I3 | Practice mistakes into the review deck or Training Plan | `CardGeneratorService` would need to accept practice games | Partly | M | **It mixes populations.** Practice games are kept out of every real-play statistic by design (`GameSource.PRACTICE`). I'd do it only for the review deck, tagged as practice, never in the report or plan |
| I4 | The opening bias (finding 10) | Book lines per ECO family exist | Yes | S: rotate among the report's openings above average (up to 3) · XS: keep it as is | Rotating weakens per-opening repetition. **Your call:** depth on one weakness vs breadth |
| I5 | "Since you started practising": the real-game rate in games after your first practice game vs before | Practice start dates, real games' dates, report facts (the F10 machinery) | Yes | M | Silent until `MIN_MISTAKES`. Correlation, not cause: labelled "since", never "because of" |
| I6 | Classify the opening actually reached, from the moves | An ECO-by-moves table (`EcoTable` is by code only) | No | M | Needs an opening book dataset |
| I7 | Fix "Practise this" (finding 16) to drill the pattern's own positions | Pattern evidence has `game_id` and `ply` | Yes | XS | None. **I recommend doing it in milestone P3 regardless** |

---

## 7. Milestones

Each one ends with: parity in all play states (with `expandAll`), the full suite (chromium and narrow), `DESIGN_SYSTEM.md` updated, and a stop for your review.

| # | Milestone | Acceptance |
|---|---|---|
| **P0** | Fixtures and goldens: **done in this phase** (three states added) | 4 Play states captured; only those goldens written |
| **P1** | **Backend facts** (needs your Maven run and a restart). The opponent preview gains structured fields: opening and why (P2, from the stored report), accuracy vs average, games, skill with cap and calibrated value, colour with both colours' figures, and watch-for (P3, from the report). History rows gain accuracy and mistakes. Patterns gain progress toward `MIN_GAMES` and an "aimed at" label. Practice rates move to per 100 moves. No em dashes in player-facing strings | Unit tests for the new fields; same-source numbers asserted (report and Play read one `facts_json`) |
| **P2** | **Page layout:** takeaway, a solid Start above the fold (1440×900 and 390×844), colour with its figure, This game and Watch for, the Practice so far table and the compact below-threshold state | Prose ≤ 50% of each state's baseline; data tokens not lower (explained if they are); 0 paragraphs; no em dash; one solid button; parity passes; every figure next to its baseline |
| **P3** | **Patterns at the threshold:** rows instead of cards, one "Why" toggle, I7's real drill link, neutral colours, `1 Oct` dates, the plural fix | As P2; the "Drill these positions" `href` is asserted |
| **P4** | **Thresholds (P1):** named constants, the report's opening rule at 5 games, per-100 everywhere, `DESIGN_SYSTEM.md` §2 principle 5 rewritten with the table | Report, plan and Play tests updated to the named thresholds |
| **P5** | Spelling sweep (P7), if approved | No "analyzed" in player-facing copy |
| **P6+** | The ideas you choose | Per idea |

---

## 8. Tradeoffs, said plainly

- **"Why this opening" goes behind a disclosure.** It's method: the opening is shown with its figures. Few people will open it, and that's acceptable because the row already says how it compares with your average.
- **The pattern cards' "Why it costs you" goes behind one toggle.** It's an explanation, and the finding and what to do stay visible. People who skim lose the reasoning, but they keep the instruction.
- **"Watch for" moves below the start button.** It isn't what the opponent does (finding 15), so it no longer competes with the action. It's still on the first screen at desktop width; on a phone it's below the fold.
- **Using the stored report as the source (P2, P3)** means Play's facts are as old as the last report build. The report rebuilds after each analysis run, so the lag is at most one run. In return, Play and the report can never disagree.
- **Moving the report's opening rule from 3 to 5 games (P1)** can drop an opening with 3–4 games from the report and plan. That's data the report shows today; the change would be recorded as a deliberate parity exception, not done silently.

---

## 9. Results (built, branch `feat/play-redesign`)

Approved: all decisions P1–P8 as recommended; ideas I1, I2, I7.

| Milestone | What shipped |
|---|---|
| **P1 · backend** | `report/Thresholds` (`MIN_GAMES` 5, `MIN_MISTAKES` 10, `PER_MOVES` 100) wired into the report, the opponent, practice patterns and the post-game report. `OpponentProfileService` reads the **stored Pattern Report** for the opening (its first opening above your average) and Watch for (phase rate, top cause, top proven tactic), and returns structured `opening`, `skill` (level, scale, calibrated, capped, requested), `colour` (weaker, basis, both colours' figures) and `watch_for`. The report's opening rule needs 5 games (was 3). History rows carry accuracy and blunder, mistake and inaccuracy counts (one grouped query). Practice rates are per 100 moves (direction steps 0.3 → 0.75; claim floor 1.0 → 2.5, the same thresholds). Patterns: `min_games`, `aimed_at`, a ≤ 8-word `do_now`, "6 Sep" evidence dates, no em dashes, no untrue small-sample caveat. The post-game report carries `drill_plies` |
| **P2 · page** | Takeaway first; the only solid button above the fold (1440×900 and 390×844); colour named with its figures; This game; Watch for (closed); Practice so far table |
| **P3 · patterns** | Rows instead of cards; one Details toggle; I7's real drill link; neutral colours; plural fix; dates |
| **P4 · thresholds** | `DESIGN_SYSTEM.md` principle 5 rewritten with the table |
| **P5 · spelling** | British in player-facing copy (13 patterns, 9 files); identifiers untouched |
| **I1, I2** | Strength select (recommended preselected; sent with the game). "Drill this game's mistakes (n)" after a game |

**Parity:** every Play state passes, with reviewed exceptions for the definition changes (P2/P3: the opponent's old accuracy, blunder-count and all-severity figures replaced by the report's), the de-duplicated "Aimed at" column, the untrue small-sample sentence (finding 9), and format mappings (dates, the run-together "Early signal4/6" → "4 of 6 games", the colour option, "analysed"). The harness gained partial removals (`count`) and either-or text formats.

**Needs your Maven run and a restart:** `OpponentProfileService` (new constructor: report repository and ObjectMapper), `PlayController` (history counts), `MoveErrorRepository.countBySeverity`, `PracticePatternReport`/`PracticePattern` (new fields), `PatternDetector.doNow`, `ImprovementReport.drillPlies`. New tests: `OpponentProfileServiceTest`; `PgnDetectorTest` checks every `doNow`.
