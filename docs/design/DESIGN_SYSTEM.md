# Praxis Chess: Design System and Storyboard

This is a hand-off document for a designer, a senior developer or an AI agent: how the Praxis Chess UI looks, why, and how a user moves through it. Every screenshot in [`screens/`](screens/) is the real app (branch `feat/phase-12`), rendered by Playwright with the synthetic fixtures in `frontend/e2e/fixtures/`. None of it is real player data.

- **Source of truth for tokens:** [`frontend/src/index.css`](../../frontend/src/index.css). If this document and that file disagree, the file wins; fix this document.
- **Screens:** 1440×900 desktop (full page where the page scrolls), plus two at 390×844 phone width.
- **To recapture:** the capture spec is a throwaway Playwright test. Put it in `frontend/e2e/tests/`, run it with `npx playwright test zz-capture --project=chromium`, then move it out again. It hides the dev-only Prax debug pill and uses the current model line-up.

---

## 1. What the product is

Praxis Chess is a self-hosted chess coach for one player. It syncs that player's Chess.com games, checks every move with Stockfish, diagnoses each serious mistake (its cause and the tactic involved), and turns the mistakes into spaced-repetition drills, a pattern report and a weekly training plan. Prax is the in-app assistant: an animated point cloud and a chat that answers only from measured data or cited sources.

The audience is one club player, roughly 800 to 1800 Elo, using it on their own laptop. It is dark only. The desktop browser comes first; phone width has to work but is secondary.

## 2. Design principles

These are applied in code review. The Phase 12 tests in `e2e/tests/reports.spec.ts` assert several of them by name.

1. **Instrument-panel minimalism.** A warm near-black canvas with translucent panels, hairline borders and a small crosshair mark on each card. It should read like a measuring instrument, not a game lobby. Decoration is limited to the grain overlay and Prax.
2. **One meaning per colour.**
   - Pink (`--orchid`) marks only what can be clicked: links, the primary button, the active tab.
   - Amber (`--warn`) is the *mistake* severity colour, and nothing else. "Above your own average" is shown by position against the baseline tick and a brighter neutral fill, not by a colour.
   - Green, amber and red (`--gain` / `--warn` / `--loss`) carry results and severity.
   - Everything else is neutral grey.
3. **Truthful labels.** Each label says exactly what is measured. "Costliest" means total winning chances lost, not frequency. A rate is "per 100 of your moves". A chart labelled "Rolling avg (up to 10 games)" says that the first nine points average fewer games.
4. **Compared to what.** Each figure appears next to its baseline: your overall rate, or your average per game. A bar is drawn against a baseline tick.
5. **One threshold.** `min_sample` is 10. Below it, a figure is drawn faded, marked "few", and never ranked or called the worst.
6. **Account for the whole.** A ranked list ends with "The rest: …" so that every mistake is counted somewhere. When the same mistakes appear under two headings, the overlap is stated.
7. **One format per fact.** Each fact has a single wording, used everywhere: `32 of 145 (22%) · 19 blunders · −27% winning chance each`. Rates always have one decimal (`17.6`), so they line up. Dates read `6 Sep` (a fixed month list, never `Intl`).
8. **Say each fact once.** A page opens with one sentence of takeaway, then the evidence. Explanatory text is short and appears once per section.
9. **Recognition over recall: context one tap away.** A move is shown with what it cost (`23.Qxc6 −34%`). Its board, opening and date are one click, tap or Enter away in the position popover, with links to the game and to a drill of that position. Hover alone never reveals anything, because phones have no hover.
10. **Feedback and state.** Plans show their dates, what is done ("✓ Done 17 Oct · 2 of 3 solved") and what is next. The next action is the only solid pink button.
11. **Verified or silent.** The LLM never states a number or claim the engine hasn't measured. Report prose is off by default. A diagnosis shows "✓ Verified" only when the board proves it.
12. **Show, don't tell.** A number on screen isn't repeated in a sentence. Prose carrying numbers becomes a stat row, a bar, a table or a tile. A section explains itself with a micro-label or not at all. Method ("how this is measured") goes behind one disclosure per section. Caveats that change how a figure reads stay visible, as short as possible: few, the baseline, overlaps, too little data.
   - **No data loss.** Every fact stays *reachable*: visible by default, or one click or tap away. Hover-only doesn't count, because phones have no hover.
   - **Reading budget:**

     | Element | Budget |
     |---|---|
     | Page takeaway | 1 sentence, ≤ 20 words (`data-reading="takeaway"`) |
     | Section intro | none, or 1 sentence ≤ 15 words (`data-reading="intro"`) |
     | Item explanation | ≤ 8 words visible (`data-reading="item"`) |
     | Paragraphs (> 15 words in one block, outside tables) | 0 by default on report and plan pages |

   - **Two numbers per screen.** *Prose words* (no digit, not a move) are what there is to read; cut them. *Data tokens* (numbers and moves) are what there is to see; they stay flat. If data tokens drop, the change has to say what moved behind an interaction and why.
   - **Enforced by** `e2e/tests/readability.spec.ts`, against goldens captured before the readability work (section 12).

## 3. Colour tokens

All tokens are defined on `:root` in `index.css`. The legacy aliases are kept so that older components still work.

### Ground and surfaces

| Token | Value | Use |
|---|---|---|
| `--canvas` (`--bg`) | `#121110` | Page background: warm near-black |
| `--canvas-deep` | `#0C0B0A` | Nav bar, deepest wells |
| `--surface-1` (`--surface`) | `rgba(255,255,255,.035)` | Card fill (translucent, blurred) |
| `--surface-2` | `rgba(255,255,255,.06)` | Inputs, hovered rows, raised fills |
| `--hairline` (`--border`) | `rgba(255,255,255,.08)` | Every border and divider |
| `--hairline-lit` | `rgba(231,166,214,.28)` | Border of an interactive element (ghost button, chip) |

### Text

| Token | Value | Use |
|---|---|---|
| `--text-primary` (`--text`) | `#F2EDEA` | Headings, values, body that matters |
| `--text-secondary` (`--text-muted`) | `#A29B96` | Supporting text, stat lines |
| `--text-tertiary` | `#6D6662` | Micro-labels, rank markers, faded rows |

### Accent

| Token | Value | Use |
|---|---|---|
| `--orchid` (`--accent`) | `#E7A6D6` | Links, primary buttons, active nav, focus ring. **Interactive only.** |
| `--mauve-deep` | `#B0679F` | Pressed or hovered solid button, chart gradient end |
| `--accent-wash` (`--accent-dim`) | `rgba(231,166,214,.12)` | Active tab pill, selected row |

### Semantic

| Token | Alias | Value | Meaning |
|---|---|---|---|
| `--gain` | `--green` | `#B9D96C` | Win, verified, done, good |
| `--warn` | `--yellow` | `#E5B04B` | Mistake severity only |
| `--loss` | `--red` | `#E2664A` | Blunder, loss, failure, error text |
| `--neutral` | | `#8A847E` | Draw, unknown |
| `--orange` | | `#D98A54` | Defined for inaccuracy, but `.badge-inaccuracy` uses orchid (§11) |

### Chart and board

| Token | Value | Use |
|---|---|---|
| `--rating` | `#7DB9E8` | The Elo line: sky blue, kept clear of the accent and the result colours |
| `--per-game` | `#9D8CF0` | Per-game accuracy line: violet |
| `--grey-1/2/3` | `#7A736E` / `#635C58` / `#423C39` | Neutral bars, empty tracks, disabled |
| `--board-light` | `#E3DBD1` | Light squares (bone) |
| `--board-dark` | `#4A4340` | Dark squares (warm graphite) |

## 4. Typography

| Role | Stack | Where |
|---|---|---|
| Display / UI | `'Inter Tight', 'Archivo', 'Neue Haas Grotesk Display', 'Segoe UI Variable Display', system-ui, sans-serif` (`--font-display`) | Everything by default |
| Mono / data | `'JetBrains Mono', 'IBM Plex Mono', 'Cascadia Code', Consolas, ui-monospace, monospace` (`--font-mono`) | Numbers, moves, model names, tables, micro-labels |

Neither font family is bundled or loaded. There is no `@font-face` and no Google Fonts link, so each machine renders the first family it has installed. On Windows that is usually Segoe UI Variable Display and Cascadia Code or Consolas, which is what the screenshots show. To pin the look, self-host Inter Tight and JetBrains Mono, for example with `@fontsource`.

### Scale (as used)

| Use | Size | Weight | Notes |
|---|---|---|---|
| Page title (`h1`) | 1.35rem | 700 | Letter-spacing −0.02em |
| Takeaway sentence | 1.1rem | 400 with `<strong>` | The first line of a report or plan |
| Today's focus headline | ~1.6rem | 700 | Only on Today |
| Section title | 1.08rem | 700 | Hairline rule above, 18px padding |
| Item title | 0.9rem | 600 | |
| Body | 14px (0.875rem) | 400 | Line-height 1.5 |
| Muted / stat line | 0.78–0.8rem | 400 | `--text-secondary`, line-height 1.55 |
| `.micro-label` | 10px | 500 | Mono, uppercase, letter-spacing .14em, `--text-tertiary` |
| `.badge` | 0.65rem | 600 | Mono, uppercase |
| Stat tile value | ~1.6rem | 700 | Coloured by meaning |

Numbers always use `font-variant-numeric: tabular-nums`, and a number inside prose is wrapped in `.mono`.

## 5. Spacing, shape and depth

- **Spacing.** Flex or grid with `gap`; page sections are 26px apart, items in a section 14–18px apart, lines inside an item 4–6px apart. Cards have 20px of padding (16px in dense grids).
- **Radii.** Small and precise: cards and inputs 4px, buttons 3px, badges 2px, chips 10px (pill). Nothing is fully rounded except the week dots.
- **Depth.** The only shadows are an inset highlight on cards and the opaque board popover. Depth otherwise comes from translucency and `backdrop-filter: blur(18px) saturate(115%)`.
- **Grain.** A fixed noise overlay at opacity 0.03 sits over the canvas.
- **Layout.** A sticky 52px nav. `main` has a max-width of 1280px, 24px of padding and a `--prax-gutter` on the right where Prax lives. Most content columns are capped at about 860–960px so reading width stays comfortable.

## 6. Components

| Component | Class or file | Spec |
|---|---|---|
| Button (ghost, the default) | `button` | Orchid text, 0.82rem weight 600, `--hairline-lit` border, radius 3, padding 7×15 |
| Button (primary) | `button.solid` | Filled orchid with dark text. **One per view: the next action.** |
| Button (secondary) | `button.secondary` | Neutral text, hairline border. Used for Rebuild, Do it again, Exit |
| Card | `.card` | `--surface-1`, blur, hairline, radius 4, padding 20, a crosshair `+` drawn by `::after` at the top right |
| Plain card | `.card.plain` / `.card.no-mark` | No crosshair; used for repeated items such as session cards |
| Stat tile | Today, Progress | A small card with a big coloured number and a label under it |
| Badge | `.badge` plus `.badge-blunder/-mistake/-inaccuracy/-win/-loss/-draw/-analyzed/-pending/-analyzing/-failed` | Mono uppercase, outlined in the semantic colour with a 10% tint |
| Micro-label | `.micro-label` | Eyebrow or column label; tertiary grey |
| Chip | Training Plan focus (`FocusChip`) | 0.7rem pill with a `--hairline-lit` border. A label chip has secondary text. An **interactive** chip has orchid text and a ▾ caret: it is a `PopoverButton` that opens what the plan recorded for that focus (rank "Priority 1 of 2", stat line, short and full drill, "See in Pattern Report →") |
| Tabs | Library, Insights | Library: an orchid underline. Insights: an `--accent-wash` pill, in a `role="group"` div (not `<nav>`) |
| Filter pills | Insights speed filter | Pill outline; the active one is lit |
| Table | `table` | Hairline row rules, no zebra stripes, mono micro-label headers, numbers right-aligned |
| Comparison bars | `ReportParts.Bars` | Equal tracks and a full-height baseline tick at the same x on every row. A row above the baseline has a brighter neutral fill (`--text-primary` at 62%), the rest `--grey-3`. Faded, with "· few", below the threshold. Mono value on the right. Never amber |
| Position chip | `ReportParts.PositionChip`, listed by `ReportParts.Positions` | A small outlined button: the move in orchid mono and its loss (`23.Qxc6 −34%`). It opens a popover with the board (in the board tokens), "White to move", opening · date · loss, **Open game** (`/games/:id?ply=n`) and **Drill this**. `Positions` shows the first 3, then "Show all n" |
| Popover | `ReportParts.PopoverButton` | A `<button data-popover aria-expanded aria-controls>`. It opens a non-modal `role="dialog"` on click, tap or Enter. The panel is portalled to `<body>`, so a card's `backdrop-filter` stacking context can't paint over it. It is opaque, holds real links, and sits beside the button, flipping left at the right edge; below 560px it docks to the bottom of the screen. Escape closes it and returns focus; a tap outside closes it |
| Disclosure | `ReportParts.Disclose`, class `.disclose` | A native `<details data-disclose>` whose summary is a small orchid label, "How this is measured" by default. One per section, for method only, never for a caveat |
| Section label | `ReportParts.sectionLabel` | One muted line under a section title: what is measured ("Ranked by winning chances lost") or the caveat that changes how it reads ("No proven tactic in 96 of 145"). Carries `data-reading="intro"` |
| Chessboard | `react-chessboard` 4.6.1 | Bone and graphite squares, coordinates in the corners |
| Setup checklist | `SetupChecklist.tsx` | ✓ ready, ✕ missing, … needs attention (never `!`, a chess annotation), – off; "Name · detail" rows with a fix link. Inline under Sync Now in Settings. On Today only while something blocks: the blocking rows first, "n ready ▾" and "Models (n) ▾" one tap away |
| Help | `ReportParts.useHelp` | Replaces every hover-only `title=` tooltip: an orchid ⓘ `<button data-disclose aria-expanded>` beside a label or heading that shows one line of help under it on click, tap or Enter |
| Engine table | `Settings.tsx` (`EngineTable`) | One row per setting: the label and range once, with ⓘ help, then a value per engine in a `role="group"` named after the engine. Below 760px it stacks, and each value shows its engine's name (`.only-stacked`) |
| Labelled rows | Play (`OpponentCard`) | A `<dl>`: each line "Label: facts" from the server becomes a micro-label and its facts, so the numbers stay without the sentence around them |
| Since this plan | `TrainingPlan.tsx` (`SinceLine`) | In a focus chip's popover: "Since 16 Oct · 1.3 per 100 moves, from 3.0 · 6 games". Silent until 10 diagnosed serious mistakes have been played since the plan ("too little play to compare: 4 of 10") |
| Recall line | `TrainingPlan.tsx` (`RecallLine`) | On a session card: "Reviews since 16 Oct: 3 of 4 correct · 2 of 3 positions in your deck"; "Not in your review deck" when none are cards, never "0 recalled" |
| Charts | Recharts | Dotted grid, mono axis text, `isAnimationActive={false}`, time x-axis with real dates, dual axis for accuracy and Elo. The rolling average is drawn in `--text-primary`, never the accent; win-rate bars are green, neutral (`--grey-1`) or red |
| Prax | `src/prax/` | A WebGL point cloud in the right gutter (`PraxAnchor`). States include dormant, aware and insight (`prax/state/runtime.ts`). Respects reduced motion. The bottom-left debug pill exists in development only |

## 7. States

- **Loading:** `LoadingSpinner` with a sentence ("Loading the report…").
- **Empty:** one sentence that says why, plus the way out on its own line ("No drills yet: each analysed blunder becomes one." then "Your games →"), or the single action ("Make my plan").
- **Old shape:** "This report was made by an older version. Rebuild it to see the current one."
- **Error:** `role="alert"`, `--loss` text, and what to do ("There is nothing to plan from yet: analyze some games first.").
- **Too few:** faded, with a "· few" suffix; the threshold itself is in the section's "How this is measured". A comparison over time with too little play says so ("too little play to compare: 4 of 10 serious mistakes") rather than showing a change.
- **Done:** a `--gain` check plus the date and result.

## 8. Accessibility and responsive rules

- A landmark per page; sections carry `aria-label` or `aria-labelledby`, which the tests query by role.
- Only one `navigation` landmark exists. Sub-tabs are `role="group"`.
- Nothing is hover-only. A position's board, help text and every other detail open on click, tap or Enter (`PopoverButton`, `Disclose`, `useHelp`), and every opener carries `aria-expanded`.
- A disclosure or popover holds real links (open in a new tab works) and returns focus to its opener on Escape.
- An inline link inside a sentence must not rely on colour alone (axe `link-in-text-block`): put the way out on its own line.
- Live text such as "1 of 2 done" uses `aria-live="polite"`.
- Reduced motion turns off Prax animation and transitions.
- `.stack-on-phone` puts grids into one column below 760px. Card grids use `repeat(auto-fit, minmax(min(100%, 250px), 1fr))`.
- The `narrow` Playwright project is a 683px desktop window (half a laptop screen), **not a phone**. Phone checks run at 390×844 with touch in `readability.spec.ts`.

## 9. Screen inventory

| # | Screen | Route | Screenshot |
|---|---|---|---|
| 1 | Today | `/` | `01-today.jpg` |
| 2 | Today, first run | `/` (setup incomplete) | `02-today-first-run.jpg` |
| 3 | Progress | `/progress` | `03-progress.jpg` |
| 4 | Library | `/library` (Games and Drills tabs) | `04-library.jpg` |
| 5 | Game Analysis | `/games/:id` | `05-game-analysis.jpg` |
| 6 | Play & Improve | `/play` | `06-play.jpg` |
| 7 | Practice review | `/play/review/:id` | `07-practice-review.jpg` |
| 8 | Ask Prax | `/ask` | `08-ask-prax.jpg` |
| 9 | Insights: Overview | `/insights` | `09-insights.jpg` |
| 10 | Insights: Pattern Report | `/insights/patterns` (`/patterns` redirects) | `10-pattern-report.jpg` |
| 11 | Insights: Training Plan | `/insights/training` (`/training` redirects) | `11-training-plan.jpg` |
| 12 | Drills (empty) | `/drills` | `12-drills.jpg` |
| 13 | Drill session | `/session/:id`, `/drills?positions=…&session=n&plan=id` | `13-drill-session.jpg` |
| 14 | Settings | `/settings` | `14-settings.jpg` |
| 15 | Today, phone | `/` at 390px | `15-phone-today.jpg` |
| 16 | Training Plan, phone | `/insights/training` at 390px | `16-phone-training-plan.jpg` |

Not captured: `/dashboard` and `/games` (legacy), plus `/evidence/:id` and `/labels` (measurement tools for the developer, not product screens).

## 10. Storyboard

### A. First run to first insight

1. **Install and open** → *02-today-first-run.* Today shows "Finish setting up Praxis" with only what blocks: Chess.com username ✕ with "Enter it in Settings →", and AI models … downloading. "3 ready ▾" and "Models (2) ▾" open the rest. "Engine analysis works now; AI features wait for their model." 
2. **Settings → Player** → *14-settings.* The user enters a username, then presses **Sync Now**: the solid button while nothing is unsaved (with unsaved changes, **Save settings** is solid instead). Under it, "Setup · 5 ready · 2 off" expands into the full checklist. Coverage is one table with a Total row; the engine settings are one table with a column per engine; help is behind ⓘ; backups show a one-line status with the policy and the snapshot list one tap away; Estimated time is a table.
3. **Analysis runs.** `SyncStatusBanner` under the nav shows progress. Games appear in the **Library** → *04-library*, grouped with "Needs analysis" first. Each row shows the result in colour and its accuracy.
4. **Back to Today** → *01-today.* The tiles show due, learning, review and new cards. The week names its dates ("17 Aug – 23 Aug") and its dots record practice days in the results green. **Today's focus** gives one headline drawn from real counts, the session size and duration, the solid **Start session**, and Show evidence, which ends with "Where your mistakes happen →" (the report's phase bars).

### B. Understand the weakness

5. **Insights: Overview** → *09-insights.* A speed filter (an unanalysed speed reads "· results only"), four tiles, the Accuracy and Elo trend ("First 9 points: fewer games"; method behind a disclosure), opponent strength, mistakes by phase (all flagged mistakes, with "per 100 moves →" to the report), missed tactics (proven only; "The rest: positional 154 (no tactic proven)"), time of day and day of week, resilience ("13.3 points lower after a loss · rest first"), thrown-away wins and openings. Each card has a title and at most a few words.
6. **Pattern Report** → *10-pattern-report.*
   - It opens with a takeaway, "Your costliest habit: ignoring your opponent's threat.", the label "Most often: middlegame · 17.6 vs 13.6 per 100 moves", and the one solid action: **Train this week →**, or **Make my plan** when there is no plan.
   - **What goes wrong** ("Ranked by winning chances lost"): each cause has its stat line, a drill of at most eight words ("Show full drills" opens the full sentences), its positions as chips (`23.Qxc6 −34%`, tap for the board, opening, date, Open game and Drill this) and **Drill these 5**.
   - **Tactics your opponent got** ("No proven tactic in 96 of 145") states its overlap with the causes, visibly.
   - **Where they happen** ("Per 100 of your moves · line = your rate, 13.6") draws the bars; the clock caveat stays visible.
   - **Openings above your average** closes the page.
7. **Game Analysis** → *05-game-analysis*, reached by "Open game" (`?ply=` selects that mistake). The board sits on the left and the mistake cards on the right: `3.Nf6??` with a BLUNDER badge, the engine's best move in SAN ("Engine best: g6"), ✓ Verified, motif tags and one verified sentence. The selected mistake offers **Drill this position →**.

### C. Train it

8. **Training Plan** → *11-training-plan.*
   - "This week, 16 Oct to 22 Oct: 2 sessions of about 20 minutes." Under it: "1 of 2 done. Next: session 2."
   - "Focus: Ignoring your opponent's threat, then Missing your own chances · tactic: Forks" keeps the priority order in one line.
   - Each session card has a goal heading, interactive focus chips (tap for the plan's own rank, stat line, full drill, "since this plan" and a link to the report), its positions as chips, and its review recall ("Reviews since 16 Oct: 3 of 4 correct · 2 of 3 positions in your deck").
   - A finished session shows "✓ Done 17 Oct · 2 of 3 solved" and **Do it again**.
   - The next session has the only solid button, **Start session 2**.
   - Below the cards: the openings to review as rows, and **Make a new plan** ("Keeps this plan in your history").
9. **Drill session** → *13-drill-session.* A focused page: progress (0 / 2), Exit, a severity badge with its motif and phase, the board, "Find the best move for white", "You played Ng5. There was something better.", and Show answer. The answer is in SAN, with "Open the game →". The end says "2 cards reviewed · 1 solved first time" and goes on to **See progress**. A plan session records every answer per position, then offers **Start session 2**, or **See progress** when the week is done.
10. **Progress** → *03-progress.* Deck health tiles, 7-day recall, recall by phase from the last session, and practice history drawn as a month-by-day dot grid that shows gaps too.

### D. Play and check

11. **Play & Improve** → *06-play.* "Your opponent" as labelled rows: Steers toward (the opening, your accuracy in it, games), Most blunders, Most frequent tactic (proven only), Strength (calibrated to your average accuracy). Below it: **Start a game** and a colour choice.
12. **Practice review** → *07-practice-review.* After the game, the same measurements are applied: "2 of your moves, in order. 1 mistake · 80% accuracy". The board steps through the moves (← → 1 / 4), next to the full move list with mistakes coloured.

### E. Ask

13. **Ask Prax** → *08-ask-prax.* "Everything here is measured from your games, or cited to a source." Three starter questions, History, and an input pinned to the bottom.

### F. Phone

14. *15-phone-today* and *16-phone-training-plan.* Tiles wrap into two columns and session cards stack into one. The nav bar scrolls sideways and is cut off at 390px (see section 11).

## 11. Known drift: fix or decide

Found while capturing these screens. Each one breaks a principle above.

1. ~~**The Insights Overview uses pink for data.**~~ Fixed in M3: tactic and phase bars are neutral, an even win rate is neutral, and the rolling average is `--text-primary`. M5 did the same for Today's practiced days (now `--gain`) and "Due today".
2. ~~**Amber has two meanings.**~~ Fixed in M1: above-average bars are a brighter neutral, so amber means severity only.
3. ~~**The report popover board is blue.**~~ Fixed in M1: the position popover uses `--board-light` and `--board-dark`.
4. ~~**"Positional" appears as a missed tactic.**~~ Fixed in M3 and M6: the Overview lists proven tactics and counts the rest ("positional 154 (no tactic proven)"); Play names the most frequent proven tactic.
5. **The phone nav overflows.** The six links don't fit at 390px and "Insights" is cut off. A wrapped row, a scroll hint or a menu would fix it.
6. **Fonts aren't loaded** (section 4), so the look changes from machine to machine.
7. ~~**Em-dash copy remains.**~~ Fixed in M5 and M6 (setup checklist, Play, Progress, Drills). Hover titles on Today's week dots still use one.
8. ~~**Settings shows "Written explanations per game".**~~ Fixed in M4: both values sit under "Retired explanations", one tap away.
9. **Two tab styles.** Library uses an underline and Insights uses a pill. Pick one.
10. **Hover-only data** that a phone or keyboard can't reach. Found by the parity harness:
    - ~~Settings ⓘ help~~ (fixed in M4: `useHelp` buttons; the snapshot file names are now visible);
    - ~~the report's board popover~~ (fixed in M1: position chips open on tap);
    - ~~Today's week dots~~ (fixed in M5: the week names its dates; each day has an accessible name);
    - ~~Progress's practice-history grid~~ (fixed in M6: "Days examined" lists the dates). Its daily recall bars repeat the "Show daily history" table, which is one tap away.
11. **Pink for data, again:** `.badge-inaccuracy` is orchid, not `--orange`.
12. ~~**Mislabelled data.**~~ Resolved in M3: the server's `phase_accuracy` field holds mistake *counts* (misnamed), so the card is labelled "All flagged", its share is meaningful, and it links to the report's rate per 100 moves. The field name itself is still wrong in the API.
13. ~~**Format drift.**~~ Fixed in M1–M7: report and plan dates read `16 Oct`, Thrown-Away Wins `20 Aug`, and every engine move is SAN (`components/san.ts`). Settings and Library timestamps still use the locale format.
14. ~~**More than one solid button.**~~ Fixed: Today's Start session is solid (M5); Settings has one at a time (M4: the username first, then Save settings while there are unsaved changes, else Sync Now). The sync banner, outside `main`, still has its own.
15. ~~**A chess glyph as a UI marker.**~~ Fixed in M5: "needs attention" is `…`.

## 12. Readability harness

Principle 12 is enforced by tests, not by review alone. The plan and the decisions behind it are in [`READABILITY_PLAN.md`](READABILITY_PLAN.md).

| File | Role |
|---|---|
| `frontend/e2e/fixtures/readability.ts` | Collects what a screen renders: text runs per block with their scope (named regions, items, rows, cards), input values, links and `title=` texts. Board coordinates, chart axes and the dev pill are left out. Computes prose words, data tokens and paragraphs, and turns everything into (scope, value, count) pairs. `expandAll` opens every disclosure and every popover in `main` |
| `frontend/e2e/fixtures/parity/states.ts` | 23 states of 14 screens: few rows, old-shape and missing reports and plans, 0, 1 and 2 sessions done, long opening names, first run, empty and plan drills. A fixed clock, locale and timezone. Also the text-data vocabulary, built from the fixtures plus state words |
| `frontend/e2e/fixtures/parity/golden/*.json` | What each state showed before the readability work. Not for review |
| `frontend/e2e/fixtures/parity/SUMMARY.md` | **The review artefact.** Per state: metrics, facts per section, and what is hover-only |
| `frontend/e2e/fixtures/parity/parity-exceptions.json` | The one reviewed list of deliberate differences: removals (with the decision), format mappings, scope moves and reworded help |
| `frontend/e2e/tests/parity-capture.spec.ts` | Re-baselines the goldens. Run it only on purpose: `PARITY_CAPTURE=1 npx playwright test parity-capture --project=chromium` |
| `frontend/e2e/tests/readability.spec.ts` | On every run, in `chromium`, `narrow` and at 390px with touch: every golden fact is still present in its own item after expanding (both innermost scope names in the chain, in either order), and `data-reading` elements meet their budget. On desktop, prose is at most 60% of each state's own baseline (73% for Today, D8) for states that started at 100 words or more, and report and plan pages show no paragraph. A negative control proves the check fails when a fact is lost or moved. `READABILITY_PRINT=1` prints every state's before and after |

**Contract for new components.**
- A disclosure carries `data-disclose`. It is a `<details>`, or a button with `aria-expanded` and `aria-controls`.
- A popover trigger carries `data-popover` and `aria-controls`. It opens on click, tap and Enter, and closes on Escape.
- `expandAll` relies on both. The legacy openers it also uses (bare `details`, `aria-expanded` buttons, "Show …" buttons, hover links) are removed as each screen migrates.

**Results** (prose words by default on desktop, before → after; data tokens in brackets):

| Screen | Prose | Data tokens | What moved behind a tap |
|---|---|---|---|
| Pattern Report | 313 → 176 (−44%) | 91 → 81 | Position dates (into each chip's popover); one threshold figure (into "How this is measured"); a repeated stat line deleted |
| Training Plan | 185 → 103 (−44%, with F10/F11) | 52 → 35 | Each focus's rank and stat line (into its chip); position dates |
| Insights Overview | 208 → 125 (−40%) | 49 → 51 | Chart method only |
| Settings | 260 → 155 (−40%) | 64 → 48 | The snapshot list (its count, size, latest and test result stay visible) |
| Today | 48 → 43 (−10%) | 7 → 9 | Nothing; its words are tiles, weekdays and the headline |
| Today, first run | 108 → 63 (−42%) | 16 → 12 | The ready checks and the model list |
| Play | 85 → 51 (−40%) | 7 → 7 | Nothing |
| Ask Prax | 55 → 30 (−45%) | 0 → 0 | Nothing (a repeated intro deleted) |
