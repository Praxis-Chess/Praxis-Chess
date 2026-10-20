# Praxis Chess: Design System and Storyboard

This is a hand-off document for a designer, a senior developer or an AI agent: how the Praxis Chess UI looks, why, and how a user moves through it. Every screenshot in [`screens/`](screens/) is the real app (branch `feat/phase-12`), rendered by Playwright with the synthetic fixtures in `frontend/e2e/fixtures/`. None of it is real player data.

- **Source of truth for tokens:** [`frontend/src/index.css`](../../frontend/src/index.css). If this document and that file disagree, the file wins; fix this document.
- **Screens:** 1440×900 desktop (full page where the page scrolls), plus two at 390×844 phone width.
- **To recapture:** the capture spec is a throwaway Playwright test. Put it in `frontend/e2e/tests/`, run it with `npx playwright test zz-capture --project=chromium`, then move it out again. It uses the current model line-up; there is no debug pill to hide any more.

---

## 1. What the product is

Praxis Chess is a self-hosted chess coach for one player. It syncs that player's Chess.com games, checks every move with Stockfish, diagnoses each serious mistake (its cause and the tactic involved), and turns the mistakes into spaced-repetition drills, a pattern report and a weekly training plan. Prax is the in-app assistant: a chat that answers only from measured data or cited sources, with glass cards for its answers and for analysis progress. It has no body on screen (the particle body was retired on 20 Oct); an optional ambient background shows it at work.

The audience is one club player, roughly 800 to 1800 Elo, using it on their own laptop. It is dark only. The desktop browser comes first; phone width has to work but is secondary.

## 2. Design principles

These are applied in code review. The Phase 12 tests in `e2e/tests/reports.spec.ts` assert several of them by name.

1. **Instrument-panel minimalism.** A pure black ground, translucent glass panels, hairline borders and a small crosshair mark on each card. It should read like a measuring instrument, not a game lobby. The only decoration is the grain and, if the player turns one on, an ambient background (off by default): white light only, blurred, livelier while Prax thinks or answers.
2. **One meaning per colour.**
   - Pink (`--orchid`) is the next action and the active state only: the one solid button, the active nav item and tab, the focus ring. Links and ghost buttons are neutral (a link is primary text with a quiet underline that brightens on hover, since phones have no hover). When pink means everything, nothing stands out.
   - A loss beside data (a chip's `−34%`, an item's `−27% each`) is `--loss-soft`: red, quieter than a result.
   - Amber (`--warn`) is the *mistake* severity colour, and nothing else. "Above your own average" is shown by position against the baseline tick and a brighter neutral fill, not by a colour.
   - Green, amber and red (`--gain` / `--warn` / `--loss`) carry results and severity.
   - Everything else is neutral grey.
3. **Truthful labels.** Each label says exactly what is measured. "Costliest" means total winning chances lost, not frequency. A rate is "per 100 of your moves". A chart labelled "Rolling avg (up to 10 games)" says that the first nine points average fewer games.
4. **Compared to what.** Each figure appears next to its baseline: your overall rate, or your average per game. A bar is drawn against a baseline tick.
5. **One threshold per kind of count.** Two named thresholds, defined once in `backend/.../report/Thresholds.java`:

   | Threshold | Value | Counts | Used for |
   |---|---|---|---|
   | `MIN_GAMES` | 5 | games | Any claim about games: the opening Play steers toward and the report's openings above your average, the weaker colour, a practice tendency, a practice game's direction |
   | `MIN_MISTAKES` | 10 | serious mistakes | Any rate or share: causes, tactics, phases, move ranges, clock data, an opening above average, "since this plan" |
   | `PER_MOVES` | 100 | your moves | The unit of every rate: the report, the plan and practice games alike |

   Below a threshold a figure is drawn faded and marked "few", or the page shows how far it is ("1 of 5 games · patterns from 5"); it is never ranked or called the worst. Two pattern rules stay named in `PerGameDetector`: a tendency must recur in at least 3 games, and is stated plainly (not as an early signal) from 10. The weaker colour also needs a 1-point accuracy gap, else win rate decides.
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
13. **Hierarchy before boxes.** An item has at most three text levels: its title (with one headline number), the instruction, then its examples and one action; the rest goes into the item's Details. Cards are for things you act on (a session, a drill-leading item); what you read (bars, tables) stays open, separated by space and section titles, not more borders. Two groupings of the same data are views (tabs), not two lists.

## 3. Colour tokens

All tokens are defined on `:root` in `index.css`. The legacy aliases are kept so that older components still work.

### Ground and surfaces

| Token | Value | Use |
|---|---|---|
| `--canvas` (`--bg`) | `#000000` | Page background: pure black (the ambient background, when on, sits just above it) |
| `--canvas-deep` | `#000000` | Nav bar (separated by a hairline) |
| `--surface-1` (`--surface`) | `rgba(255,255,255,.035)` | Card fill (translucent, blurred) |
| `--surface-2` | `rgba(255,255,255,.06)` | Inputs, hovered rows, raised fills |
| `--hairline` (`--border`) | `rgba(255,255,255,.08)` | Every border and divider |
| `--hairline-strong` | `rgba(255,255,255,.16)` | Border of a control: ghost button, chip, popover. Neutral |
| `--hairline-lit` | `rgba(231,166,214,.28)` | Legacy pink-tinted border; no longer used for controls |

### Text

| Token | Value | Use |
|---|---|---|
| `--text-primary` (`--text`) | `#F2EDEA` | Headings, values, body that matters |
| `--text-secondary` (`--text-muted`) | `#A29B96` | Supporting text, stat lines |
| `--text-tertiary` | `#6D6662` | Micro-labels, rank markers, faded rows |

### Accent

| Token | Value | Use |
|---|---|---|
| `--orchid` (`--accent`) | `#E7A6D6` | The solid button, active nav and tab, focus ring. **Next action and active state only**; links are neutral |
| `--mauve-deep` | `#B0679F` | Pressed or hovered solid button, chart gradient end |
| `--accent-wash` (`--accent-dim`) | `rgba(231,166,214,.12)` | Active tab pill, selected row |

### Semantic

| Token | Alias | Value | Meaning |
|---|---|---|---|
| `--gain` | `--green` | `#B9D96C` | Win, verified, done, good |
| `--warn` | `--yellow` | `#E5B04B` | Mistake severity only |
| `--loss` | `--red` | `#E2664A` | Blunder, loss, failure, error text |
| `--loss-soft` | | `#C98172` | A loss figure beside data: chips, "−27% each" |
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

Both families are bundled with the app (`@fontsource/inter-tight` 400–700 and `@fontsource/jetbrains-mono` 400–600, latin subset, imported in `main.tsx`, `font-display: swap`). No CDN request, so the app looks the same on every machine and works offline.

### Scale (tokens on `:root`, U1)

| Use | Size | Weight | Notes |
|---|---|---|---|
| Page title | `--fs-h1` 1.75rem | 700 | Letter-spacing −0.02em. Every page title, `h1` or `h2` |
| Takeaway sentence | 1.1rem | 400 with `<strong>` | The first line of a report or plan |
| Today's focus headline | ~1.6rem | 700 | Only on Today |
| Section title | `--fs-section` 1.25rem | 700 | `ReportParts.sectionTitle` (hairline rule above, 18px padding) and the Settings cards |
| Item title | 0.9rem | 600 | |
| Body | `--fs-body` 14px (0.875rem) | 400 | Line-height 1.5. Running text in a page is capped at 75ch |
| Muted / stat line | `--fs-small` 0.78–0.8rem | 400 | `--text-secondary`, line-height 1.55 |
| `.micro-label` | `--fs-micro` 10px | 500 | Mono, uppercase, letter-spacing .14em, `--text-tertiary` |
| `.badge` | 0.65rem | 600 | Mono, uppercase |
| Stat tile value | `--fs-display` ~1.6rem | 700 | Coloured by meaning |

Numbers always use `font-variant-numeric: tabular-nums`, and a number inside prose is wrapped in `.mono`.

## 5. Spacing, shape and depth

- **Spacing.** Flex or grid with `gap`; page sections are 26px apart, items in a section 14–18px apart, lines inside an item 4–6px apart. Cards have 20px of padding (16px in dense grids).
- **Radii.** Small and precise: cards and inputs 4px, buttons 3px, badges 2px, chips 10px (pill). Nothing is fully rounded except the week dots.
- **Depth.** The only shadows are an inset highlight on cards, the glass slabs (`.glass`: the sync banner, Prax's cards) and the opaque board popover. Depth otherwise comes from translucency and `backdrop-filter` over the ambient background.
- **Grain.** A fixed noise overlay at opacity 0.03 sits over the canvas.
- **Interaction (U8).** Every control answers hover and press, in neutral washes: `--hover-wash` (white 4.5%) on hover, `--press-wash` (white 8.5%) on press; pink stays the next action and the active state, never a hover. A pressed button moves 1px down; a solid button darkens to `--mauve-deep` and dims 10%. A row that is one link (`.row-link`) lifts on hover and dips on press; `data-selected` keeps it filled. Table rows lift in the same wash (they were pink). A card that is itself a link (`a.card`, `.card.interactive`) lights its edge (white 24%) and never moves. Resting tabs brighten on hover (`.tab`). Every link, button and tab has a 1px orchid focus ring.
- **Motion.** One scale: `--dur-fast` 120ms (rows, press), `--dur` 150ms (buttons, links, tabs), `--dur-slow` 180ms (cards, toasts), all on `--ease-out` `cubic-bezier(0.2, 0, 0, 1)`. A progress fill, which shows change over time, takes `--dur-fill` 400ms. Nothing bounces. **Reduced motion:** every transition is 0ms, a press does not move, script scrolls jump (`lib/motion.ts`), the loading mark is its still frame, and the animations that say "working" keep their own reduced form (a fade, a pulse).
- **Layout.** A sticky 56px nav in three zones (`.topnav`): the Praxis text logo on the left (a home link), the six sections centred in uppercase mono (active one pink), and on the right the sync chips and Settings behind a hairline. From 1440px down to small laptops (1280, 1366) the end zone shortens: chips show their count, Settings its gear. `main` has a max-width of 1280px and padding of 32px on top, 24px elsewhere. **No visible page title where the nav names the page** (20 Oct): Today, Progress, Library, Insights, Ask Prax and Play keep their `<h1>` for screen readers and tests (`.sr-only`) and start with their content; Play's next-game line leads at `--fs-section`. Pages the nav does not name keep their title: Game Analysis (with its metadata and Back), drill sessions, practice review, and Settings (only a gear in the nav). **Every page is centred on one of three widths** (`components/Page.tsx`, declared once per route in `App.tsx`): **reading** 760px (Ask Prax), **standard** 1200px (most pages), **wide** = all of `main` (Game Analysis, practice review, the live game). `Page` has a `rail` slot: from 1200px of viewport an 8 + 4 column grid, stacked below that (adopted from U3). Nothing reserves a gutter for Prax. Phones are out of scope (20 Oct): the narrow-window rules stay but are not developed further.

## 6. Components

| Component | Class or file | Spec |
|---|---|---|
| Button (ghost, the default) | `button` | Primary text, 0.82rem weight 600, `--hairline-strong` border, radius 3, padding 7×15 |
| Link | `a`, `.link` (a `<button class="link">` toggle) | Primary text, weight 500, underline at 32% of the text colour, full on hover. Never pink |
| Button (primary) | `button.solid` | Filled orchid with dark text. **One per view: the next action.** |
| Button (secondary) | `button.secondary` | Neutral text, hairline border. Used for Rebuild, Do it again, Exit |
| Card | `.card` | `--surface-1`, blur, hairline, radius 4, padding 20, a crosshair `+` drawn by `::after` at the top right |
| Glass slab | `.glass` (sync banner); Prax's cards inline | A pane of frosted glass: a faint white tint with a diagonal sheen, `backdrop-filter: blur(28px) saturate(160%)`, a 10%-white hairline, a lit top edge, a soft drop shadow, radius 10. An active state (analysis running) tints it orchid, never fills it. Prax's cards add a 55% black base so text reads over the page on a phone |
| Page frame | `components/Page.tsx` | `<Page width="reading" \| "standard" \| "wide" rail?>`: centres the page on `--page-reading` 760px, `--page-standard` 1200px or all of `main`; marked `data-page`, which the density harness measures against. The optional `rail` is an 8 + 4 column grid from 1200px |
| Sync chip | `SyncChips` in `SyncStatusBanner.tsx`, in the nav | Idle library news as a ghost pill in mono: count, then the verb ("16 new · Sync"); the verb hides below 1440px and stays in the accessible name. Never solid. The banner keeps runs (progress) and failures (`role="alert"`) |
| Today rail | `components/today/TodayRail.tsx` in `Page`'s rail | Small `.card.no-mark` panels, each a micro-label, one or two lines and a link to its page: Rating (figure, change, a decorative line), Last game, Next session, Top habit. A panel with nothing to say is absent or one link, never an empty frame; never a solid button |
| Plain card | `.card.plain` / `.card.no-mark` | No crosshair; used for repeated items such as session cards |
| Stat tile | Today, Progress, Insights | A small card with a big number, a label under it and one muted line of context. A tile is a figure, never a link dressed as one (N14): an action goes where its subject is |
| Takeaway line | Insights Overview (`components/insights/takeaway.ts`) | One sentence at `--fs-section`, weight 600, above the figures: what the trend chart says (Elo since its furthest point, accuracy over ten games either side). Absent below 20 games. When results fell, a neutral link to the cause ("What goes wrong →") |
| Win-rate bars | Insights (`WinRateBars`) | Bars against **your own** win rate, never 50%: a dashed line at your rate, each bar's figure on top, green 10+ points above, red 10+ below, `--grey-1` between; fewer than `MIN_GAMES` faded and never coloured. A line under the chart names the furthest bucket. The chart takes the card's height, so a row of them has no voids |
| Badge | `.badge` plus `.badge-blunder/-mistake/-inaccuracy/-win/-loss/-draw/-analyzed/-pending/-analyzing/-failed` | Mono uppercase, outlined in the semantic colour with a 10% tint |
| Micro-label | `.micro-label` | Eyebrow or column label; tertiary grey |
| Chip | Training Plan focus (`FocusChip`) | 0.7rem pill with a `--hairline-lit` border. A label chip has secondary text. An **interactive** chip has primary text and a ▾ caret: it is a `PopoverButton` that opens what the plan recorded for that focus (rank "Priority 1 of 2", stat line, short and full drill, "See in Pattern Report →") |
| Tabs | `components/Tabs.tsx` (`.tabs`, `.tab`); Library, Insights | **One look:** an underline under the active tab on a hairline, the active one orchid and 600, the rest secondary text that brightens on hover; never a pill (pills are filters and chips). Two kinds behind it: **link tabs** (each its own address, Insights) are links in a `role="group"` with `aria-current="page"`; **in-page tabs** (Library) are an ARIA `tablist` with `aria-selected`, a matching `tabpanel`, and Left / Right / Home / End |
| Filter pills | Insights speed filter | Pill outline; the active one is lit |
| Table | `table` | Hairline row rules, no zebra stripes, mono micro-label headers, numbers right-aligned |
| Comparison bars | `ReportParts.Bars` | Equal tracks and a full-height baseline tick at the same x on every row. One row is bright (`--text-primary` at 62%, label and value in primary text): the finding the section leads with (`highlight`), else every row above the baseline; the rest `--grey-3`. Faded, with "· few", below the threshold. The value sits right after its bar, on one grid shared by every row. Never amber |
| Position chip | `ReportParts.PositionChip`, listed by `ReportParts.Positions` | A small outlined button (`--hairline-strong`): the move in primary mono and its loss in `--loss-soft` (`23.Qxc6 −34%`). It opens a popover with the board (in the board tokens), "White to move", opening · date · loss, **Open game** (`/games/:id?ply=n`) and **Drill this**. `Positions` shows the first 3, then "Show all n" |
| Popover | `ReportParts.PopoverButton` | A `<button data-popover aria-expanded aria-controls>`. It opens a non-modal `role="dialog"` on click, tap or Enter. The panel is portalled to `<body>`, so a card's `backdrop-filter` stacking context can't paint over it. It is opaque, holds real links, and sits beside the button, flipping left at the right edge; below 560px it docks to the bottom of the screen. Escape closes it and returns focus; a tap outside closes it |
| Disclosure | `ReportParts.Disclose`, class `.disclose` | A native `<details data-disclose>` whose summary is a small secondary-grey label, "How this is measured" by default. One per section, for method only, never for a caveat |
| Section label | `ReportParts.sectionLabel` | One muted line under a section title: what is measured ("Ranked by winning chances lost") or the caveat that changes how it reads ("No proven tactic in 96 of 145"). Carries `data-reading="intro"` |
| Chessboard | `components/ThemedBoard.tsx` around `react-chessboard` 4.6.1 | **Every board** goes through `ThemedBoard`: bone and graphite squares (`--board-light`, `--board-dark`), a hairline edge, radius 2; marked `data-board`. Callers pass position, orientation, arrows, highlights and size, never colours |
| Static board | `components/StaticBoard.tsx` | **Boards in lists** (U4): a FEN drawn as an 8×8 grid in the board tokens, pieces as text glyphs with an outline, no library, no interaction; oriented for the side to move; one image to assistive tech; `data-board="static"`. Used by Library → Drills (a gallery), Pattern Report items, Training Plan sessions and Thrown-Away Wins (Today's focus uses the full `MiniBoard`) |
| Eval graph | `components/analysis/EvalGraph.tsx` | Your winning chance after each half-move (0–100, from the stored curve), the 50% line, flagged moves as dots in `--loss` / `--warn` / secondary, the selected move as a dashed rule; decorative to assistive tech (the move list carries the same moves) |
| Move list | `components/analysis/AnalysisMoveList.tsx` | Moves in white/black pairs, mono; flagged moves in their severity colour; each a button with `aria-pressed` for the move on the board |
| Teaching empty state | `components/TeachingEmpty.tsx` | A full-width card for a screen with nothing yet: title, what it will show (≤ 15 words), **Built from** (your figures: games synced, analysed, and why it is empty, ✓ or –), the one action, and up to four static boards from your games. With none, one 248px sample board captioned "Sample". `compact` for a screen whose controls come first: one line of figures, a 160px board |
| Sample banner | `sample/SampleLink.tsx` (`SampleBanner`, `SampleLink`) | While sample mode is on, a `.glass` slab under the nav on every page, `role="status"`: "Sample games", what they are, **Exit sample** (secondary). `SampleLink` ("Explore with sample games →", a `.link` button) appears only when the build has a library and the mode is off |
| What Prax knows | `AskPrax.tsx` (`EmptyState`) | Before the first question: three `.card.no-mark` panels (micro-label, mono figure, link), then "From your report" question chips (ghost buttons), then "Recent" questions as links with their date |
| Setup checklist | `SetupChecklist.tsx` | ✓ ready, ✕ missing, … needs attention (never `!`, a chess annotation), – off; "Name · detail" rows with a fix link. Inline under Sync Now in Settings. On Today only while something blocks: the blocking rows first, "n ready ▾" and "Models (n) ▾" one tap away |
| Help | `ReportParts.useHelp` | Replaces every hover-only `title=` tooltip: an orchid ⓘ `<button data-disclose aria-expanded>` beside a label or heading that shows one line of help under it on click, tap or Enter |
| Engine table | `Settings.tsx` (`EngineTable`) | One row per setting: the label and range once, with ⓘ help, then a value per engine in a `role="group"` named after the engine. Below 760px it stacks, and each value shows its engine's name (`.only-stacked`) |
| Labelled rows | Play (`ThisGame`, `WatchForPanel`) | A `<dl>` of micro-label and facts: no sentence around the numbers. **This game** shows only what the opponent does (opening, strength). **Watch for** is a closed disclosure: the Pattern Report's facts, from the same stored report, labelled as not targeted. An older server's "Label: facts" lines render the same way |
| Practice table | Play (`PracticeSoFar`) | Result · Date · (Aimed at) · Accuracy · Flagged · Review. The "Aimed at" column is dropped when every game was aimed at one opening, which the note line says once. Below `MIN_GAMES` the header shows progress; patterns are rows (title · n of N games · Early signal; a ≤ 8-word "do now"), with the finding and why behind one "Details" toggle and a real "Drill these positions" link. Three levels: the title in primary 600, the "do now" in secondary, the row's actions in small orchid (0.75rem, 500). The games behind the count sit on one shared grid under a hairline: date (mono, tertiary) · what happened (secondary) · Review → |
| Since this plan | `TrainingPlan.tsx` (`SinceLine`) | In a focus chip's popover: "Since 16 Oct · 1.3 per 100 moves, from 3.0 · 6 games". Silent until 10 diagnosed serious mistakes have been played since the plan ("too little play to compare: 4 of 10") |
| Recall line | `TrainingPlan.tsx` (`RecallLine`) | On a session card: "Reviews since 16 Oct: 3 of 4 correct · 2 of 3 positions in your deck"; "Not in your review deck" when none are cards, never "0 recalled" |
| Charts | Recharts | Dotted grid, mono axis text, `isAnimationActive={false}`, time x-axis with real dates, dual axis for accuracy and Elo. The rolling average is drawn in `--text-primary`, never the accent; win-rate bars are green, neutral (`--grey-1`) or red, against your own rate |
| Prax | `src/prax/` | No body: the particle body is retired. Prax is the runtime (state machine, voice) and its glass cards (analysis progress, the question card), on Today, Progress and Library (`PRAX_CARD_ROUTES`). A card sits in the margin beside the centred page, bottom right, when the margin holds it; otherwise a progress card is not shown (the banner carries the run) and a question card the player opened overlays the page. Its state (dormant, aware, thinking, insight, speaking) drives the ambient background. No on-screen debug panel: tests read state through the dev-only `window.__prax` handle |
| Ambient background | `src/background/` (`AmbientBackground`) | One canvas behind every page (`z-index: -1`, `aria-hidden`), white on black, half resolution, CSS blur 7px, opacity 0.7, darker under the nav. **Off by default** (plain black, no canvas). Styles: Contours, Dot matrix, Ridge lines, Flow trails, Starfield, Chess horizon. None is final: the player is designing their own (candidates: Paper Shaders, ShaderGradient, Unicorn Studio), which would replace these as styles here. Energy (0–1) from Prax's state and voice sets speed and volatility; Motion: Reacts to Prax, Calm or Still (reduced motion is Still). Mean brightness stays under 0.12 (tested). Settings → Display, kept in this browser. **Hidden for now (20 Oct):** `BACKGROUND_SETTING = false` removes the Display card and keeps the background Off for everyone, even a browser that saved a style; the styles, renderer and their tests stay and return with the flag |

## 7. States

- **Loading:** `LoadingSpinner` with a sentence ("Loading the report…").
- **Empty:** one sentence that says why, plus the way out on its own line ("No drills yet: each analysed blunder becomes one." then "Your games →"), or the single action ("Make my plan"). A whole screen with nothing yet uses the **Teaching empty state**: what it will show, what it is built from, the action, and its positions (U7).
- **Sample:** the sample library behind every screen, said so by the Sample banner; reads replay, writes are refused with the reason.
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
- Reduced motion turns off Prax animation, every transition, press travel and smooth scrolling, and stills the loading mark (U8). The e2e suite runs with reduced motion on; `interaction.spec.ts` asks for motion where it tests it.
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
3. **Analysis runs.** New games or games waiting show as ghost chips in the nav ("16 new · Sync", "3 waiting · Analyse"). While a sync or an analysis runs, `SyncStatusBanner` under the nav shows its progress, as glass; a failed action is an alert there with Try again. Games appear in the **Library** → *04-library*, grouped with "Needs analysis" first. Each row shows the result in colour and its accuracy.
4. **Back to Today** → *01-today.* A cockpit (U3): the main column opens on **Today's focus** (one headline, the session size and duration, the solid **Start session**, Show evidence) with one of your own positions on a board beside it (the costliest of the Pattern Report's top habit). Under it, what is due today (reviews due, plus up to 10 new cards a day), learning, review and new cards as neutral counts, and the week ("17 Aug – 23 Aug", dots in the results green). The **rail** beside it: your rating and its line, your last game, the next Training Plan session, and your top habit with its direction since the plan. The deck fills itself after each analysis: blunders first, then the plan's positions, then the most recent (`DeckRule`).

### B. Understand the weakness

5. **Insights: Overview** → *09-insights.* A speed filter (an unanalysed speed reads "· results only"), four tiles, the Accuracy and Elo trend ("First 9 points: fewer games"; method behind a disclosure), opponent strength, mistakes by phase (all flagged mistakes, with "per 100 moves →" to the report), missed tactics (proven only; "The rest: positional 154 (no tactic proven)"), time of day and day of week, resilience ("13.3 points lower after a loss · rest first"), thrown-away wins and openings. Each card has a title and at most a few words.
6. **Pattern Report** → *10-pattern-report.*
   - It opens with a takeaway, "Your costliest habit: ignoring your opponent's threat.", the label "Most often: middlegame · 17.6 vs 13.6 per 100 moves", and the one solid action: **Train this week →**, or **Make my plan** when there is no plan.
   - **What goes wrong** ("Ranked by winning chances lost") has two tabs over the same mistakes: **By cause** (first) and **By tactic your opponent got** ("A tactic proven in 49 of 145"). The cause view says "No cause found for 40 of 145 (28%)" plainly, above the list.
   - Each item is a light card in three levels: the title with one headline number (`−27% each`, soft red); the instruction, in primary text; three position chips (`23.Qxc6 −34%`, tap for the board, opening, date, Open game and Drill this) and **Drill 5 →**. **Details** holds the stat line, the full drill sentence, the overlap with the causes and the other positions.
   - "The rest" is a list (`unclear 17`), not a sentence.
   - **Where they happen** leads with the finding ("Most in the middlegame, moves 11-20"); only those bars are bright; "Per 100 of your moves · line = your rate, 13.6" labels them, and the clock caveat stays visible.
   - **Openings above your average** closes the page. An opening whose gap is under twice the spread its game count allows is marked **Low sample**.
7. **Game Analysis** → *05-game-analysis*, reached by "Open game" (`?ply=` selects that mistake; with none, it opens on the costliest mistake). Beside the board: your winning chance move by move (a curve with your flagged moves as dots) and every move of the game; any move shows its position. The board sits on the left and the mistake cards on the right: `3.Nf6??` with a BLUNDER badge, the engine's best move in SAN ("Engine best: g6"), ✓ Verified, motif tags and one verified sentence. The selected mistake offers **Drill this position →**.

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

11. **Play & Improve** → *06-play.* One line first: "Next game: Center Game as Black · engine skill 9 of 20". Then the only solid button, **Start a game**, with the colour ("Weaker colour: Black", and why: "accuracy 64.9% vs 70.1% as White · 43 and 58 games") and the strength (recommended preselected, or 1–14). **This game:** the opening, from the stored Pattern Report ("6.0 vs your 4.7 serious mistakes per game · 5 games", Why one tap away), and the strength, saying when it is the cap. **Watch for** (closed): the report's phase rate, top cause and proven tactic, not targeted by the opponent. **Practice so far:** the games table and the patterns.
12. **Practice review** → *07-practice-review.* After the game, the same measurements are applied: "2 of your moves, in order. 1 mistake · 80% accuracy". The board steps through the moves (← → 1 / 4), next to the full move list with mistakes coloured; the engine's move is in SAN. The post-game report offers **Drill this game's mistakes (n)**, a drill of exactly those positions.

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
5. ~~**The phone nav overflows.**~~ Fixed: the sections have their own row on a phone and fit at 390px.
6. ~~**Fonts aren't loaded.**~~ Fixed in U1: Inter Tight and JetBrains Mono are bundled.
7. ~~**Em-dash copy remains.**~~ Fixed in M5 and M6 (setup checklist, Play, Progress, Drills). Hover titles on Today's week dots still use one. Since U8 the dots of days still to come are dashed rings (N17), no longer near-invisible.
8. ~~**Settings shows "Written explanations per game".**~~ Fixed in M4: both values sit under "Retired explanations", one tap away.
9. ~~**Two tab styles.**~~ Fixed (20 Oct): one `Tabs` component, the underline, on Library and Insights.
10. **Hover-only data** that a phone or keyboard can't reach. Found by the parity harness:
    - ~~Settings ⓘ help~~ (fixed in M4: `useHelp` buttons; the snapshot file names are now visible);
    - ~~the report's board popover~~ (fixed in M1: position chips open on tap);
    - ~~Today's week dots~~ (fixed in M5: the week names its dates; each day has an accessible name);
    - ~~Progress's practice-history grid~~ (fixed in M6: "Days examined" lists the dates). Its daily recall bars repeat the "Show daily history" table, which is one tap away.
11. **Pink for data, again:** `.badge-inaccuracy` is orchid, not `--orange`.
12. ~~**Mislabelled data.**~~ Resolved in M3: the server's `phase_accuracy` field holds mistake *counts* (misnamed), so the card is labelled "All flagged", its share is meaningful, and it links to the report's rate per 100 moves. The field name itself is still wrong in the API.
13. ~~**Format drift.**~~ Fixed in M1–M7: report and plan dates read `16 Oct`, Thrown-Away Wins `20 Aug`, and every engine move is SAN (`components/san.ts`). Settings and Library timestamps still use the locale format.
14. ~~**More than one solid button.**~~ Fixed: Today's Start session is solid (M5); Settings has one at a time (M4: the username first, then Save settings while there are unsaved changes, else Sync Now). Since U1 the sync news is a ghost chip in the nav, so no solid button sits outside `main`; `density.spec.ts` enforces one per view on every primary screen.
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

**Play redesign** (`docs/design/PLAY_PLAN.md`, prose words by default on desktop, before → after; data tokens in brackets):

| State | Prose | Data tokens |
|---|---|---|
| Opponent, no practice games | 85 → 42 (−51%) | 7 → 12 |
| New player | 51 → 20 (−61%) | 0 → 2 |
| 1 practice game | 141 → 66 (−53%) | 11 → 18 |
| 6 practice games | 238 → 117 (−51%) | 22 → 28 |

Spelling: player-facing copy is British ("analysed", "colour", "practise"); identifiers and API fields are unchanged.
