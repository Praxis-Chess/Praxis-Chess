# Polish and density: audit and plan

**Scope:** Phase 0 (audit) and Phase 1 (plan). Milestones U0 and U1 are written in full; U2 to U8 are one row each and get their own detail when we reach them. **No product code changes until you approve.**

**Status (20 Oct):** the "verify" items were checked against the code (results in the findings below). D1, D2 and D4 to D7 are approved as recommended. D3 is answered, with three details still open (§4a). **U0–U5 are done (§5a, §6a–§6e), N23 fixed and repaired; U5 stopped for review.** U5's backend (the stored eval curve) needs a Maven run and a restart. **Scope (20 Oct): no phone layout;** small laptops (1280×720, 1366×768) are the floor.

**Sources:** 20 screenshots of the running app at about 1920 px wide (Today, Progress, Library: Games and Drills, Game Analysis, Play: pre-game and in-game, Ask Prax, Insights Overview, Settings), plus `DESIGN_SYSTEM.md`, `PLAY_PLAN.md` and `BACKGROUND_PLAN.md`. Pattern Report and Training Plan weren't in the set. Where a finding names a cause I haven't seen in code, it says **verify**.

---

## 0. The headline

The app feels empty for three reasons, in this order:

1. **The first two pages report nothing.** Today and Progress open on nine zero tiles, while the Library holds 124 analysed games and 50 drills. The review deck has 0 cards. This is a data and product gap first, a layout problem second.
2. **There is no layout system.** Content is left-aligned inside a wide `main`, with six different right edges across pages. On most pages 40–65% of the width is unused, all of it on the right.
3. **The richest data sits on secondary pages and below the fold.** Insights is dense and Game Analysis has a board. The home page, the drill list and the live game show neither boards nor trends.

Visual polish (fonts, type steps, surfaces) matters, but it comes after these three.

---

## 1. Baseline (measured by the harness, U0)

Measured by `e2e/tests/density.spec.ts` (`DENSITY_RECORD=1`); the full table, every state at both widths, is `frontend/e2e/fixtures/parity/DENSITY.md`. Synthetic fixture data. "Coverage" is the content's ink width (text runs and painted boxes) over `main`'s content box, which is 1232 px at both widths: `main` is capped at 1280 px and centred.

| Screen · state | Coverage | Right edge at 1440 / 1920 | Boards (above fold) | Charts (above fold at 1440 / 1920) | Solid buttons above the fold | Floors missed at 1440 |
|---|---:|---:|---:|---:|---|---|
| Today · default | 44% | 644 / 884 | 0 | 0 | 1 | media, coverage |
| Today · empty deck (N1) | 44% | 644 / 884 | 0 | 0 | **2** (Sync Now, Start session) | media, solid, coverage |
| Progress · default | 58% | 824 / 1064 | 0 | 1 / 1 | 0 | coverage |
| Library · games | 57% | 810 / 1050 | 0 | 0 | 0 | media, coverage |
| Library · drills | 58% | 824 / 1064 | 0 | 0 | 0 | media, coverage |
| Game Analysis | 100% | 1336 / 1576 | 1 (1) | 0 | 0 | none |
| Play · pre-game (six games) | 73% | 1004 / 1244 | 0 | 0 | 1 | media, coverage |
| Play · new player | 39% | 584 / 824 | 0 | 0 | 1 | media, coverage |
| Play · in game | 73% | 1004 / 1244 | 1 (1) | 0 | 0 | coverage |
| Insights · Overview | 70% | 964 / 1204 | 0 | 4 / 6 | 0 | coverage |
| Pattern Report | 70% | 964 / 1204 | 0 | 0 / 2 | 1 | media (at 1440 only), coverage |
| Training Plan · one done | 70% | 964 / 1204 | 0 | 0 | 1 | media, coverage |
| Settings (not primary) | 75% | 1024 / 1264 | 0 | 0 | 1 (Sync Now, in the banner) | n/a |

**What this corrects in the screenshot estimates.** The screenshots came from a window about **1600 CSS px** wide (1920 physical pixels at 120% display scaling): content starts at 184 there, which is `main`'s left edge at 1600. At a true 1920 `main` is already centred (content starts at 344). So N4's problem is not that `main` hugs the left; it is that **every page is left-aligned inside a centred `main`**, with seven right edges (644, 810, 824, 964, 1004, 1024, 1336 at 1440). U1's widths fix exactly that.

**The floors today (warn mode, 1440×900):** 22 of 24 primary states miss at least one floor. Only the two Game Analysis states meet all three. Only Game Analysis, live Play, Progress (its dot grid) and Insights Overview have a board or chart above the fold; Pattern Report's bars start just below it at 900 px.

---|---:|---:|---:|---:|---:|
| Today | 865 | 36% | 0 | 0 | 2 (banner, Start session) |
| Progress | 1084 | 47% | 0 | 1 (dot grid) | 1 (banner) |
| Library: Games | 1084 | 47% | 0 | 0 | 1 |
| Library: Drills | 1084 | 47% | 0 | 0 | 1 |
| Game Analysis | 1723 | 80% | 1 | 0 | 1 |
| Play: pre-game | 1309 | 59% | 0 | 0 | 2 (banner, Start a game) |
| Play: in-game | 1309 | 59% | 1 | 0 | 1 |
| Ask Prax | 1723 | 80% (input only) | 0 | 0 | 1 |
| Insights: Overview | 1259 | 56% | 0 | 4 | 1 |
| Settings | 1334 | 60% | 0 | 0 | 2 (banner, Sync Now) |

"Width used" is the content's right edge minus its left edge (184 px), as a share of the viewport. U0 replaces these estimates with harness numbers at 1440 and 1920.

---

## 2. Findings

### Last review's hypotheses, checked against the screens

| # | Hypothesis | Verdict |
|---|---|---|
| H1 | Surfaces vanish on black | **Partly wrong.** Cards read as dark slabs with a hairline. The real problems are that every section is the same card (Progress, Settings, Insights), and that paired grid cards leave large voids inside them (Resilience, vs Opponent Strength). |
| H2 | The harness only measures subtraction | **Confirmed.** Today passes every reading budget with four zeros and one sentence. |
| H3 | The board is hidden | **Confirmed, and wider than expected.** The drill list (50 positions), Thrown-Away Wins and Patterns show no positions. Game Analysis opens on the starting position. |
| H4 | Dead width | **Confirmed** (§1). Content starts at 184 px and is left-aligned in a `main` of about 1540 px. **Checked:** `--prax-gutter` reserves space only while a Prax card is open, so it isn't the cause. It still matters for U1: once pages are centred, a gutter that appears and disappears would shift the whole page sideways every time a card opens. |
| H5 | Flat type, fonts not loaded | **Fonts confirmed** (system Segoe and Consolas). Type is less flat than I guessed: page titles and stat values read well. Section titles sit too close to body size. **Downgraded.** |
| H6 | "Instrument panel" applied as minimalism | **Confirmed in spirit.** The two most instrument-like elements, the Practice history dot grid and the Coverage table, are the best-looking things in the app. Use them as the reference. |
| H7 | No time dimension | **Confirmed**, except Insights' trend chart. |
| H8 | First run is the emptiest state | Not captured. Today with an empty deck already behaves like first run for a player with 124 analysed games (N1). |

### New findings

| # | Finding | Where | Principle |
|---|---|---|---|
| N1 | **Home reports nothing.** Today: Due 0 · Learning 0 · Review 0 · New 0. Progress: five zero tiles. Library: "50 drills from your analysed games". **Checked:** review cards are created only when you press Start session (`SessionController` → `CardGeneratorService`). Nothing creates them after analysis. New cards also aren't due until 7 days after they're created, so "Due today" would stay at 0 for a week even after the fix (D8). | Today, Progress, Library: Drills, `SessionController`, `CardGeneratorService` | 10 |
| N2 | **No data is drawn as failure.** "7-day recall 0%" is in loss red over three full-width red bars. Phase recall shows "0% · 0/0" in red, three times. | Progress | §7 "Too few", 3 |
| N3 | **The sync banner dominates every page.** It's the widest element (1840 px against content of 680–1150 px). It carries a solid pink button, so Today, Play and Settings each have two solid buttons, and Settings shows "Sync Now" twice. | `SyncStatusBanner` | 10, drift 14 |
| N4 | **Six right edges.** 865 · 1084 · 1259 · 1309 · 1334 · 1723. The page width jumps on every nav click, and every page is weighted left. | All pages | 13 |
| N5 | **Two board themes.** Play in-game uses react-chessboard's default brown. Game Analysis uses bone and graphite. **Checked:** live Play and Prax's chess-position card skip the board tokens; the other four boards apply them. | `/play` (in game), Prax position card | 2 |
| N6 | **The live game's side panel holds two lines.** "This game is pressing" (**checked:** real copy, `PlayImprove.tsx:613`) and "Queen's Pawn Game · engine skill 10/20". There's no move list, no material count, and no reminder of the habit being trained. | `/play` (in game) | 9 |
| N7 | **Game Analysis is missing the two standard elements: an eval graph and a move list.** The board shows the start position until you click a mistake. Each positional card prints the same 25-word sentence twice (the diagnosis, then "AI commentary · checked") and POSITIONAL twice. Nine cards repeat about 450 words. | `/games/:id` | 8, 12 |
| N8 | **The trend chart plots missing accuracy as 0.** The per-game line drops to 0 several times (May, 20 Jul, 15 Aug); the chart plots whatever it receives, so the backend most likely sends 0 for unanalysed games (unconfirmed; U2 confirms it, and the chart treats a missing value as a gap rather than a point). The page's biggest story goes unstated: Elo fell from about 880 to about 690 since June while rolling accuracy rose. Overview has no takeaway line. | Insights: Overview | 3, 8 |
| N9 | **Win % is coloured against 50%, not against your own rate.** Your overall rate is about 42%, so nearly every bar in Time of Day, Day of Week and Resilience is red, and the page reads as alarm. | Insights: Overview | 4 |
| N10 | **A one-game figure is drawn at full strength.** "Weaker · 100% · 1g" is a full, bright green bar. Below `MIN_GAMES`, it should be faded and marked "few". | vs Opponent Strength | 5 |
| N11 | **Library rows are tall and thin.** Every unanalysed bullet game reads "Unknown opening" (Chess.com PGNs carry an ECO header: **verify**). Dates use the locale format (`1/10/2026`). Each row is about 73 px tall for five facts, so seven games fit on a screen. | Library: Games | 7 |
| N12 | **Drill rows are 50 bordered cards** with crosshairs, where Games rows have no cards. Most motifs read "positional" or "other". The INACCURACY badge is grey here and orchid in Game Analysis. | Library: Drills | 2, 13 |
| N13 | **The Openings table splits one name across ECO codes.** Center Game appears as C21 and C22, Queen's Pawn Game as D00 and A45, King's Pawn Opening as C20 and B00. | Insights: Openings | 7 |
| N14 | **A link is dressed as a stat tile.** The fourth tile is "♟ Drill →". | Insights: Overview | 13 |
| N15 | **Ask Prax is about 70% empty.** Three starter chips, and nothing about what Prax knows (124 games, the latest report) or your recent questions. | `/ask` | 10 |
| N16 | **Colour drift.** Amber is used on the "Learning" tile and on the Backups warning border, but amber means mistake severity only. Green is used on the "Review" tile. | Today, Progress, Settings | 2 |
| N17 | **The week strip looks broken.** Future days' dots are close to invisible. | Today | 10 |
| N18 | **Two timestamp formats on one card.** ISO (`2026-05-03`) and locale (`1/10/2026, 1:57:36 am`). | Settings | 7, drift 13 |
| N19 | **Pattern Report: a fourth width inside the page.** The report column ends at 964 (860 px wide), but its Openings table stops at 640 px wide, and the "How this is measured" for openings sits under the table. The positions are chips only: every item's evidence is a tap away from a board, never on the page. The bars, the page's only chart, start below the fold at 1440×900. | Pattern Report | 13, 9 |
| N20 | **Training Plan ends at about 660 px** of a 1080 px screen: two session cards, one line of openings, a footer. The cards show three chips each and no board, although each session *is* a set of positions. The FOCUS line is a run-on string of three foci. | Training Plan | 10, 9 |
| N21 | **One fact, two formats, two verdicts.** Pattern Report lists *Center Game · 5 · 6.0 · ×1.28* and marks it **Low sample**; Training Plan's "Openings to review" lists *Center Game · 6.0 vs your 4.7 per game · 5 games* with no marker, and asks you to review it. The plan should say the same thing as the report, in the same format. | Training Plan, Pattern Report | 7, 5 |
| N22 | **"Not in your review deck" on every session card** in the real app (seen on the Training Plan before this audit) is the same root cause as N1: the deck is empty because cards are only made when a session starts. U2's deck rule fixes both. | Training Plan | 10 |

---

## 3. Milestones (the shape)

| # | Milestone | Why here |
|---|---|---|
| **U0** | Baseline, density metrics, new fixture states | So every later change is measured |
| **U1** | **The frame:** page widths, a centred grid with a rail slot, sync status moved into the nav, one board theme, bundled fonts, type steps | Global and cheap: every page stops looking left-weighted and accidental |
| **U2** | **Truthful data** (backend changes: needs your Maven run and a restart): the deck rule with a backfill of the 124 analysed games (N1, §4a), no-data states (N2), null accuracy (N8), opening names (N11, N13), few-game bars (N10), colour drift (N16) | Fix the data **before** putting more of it on screen; otherwise every later milestone amplifies these bugs |
| **U3** | **Today as a cockpit:** focus with its position, the week, a rating trend, the last game, the next plan session, the top habit and its direction | The page everyone sees first; depends on U2's deck rule |
| **U4** | **Boards in lists:** a static `MiniBoard`, placed on drills, Thrown-Away Wins, patterns, Today's focus and Report items. Game Analysis opens on the worst mistake | The single biggest gain in information per pixel |
| **U5** | **Game Analysis and live Play:** eval graph, move list, de-duplicated commentary (N7); the in-game panel (N6) | The two screens where a chess player expects density |
| **U6** | **Insights Overview:** takeaway line, bars against your own rate (N9), no voids in the grid, the Drill tile (N14) **(done, §6f)** | Already rich; this is a correction pass |
| **U7** | **Ask Prax and first run:** what Prax knows, recent questions, suggestions from the latest report; an "Explore with sample games" mode **(done, §6g)** | Empty states that teach |
| **U8** | **Interaction polish:** hover and press states, row hover, 120–180 ms transitions, honouring reduced motion **(done, §6h)** | Last, because it polishes what exists |

Each milestone ends as before: parity in every affected state (with `expandAll`), the full suite (chromium, narrow and 390 px), `DESIGN_SYSTEM.md` updated, and a stop for your review.

---

## 4. Decisions I need

| # | Decision | My recommendation | Blocks |
|---|---|---|---|
| **D1** | Page widths | Three widths, always centred: **reading 760 px** (Ask Prax thread, drill session), **standard 1200 px** (most pages), **wide** = the full `main` (Game Analysis, live Play, practice review) | U1 |
| **D2** | The sync notice | A compact nav chip while idle ("16 new · Sync", ghost). The glass banner appears only while syncing or analysing (progress) or on failure (alert) | U1 |
| **D3** | **The deck rule** (N1) | Tell me the intended behaviour. My recommendation: every analysed blunder and mistake becomes a review card automatically, as the empty state already promises ("each analysed blunder becomes one"). New cards are capped per day, so the deck doesn't flood | U2, U3 |
| **D4** | The density floor | Add it in U0 in **warn mode** (it reports but doesn't fail). Each floor becomes enforced in the milestone that fixes its screen | U0 |
| **D5** | Fonts | Bundle Inter Tight and JetBrains Mono with `@fontsource`. The app is offline, so no CDN | U1 |
| **D6** | The rail | Build the slot in U1; fill it page by page from U3 | U1 |
| **D7** | The custom background (Paper Shaders and the rest) | Pause until U4. It will look better behind a structured page, and it can't fix an empty one | none |

### 4a. Answers (20 Oct)

- **Approved as recommended:** D1, D2, D4, D5, D6, D7.
- **D3, the deck rule:** review cards are created automatically after each analysis, capped as now (about 120 active), with a daily limit on new cards. Its details, all answered 20 Oct:
  - **D3a · Backfill: yes.** A one-off backfill of the games already analysed when U2 deploys, through the same code path. Generation is idempotent (one card per game and ply), because Start session still calls the generator.
  - **D3b · Which cards fill the cap: yes, in this order.** Blunders before mistakes, then the Training Plan's focus causes, then the most recent. Inaccuracies never become cards. Practice games stay out (`GameSource.PRACTICE`, `PLAY_PLAN.md` I3).
  - **D3c · Daily new-card limit: 10 a day.** A constant, not a setting, for now.
- **D8 · The 7-day wait: removed.** New cards are available straight away, within D3c's limit; only reviews are spaced.

Nothing is open before U2.

---

## 5. U0 · Baseline, metrics, fixtures (in full)

**Goal:** turn §1 into numbers the harness produces, and add the states the screenshots show but the fixtures don't. No product change.

| # | Work | Detail |
|---|---|---|
| 1 | **Capture** | Reuse the throwaway capture spec at 1440×900 and 1920×1080 for all 14 routes. Save to `docs/design/screens/before-polish/`. These become the "before" set for every milestone. |
| 2 | **Layout metrics** in `e2e/fixtures/readability.ts` | Per state, at desktop width: <br>• content left and right edges (the union box of `main`'s children, excluding fixed layers); <br>• **coverage**: that width as a share of the page's width token (after U1) or of `main` (before); <br>• **boards**: count of board roots (react-chessboard's root, later `data-board`); <br>• **charts**: count of chart roots (Recharts surface, the dot grid, later `data-chart`); <br>• **solid buttons visible** above the fold, the banner and nav included. |
| 3 | **New parity states** in `parity/states.ts` | <br>• `today/empty-deck`: 130 games, 124 analysed, 50 drills, 0 cards (the screenshot); <br>• `progress/no-reviews`: no reviews in 7 days; <br>• `game/positional-only`: a game whose mistakes are all positional (N7's repetition); <br>• `play/in-game`: a live game after a few moves. <br>Goldens captured with `PARITY_ONLY`, so nothing else re-baselines. |
| 4 | **Density floor** in `e2e/tests/density.spec.ts` | Primary screens (Today, Progress, Library, Game Analysis, Play, Insights, Pattern Report, Training Plan): <br>• at least 1 board or chart above the fold at 1440×900; <br>• at most 1 solid button visible, banner and nav included; <br>• coverage of at least 0.9 of the page's width token (enforced from U1). <br>**Warn mode** (D4): failures are listed as annotations, not failures, until the milestone that fixes that screen flips its flag. |
| 5 | **Record** | Replace §1 with the harness's table, at both widths. **Audit Pattern Report and Training Plan**, which the screenshot set missed, and add their findings to §2. |
| 0 | ~~**Before anything**~~ | ~~Commit the uncommitted work as separate commits.~~ **Dropped (20 Oct):** everything is committed together, by you. The "before" goldens and screenshots were taken from the working tree; U0 changed no product code, so they show exactly what that commit will contain. |

**Done when:** the table exists at 1440 and 1920; the density spec prints today's failures (expected: Today, Progress, Library, Play pre-game, Settings); the four new states have goldens; product code is untouched; suite green.

---

## 5a. U0 results (20 Oct)

| # | Shipped |
|---|---|
| 1 | **Screenshots:** `docs/design/screens/before-polish/1440/` and `/1920/`, 17 states each (14 routes, plus the empty deck, no recent reviews and positional-only game), full page. 1.7 MB. |
| 2 | **`layout()`** in `e2e/fixtures/readability.ts`: content edges by *ink* (text runs and painted boxes, so a full-width heading box does not count as content), coverage against `main`'s content box, boards (`[data-boardid]`, later `[data-board]`), charts (Recharts surfaces, `[data-chart]`, the Practice history grid, the report's comparison bars), and solid buttons above the fold, nav and banner included. The chart rule is a heuristic until U1 adds `data-chart`. |
| 3 | **Five new parity states** with goldens, captured with `PARITY_ONLY` (no other golden changed): `today/empty-deck`, `progress/no-reviews`, `game-analysis/positional-only`, `play/in-game`, and `library/drills` (the Drills tab, which the plan's list missed). `ParityState` gained `act`, for states no URL opens (a live game, a tab). |
| 4 | **`e2e/tests/density.spec.ts`:** the three floors per primary state at 1440×900, in warn mode (annotations; `DENSITY_PRINT=1` prints them). `ENFORCE` is empty; each milestone adds its screens. |
| 5 | **Record:** §1 above, from `DENSITY.md`; Pattern Report and Training Plan audited (N19–N22). |

**Product code:** unchanged. **Suite:** green: one failure on the first full run (the new `play/in-game` state inherited Play's prose ratio, written for the pre-game redesign), fixed by `RATIO_PENDING` in `readability.spec.ts`, which defers a new baseline's ratio to its milestone (U5 here); the readability and density specs then passed (119).

**For U1, from the numbers:** the coverage floor should be measured against the page's width token, not `main` (Game Analysis and Ask already read 100% against `main`, while Today reads 44%). With a 1200 px standard width inside a 1232 px `main`, the token and `main` differ by 32 px, so the floor of 0.9 holds as written.

---

## 6. U1 · The frame (in full)

**Goal:** every page is centred on one of three widths, has one solid button, one board theme and real fonts. U1 doesn't add content, so pages won't feel *full* yet; they'll feel *deliberate*. Fullness comes from U2 to U5.

| # | Work | Detail |
|---|---|---|
| 1 | **Page widths** (D1) | <br>• Tokens on `:root`: `--page-reading: 760px`, `--page-standard: 1200px`, and wide, which is the full `main`. <br>• One `Page` wrapper (`src/components/Page.tsx`) with a `width` prop, centred in `main`, so every page declares its width once. <br>• Assignment: reading for Ask Prax and the drill session; wide for Game Analysis, live Play and practice review; standard for everything else. <br>• Remove `--prax-gutter`. It reserves space only while a Prax card is open, which on a centred page would shift the whole page sideways each time a card opens. Prax's cards stay fixed bottom right as an overlay. Pages that show them (Today, Progress, Library) get permanent bottom padding equal to the card's height, so a card never covers the last content and nothing moves. |
| 2 | **Rail slot** (D6) | `Page` takes an optional `rail`. At 1200 px and wider it's a 12-column grid: 8 columns of content and 4 of rail, with a 24 px gap. Below 1200 px the rail stacks under the content. **No page uses it in U1**; one fixture page tests it. Today adopts it in U3. |
| 3 | **Sync status into the nav** (D2) | <br>• **Idle with new games:** a ghost chip in the nav's right zone, before Settings: count in mono, "new · Sync". <br>• **Syncing or analysing:** the existing glass banner with progress, unchanged. <br>• **Failed:** the banner with `role="alert"`. <br>• On a phone the chip sits in the nav's top row beside the Settings icon. <br>Result: no solid button outside `main` on any page (closes drift 14). Parity: a reviewed scope move for "16 new games on Chess.com". |
| 4 | **One board theme** | A single `ThemedBoard` wrapper around react-chessboard that applies `--board-light` and `--board-dark` and the coordinate style. All six boards move to it, including the two that skip the tokens today (live Play and Prax's chess-position card). A test asserts the square colours on both (N5). While live Play is open for this, its heading "This game is pressing" (N6) becomes the micro-label **This game**, matching the pre-game section. |
| 5 | **Fonts** (D5) | <br>• `@fontsource/inter-tight` (400, 500, 600, 700) and `@fontsource/jetbrains-mono` (400, 500, 600), latin subset, imported once at the app entry, `font-display: swap`. <br>• They're bundled into the jar, so the app still works offline. Cost: roughly 200 KB of woff2. <br>• Closes drift 6. |
| 6 | **Type steps** | <br>• Tokens: `--fs-display` (stat values), `--fs-h1`, `--fs-section`, `--fs-body`, `--fs-small`, `--fs-micro`. <br>• Two changes in value: the page title becomes clearly larger than section titles, and section titles move far enough above body (about 1.25rem) to read as a level. Everything else keeps its size. <br>• Stat values stay mono with tabular numerals. <br>• One table replaces §4's scale in `DESIGN_SYSTEM.md`. |
| 7 | **Docs** | `DESIGN_SYSTEM.md`: §5 Layout rewritten (page widths, rail, no gutter); §4 fonts are loaded; §6 gains `Page` and `ThemedBoard`; §11 drift 6 and 14 closed; screenshots recaptured. |

**Done when:**
- At 1440 and 1920, every page is centred and its right edge comes from one of three widths. U0's coverage floor is enforced.
- One solid button per view, counting the nav. U0's floor is enforced for every screen.
- Every board uses bone and graphite.
- The network panel shows no external font request, and the screenshots show Inter Tight.
- Parity is green, with the banner scope move as the only new exception. Reading budgets are unchanged.
- The suite is green (chromium, narrow, 390 px).

---

## 6a. U1 results (20 Oct)

| # | Shipped |
|---|---|
| 1 | **Page widths.** `--page-reading` 760px, `--page-standard` 1200px, wide = `main`. `components/Page.tsx`, declared per route in `App.tsx`: Ask Prax reading; Game Analysis, practice review and the live game wide (Play switches itself: standard before a game, wide during one); everything else standard. Inner caps removed (Library and Progress 720, Settings 920, Insights 860, Play 900). Running text keeps a 75ch measure. **`--prax-gutter` removed.** |
| 2 | **Rail slot** in `Page` (8 + 4 of 12 columns from 1200px, stacked below). Unused by pages; the dev-only `/dev/rail` fixture is tested. |
| 3 | **Sync status in the nav.** Idle news is ghost chips before Settings ("16 new · Sync", "3 waiting · Analyse"); the glass banner shows only a running sync or analysis (progress, Stop analysis) or a failure (`role="alert"`, Try again). The nav and the banner share one `useLibraryActions`, so a chip's failure reaches the banner. |
| 4 | **One board theme.** `components/ThemedBoard.tsx`; all six boards moved to it (live Play and Prax's position card were the two off-theme). Live Play's "This game is pressing" is now the micro-label **This game**, and its skill reads "9 of 20", as before a game. |
| 5 | **Fonts** bundled (`@fontsource`, latin, Inter Tight 400–700, JetBrains Mono 400–600). A test checks both load with no external font request. |
| 6 | **Type steps** as tokens (`--fs-display`, `--fs-h1` 1.75rem, `--fs-section` 1.25rem, `--fs-body`, `--fs-small`, `--fs-micro`). Every page title uses `--fs-h1`; report, plan, Play and Settings section titles use `--fs-section`. |
| 7 | **Docs.** `DESIGN_SYSTEM.md` §4 (fonts, scale), §5 (layout), §6 (Page frame, Sync chip, ThemedBoard, Prax cards), §11 drift 6 and 14 closed; the storyboard's analysis step; screenshots recaptured. |

**Density (enforced now):** one solid button per view on every primary screen, and coverage of at least 0.9 of the page width on every primary screen except Today, at **1280×720, 1440×900 and 1920×1080**. Waived: Today's coverage (its cards stay 540px until its rail, U3) and five one-line states (a new player; no report or plan; one from an older version), until U7. The media floor stays in warn mode until U3/U4.

**Changed from the plan:**
- **Prax's cards.** The plan said "overlay bottom right, with permanent bottom padding". That would still cover a page's controls at 1280–1440, where the margin beside a 1200px page is under 100px. Instead: a card sits in the margin when the margin can hold it (1920 and wider); otherwise a progress card is not shown (the banner shows the run) and a question card the player opened overlays the page. Nothing is reserved, so nothing shifts. The tests changed with it: no card on a phone; on a wide screen the card is right of the page.
- **Small laptops.** At 1280px the two sync chips ran into the sections. From 1440px down, chips show their count only (the verb stays in the accessible name), Settings shows its gear, and the sections sit closer; the menu now clears the chips by 194px at 1280 and 77px at 1024.
- **No banner scope move in parity.** Parity collects inside `main`, so the banner's text was never in a golden; the only new exception is the skill format ("9/20" → "9 of 20").

**Suite:** 697 passed, none failed (chromium and narrow; the 390px parity runs still pass).

---

## 6b. U2 results (20 Oct)

| # | Finding | What shipped |
|---|---|---|
| 1 | **N1 · the deck rule** (D3a–c, D8) | `service/drills/DeckRule` (120 active, 10 new a day). `CardGeneratorService`: blunders and mistakes only (never inaccuracies, never practice games); when the cap bites, blunders first, then the latest Training Plan's positions (read from its stored JSON), then the most recent game; a new card is due the day it is made; one card per game and ply (`CardRepository.countForGameAndPly`) and per position. Generation runs **after every analysis run** (`AnalysisPipelineOrchestrator`, never failing the run), **at startup** (`DeckBackfill`: the one-off backfill, idempotent on later starts) and when a session starts, as before. `SessionEngineService`: due reviews no longer include new cards, and new cards in a session are capped by what is left of today's 10 (`countIntroducedSince`, the first attempt today). `ProgressDto.DeckSummary.dueToday` = reviews due + new cards still available today; **Today's "Due today" uses it** (it summed every card before). Test: `CardGeneratorServiceTest` (6). |
| 2 | **N2 · no data drawn as failure** | Progress: "7-day recall" is the last 7 *calendar days* (it took the last 7 entries, so a month-old review counted); with no reviews it is a dash and "No reviews in the last 7 days"; a phase with nothing reviewed is an empty track and "none", not a red "0% · 0/0"; a day with no reviews is a grey bar, not red. |
| 3 | **N8 · the accuracy line falling to 0** | **Not a missing value.** The backend already leaves out unmeasured games (`Game.hasAccuracy`, accuracy > 0). The dips are 14 games with a *measured* accuracy under 20%: the lowest are 0.3%, 1.3% and 1.6% (two losses and a win), 10 of the 14 as Black, none with Chess.com's own evaluations. A win with one flagged mistake at 15.5% is implausible, so the accuracy computation itself is in question (**N23**, below). Not changed in U2: confirming it needs engine runs, which wait for your go. |
| 4 | **N10 · few-game bars** | vs Opponent Strength: a band with fewer than 5 games (`MIN_GAMES`) is faded, grey and marked "· few". |
| 5 | **N11 · "Unknown opening"** | Every unanalysed game had no opening (396 of 396 in your database), though each PGN carries an ECO header: the name was set only by analysis. Sync now reads the ECO header and names it from the ECO table (`OpeningFromPgn`); `OpeningBackfill` names the games already stored, at startup. The Library dates a game "20 Aug 2026", not by the locale. Test: `OpeningFromPgnTest`. |
| 6 | **N13 · one opening, several rows** | Insights' Openings table groups by **name**; the ECO column lists every code it covers ("C21, C22"). |
| 7 | **N16 · colour drift** | Today's and Progress's count tiles are neutral (Learning was amber, Review green, Suspended red). The three warning notices (backups not running, backup failed, the Settings notice) use the soft red, not amber. |
| 8 | **N18 · two date formats** | The last sync reads "21 Aug, 07:36", not "8/21/2026, 7:36:48 AM". |

**N23 · implausible accuracies: fixed (20 Oct).** An engine check of four of the 14 games, with the app's own formula, gave 73.5%, 91.3%, 89.4% and 94.4% against stored 0.3%, 1.6%, 7.9% and 11.4%. Cause: the accuracies were computed before the 17 Aug sign fix (the eval flipped every ply), and accuracy was set only when a game had none, so re-analysis never replaced them. Fix: every analysis recomputes accuracy (`GameAnalysisTransactionService`); `POST /api/analysis/reanalyze-games` re-analyses a list of games as one job. Repair: the 100 games created before 17 Aug were re-analysed (67 min), after a manual `pg_dump` (`backups/before-accuracy-reanalysis-2026-10-20.dump`: the app's backup service can't reach this database container, which belongs to another compose project). Result: 100 of 100 analysed, none under 20%, lowest 71.5%, mean 84.6%. The four checked games came out at 71.5%, 90.8%, 88.4% and 94.7%. **Consequence:** your average accuracy rises, so Play's calibrated engine strength rises with it (it may now sit at the cap, 14 of 20).

**Parity:** reviewed exceptions for the date formats (Library, the last sync), the 7-day recall on the fixture (its reviews are from August, the clock is 16 Oct), and the no-review states' "0%" and "0/0". **Frontend suite:** 709 passed, none failed. **Backend:** not compiled here (your Maven run); new tests `CardGeneratorServiceTest`, `OpeningFromPgnTest`.

---

## 6c. U3 results (20 Oct): Today as a cockpit

| # | Shipped |
|---|---|
| 1 | **Today uses the rail** (`Page` with `rail`, 8 + 4 columns from 1200px): the day's work in the main column, its context beside it. The route no longer wraps Today; it declares its own frame. |
| 2 | **The focus shows a position:** the costliest position of the Pattern Report's top habit, as a board beside the focus (move, loss, the habit, "Open the game"). Without a report the card is text only. |
| 3 | **Order:** focus first (the one solid button), then the deck counts and the week; the 540px caps are gone. |
| 4 | **The rail** (`components/today/TodayRail.tsx`), four panels, each a link to its page: **Rating** (now, the change since the first stored point, a line); **Last game** (opening as the link to its review, result, date, speed, colour, accuracy); **Next session** of the Training Plan (goal, positions, foci; "Make a plan →" when there is none); **Top habit** and its direction since the plan ("1.3 per 100 moves since 16 Oct, down from 3.0", or "too little play to compare"; no panel without a report). No solid button in the rail. |
| 5 | **Density:** Today now meets all three floors at 1280, 1440 and 1920 (the rating line is above the fold; coverage is the full 1200px). |

**Changed on the way:** the rail's wording was cut to keep Today's first-run reading budget (94 → under 79 words): the opening name is the link, an absent report shows no habit panel, no plan is one link. The rating line is decorative beside its figures, so it is neither focusable nor announced (axe flagged Recharts' focusable surface inside an `aria-hidden` box). **Suite:** 717 passed, none failed.

---

## 6d. U4 results (20 Oct): boards in lists

| # | Shipped |
|---|---|
| 1 | **`components/StaticBoard.tsx`:** a position drawn from its FEN as an 8×8 grid in the board tokens, with no chess library and no interaction (fifty boards cost fifty small grids). Oriented for the side to move; one image to assistive tech ("White to move", or a caption); marked `data-board`. |
| 2 | **Library → Drills** is a gallery of positions (N12): each tile is the board, severity, the move, phase and motif, **Drill this** and View game; the tiles fill the row. |
| 3 | **Pattern Report:** each item shows its costliest position beside its three levels. |
| 4 | **Training Plan:** each session card shows its positions (up to four) above their chips. |
| 5 | **Insights → Thrown-Away Wins:** each game shows the position before the move where the win slipped (backend: `BlownGame.turningFen`). |
| 6 | **Game Analysis opens on the costliest mistake** (the most winning chance lost; failing that, the first blunder, then the first mistake), not on the start position (backend: `MoveErrorDto.winPctDrop`). `?ply=` still wins. |
| 7 | **Density:** the media floor is enforced for Library, Pattern Report, Training Plan and Insights at 1280, 1440 and 1920. Waived: the Library's games list (no board per game is planned; decision open) and the empty or old-shape report and plan states (U7). Play's media floor waits for U5. |

**Harness:** boards are left out of parity's reading (`[data-board]`), as interactive boards were: the static board draws its pieces as text. The "Why?" tests now start from the selected costliest mistake. **Suite:** 729 passed, none failed. **Backend:** `MoveErrorDto.winPctDrop` and `BlownGame.turningFen` need your Maven run and a restart; until then Game Analysis falls back to the first blunder and Thrown-Away Wins shows no board.

---

## 6e. U5 results (20 Oct): Game Analysis and live Play

| # | Shipped |
|---|---|
| 1 | **The eval curve is kept.** `Game.evalCurve` (TEXT): the analysis sweep's evaluation after each half-move, White's side, pawns (mate ±100), written by `GameAnalysisTransactionService`. `GameReviewDto.evals` hands it to the page, aligned with the moves, or null when there is none or it no longer lines up (`GameReviewCurveTest`). |
| 2 | **Game Analysis: the game as one line** (`components/analysis/EvalGraph.tsx`): your winning chance after every move, the 50% line, your flagged moves as dots in their severity, the move on the board as a rule; a click shows that position. Games analysed before the curve was kept say so, with **Re-analyse this game** (one game, on request; no engine run starts by itself). |
| 3 | **Game Analysis: every move** (`AnalysisMoveList.tsx`): white and black side by side, flagged moves in their severity colour; any move shows its position on the board ("After 2.Bc4", Back to the mistake), a flagged one selects its mistake. The graph and the list sit above the mistakes, beside the board, so both are above the fold at 1280×720. |
| 4 | **N7: each thing once.** A verified badge that repeats the motif badge is not shown (POSITIONAL twice); the checked commentary is not shown when it is the diagnosis word for word. |
| 5 | **N6: the live game's panel:** This game (opening, engine skill), **Watch for** (the report's top cause and tactic, the habit you are working on) and **Material** ("even", "you +2"), above the move list. |
| 6 | **Density:** Play's media floor is enforced; the live game meets it with its board. Play before a game is waived: there is no position yet, and a board of the target opening is not planned (decision open). |

**Suite:** the full run gave 734 passed and 3 failed, all three the expected parity change (the repeated POSITIONAL badge); with that reviewed exception those three and the U5 tests pass (11). The full suite has not been re-run since. **Backend:** `Game.evalCurve` (a new column; `ddl-auto: update` adds it), `GameReviewDto.evals`; needs your Maven run and a restart. Existing games show the curve once re-analysed, one by one from the page or in bulk (about 65 minutes per 100 games at your settings): your call.


## 6f. U6 results (20 Oct): Insights Overview

| # | Shipped |
|---|---|
| 1 | **A takeaway line (N8)** above the figures (`components/insights/takeaway.ts`): where your Elo went and whether your play went with it. Elo is compared with the rated game furthest from today's rating (a peak or a trough, at least ten games back); accuracy as the mean of ten games from there against your last ten. Moves under 25 Elo or 2 accuracy points read as "held". Below 20 games there is no line. When results fell it links to **What goes wrong →** (the Pattern Report). |
| 2 | **The story changed with N23.** With the corrected accuracies the line now reads: Elo fell from 881 (8 Jun) to 688, and accuracy fell with it, from 84% to 82%. The "accuracy rose while Elo fell" story in N8 came from the stale pre-17 Aug accuracies. |
| 3 | **Win rates against your own (N9).** Every win-rate figure is read against your overall rate (42.5% in rapid, from the time-of-day buckets): 10 points or more above is green, 10 or more below is red, the rest neutral; fewer than 5 games is faded and never coloured. This applies to the three charts, the Resilience figure and the Openings table. Each chart draws your rate as a dashed line, prints each bar's figure, and names the furthest bucket underneath ("Night 65% · 20g · 23 above you", or "No outlier (10+ points)"). On your data, two bars are green (Night, Fri) and one is red (Tue); before, almost all were red. |
| 4 | **No voids.** Win rates are one row of three equal charts (vs Opponent Strength, now a bar chart with its bands' games and accuracy underneath; Time of Day; Day of Week), each chart taking the row's height. Mistakes by Phase is one strip (share of flagged mistakes, segment widths to scale) above Missed Tactics in two to four columns, in one card. Thrown-Away Wins runs full width in two or three columns. Measured at 1366×768: every card's empty space at the bottom is its padding (21 px); before, 517 px in Resilience and 144 px in Opponent Strength and Phase. Tested (`reports.spec.ts`: no card holds more than 40 px). |
| 5 | **The Drill tile (N14)** is gone. Its slot holds Resilience as a figure like the others ("40% · Win % after a loss · 43.1% after a win · 60 / 51 games"). The drill is now a real action: **Drill 8 positions →** under Thrown-Away Wins, the positions where each win slipped. |
| 6 | **Below 1000 px** the win-rate row stacks (`.win-rates`): seven bars do not fit a third of the narrow page. |

**Suite:** 747 passed, chromium plus narrow, with no new parity exceptions. On the way there the prose budget for insights · default was briefly over (141 against 125) and was trimmed back under. Density was re-recorded (`DENSITY.md`). **No backend change.**


## 6g. U7 results (20 Oct): Ask Prax, first run, empty states that teach

| # | Shipped |
|---|---|
| 1 | **Ask Prax before the first question (N15).** What Prax answers from, as three small panels with links: analysed games ("128 of 146"), the pattern report's date, the training plan's date ("none yet" and "Build it" / "Make one" when absent). **From your report:** up to four questions built from the latest report (its costliest cause, its most missed tactic, its costliest opening, its worst phase), e.g. "Why do I keep ignoring my opponent's threat?". Without a report, the three general questions. **Recent:** the last five distinct questions, each reopening its thread. |
| 2 | **Empty states that teach** (`components/TeachingEmpty.tsx`): the empty and older-version Pattern Report and Training Plan, and Play for a new player. Each says what the screen will show (at most 15 words), what it is built from with your own figures (games synced, analysed, why it is empty), the one action, and up to four positions from your games. With no analysed games, one labelled sample position. The five density waivers marked U7 are gone: these states now meet coverage and media. |
| 3 | **Explore with sample games** (`src/sample/`). Sample mode shows the whole app on a sample library: 16 rapid games played by Stockfish at club strength (Skill Level 4 against 2 to 7) as `sample_sam`, analysed by the real pipeline on a separate database (`praxis_sample`, a second backend on 8087), then captured once into `frontend/src/sample/library.json` (205 responses, 0.76 MB, loaded only when the mode is on). In the browser every `/api` read is answered from the library and every write is refused ("Sample games are read-only"); the two Ask Prax questions asked at capture replay their recorded answers. A glass "Sample games" slab on every page says so and is the way out (**Exit sample**). Entry: "Explore with sample games →" in the empty states and on Today before any game is synced. The choice is kept in this browser (`praxis.sample`). |
| 4 | **What in the sample is synthetic,** said here and in `scripts/sample/generate.py`: the clocks (thinking time drawn at random, so time trouble is a dice roll), the ratings (Elo by formula from each result, 1180 to 1232) and the dates (3 Aug to 10 Oct 2026). Every move, evaluation, mistake, diagnosis, the report and the plan are the engine's and the pipeline's. Today's week strip and "due today" are as captured on 20 Oct. Scanned for host data before shipping: no paths, names or install details; the backup list is emptied. |
| 5 | **Rebuilding the sample:** `scripts/sample/generate.py` (self-play), `load_sql.py` (rows as a sync writes them), the second backend with `--server.port=8087 --spring.datasource.url=…/praxis_sample --praxis-chess.chess-com.username=sample_sam`, `POST /api/analysis/analyze-pending`, `POST /api/training-plan/generate`, then `node scripts/sample/capture.mjs`. A re-capture keeps the recorded Prax answers. |
| 6 | **Found on the way (real bugs):** (a) **list boards collapsed empty ranks** (no row template on the grid), so every U4 board in a list was drawn wrong; fixed. (b) **Today's Rating panel always said "Not enough rated games yet"**: it called `/api/dashboard/rating-history`, which the server never had (only the test mock did); it now reads the history from the dashboard stats, and the dead route is gone from the client and the mock. (c) **Start session failed silently** on any error; it now says why. |

**Suite:** 758 passed and 1 failed, chromium plus narrow. The failure was Play's new-player state at 29 prose words against a budget of 26, from the sample link; after trimming that card, the full re-run gave **759 passed, none failed**. New: `sample-mode.spec.ts` (4) and two Ask Prax tests. **No backend change.** The second backend on 8087 can be stopped; `praxis_sample` stays for a re-capture (drop it with `dropdb` when no longer wanted).


## 6h. U8 results (20 Oct): interaction polish

| # | Shipped |
|---|---|
| 1 | **Tokens** on `:root`: `--hover-wash`, `--press-wash`, `--dur-fast` 120ms, `--dur` 150ms, `--dur-slow` 180ms, `--dur-fill` 400ms, `--ease-out`. Every transition in the CSS and in inline styles uses them; the raw 0.1–0.6s values and one `transition: all` are gone. |
| 2 | **Hover and press.** Buttons: a neutral wash on hover, a firmer one and 1px of travel on press; solid buttons darken and dim. Links: the underline fills on hover, with an orchid focus ring now on links too. **Row hover is neutral:** table rows were washed in pink on hover, which read as "selected" on every row the pointer crossed. A shared `.row-link` replaces the five components that set backgrounds from `onMouseEnter`/`onMouseLeave` (Library rows, recent games, the legacy games table, the opening list, Thrown-Away Wins), and the Game Analysis move list now takes the button hover and press. Insights' resting tabs brighten on hover. A card that is a link lights its edge, neutrally (the lit hairline token is orchid-tinted, so it is not used for hover). |
| 3 | **Reduced motion, everywhere:** one global rule sets every transition to 0ms and stops press travel; the two script scrolls (Game Analysis opening on a mistake, Rule Validation) jump instead of gliding (`lib/motion.ts`); the loading GIF shows its still first frame (`praxis_balancing_still.png`, via `<picture>`); the toast's slide becomes a fade. Prax's and the indeterminate progress animations already had reduced forms. |
| 4 | **N17, the week strip:** days still to come were drawn at 0.35 × a hairline (near invisible); they are now dashed rings at full opacity with a 60% label, so the week reads as seven days. |
| 5 | **Tests:** `interaction.spec.ts` (5, both projects): durations within 120–180ms, a press moves 1px, a hovered row and a row link lift in a non-pink wash, reduced motion zeroes transitions, stops travel and stills the spinner, and a future day is a dashed ring. |

**Suite:** 769 passed, chromium plus narrow, none failed. No parity or density change. **No backend change.**


## 6i. After U8 (20 Oct): no title where the nav names the page

Today, Progress, Library, Insights, Ask Prax and Play no longer draw their page title: the lit nav item already says where you are, and the row cost about 70px above the fold at 1280×720. Each keeps an `<h1>` for screen readers and tests (`.sr-only`). `main`'s top padding is 32px (was 24) so content does not sit on the nav. Play's next-game line becomes the lead at `--fs-section`; Ask Prax's subtitle and its History / New chat row stay. Unchanged: Game Analysis, drill sessions, practice review and Settings, whose titles say something the nav does not. **Suite:** 769 passed; parity unchanged (the hidden headings are still page text); density re-recorded.


## 6j. Display hidden (20 Oct)

Settings no longer shows the Display card (background and motion): the backgrounds will be redone. `BACKGROUND_SETTING = false` in `background/preference.ts` hides it and keeps the background **Off for everyone**, including a browser that saved a style earlier, since nothing would be left to turn it off. The styles, the renderer and the saved choice are untouched; the style tests (background, navigation, Prax placement) are skipped with that reason and return when the flag is turned on. New tests: Settings has no Display card; a saved style is not drawn. Found on the way: Vite's dev transform dropped a value import listed after `type` imports in the same braces (`tsc` passed, the page threw), so the flag has its own import line. **Suite:** 757 passed, none failed.

Also done: the `praxis_sample` database is dropped; the stray `praxis-chess-backup` container was already gone (only `praxis-chess-postgres` remains).


## 6k. One tab style (20 Oct)

Library (an underline) and Insights (a pill) drew tabs two ways. Both now use `components/Tabs.tsx`: the underline, orchid for the active tab, on a hairline. Insights keeps link tabs (its sections are addresses: back, forward, bookmarks); Library's become a real ARIA tablist with arrow keys and a labelled tabpanel. The pill is gone because the speed filter right under Insights' tabs is pills too, and two rows of pills read as two filters. The U8 `.tab` hover rule with `!important` is replaced by the component's own styles. **Suite:** 761 passed (two new tests: the same computed look on both pages; arrow keys and the panel).

---

## 7. Tradeoffs, said plainly

- **A centred 1200 px page still leaves about 360 px each side at 1920.** Symmetric margins read as intentional; a 1920 px column would make lines and tables too long. The rail (from U3) is what puts the extra width to work.
- **The sync notice gets quieter.** You may sync less often. The count stays visible on every page; only the solid button goes.
- **Truthful data comes before more data.** U2 delays the visible wins (boards, Today) by one milestone. Without it, a fuller Today would headline the same zeros, and a board-rich Insights would sit beside an accuracy line that falls to 0.
- **Fonts add about 200 KB to the jar.** That's irrelevant offline, and the app stops looking different on every machine.
- **The density floor is a new kind of rule.** It's easy to satisfy with a decorative chart. Review should hold the line that a board or chart counts only when it carries this player's data.
