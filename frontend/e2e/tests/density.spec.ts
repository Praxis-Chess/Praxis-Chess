import * as fs from 'fs'
import * as path from 'path'
import { test, expect } from '../fixtures/test'
import { layout, type Layout } from '../fixtures/readability'
import { STATES, open, type ParityState } from '../fixtures/parity/states'

/**
 * The density floor (docs/design/POLISH_PLAN.md, U0 item 4 and decision D4).
 *
 * The readability harness only measures subtraction: a screen passes its reading
 * budget with four zeros and one sentence. This adds the other side, per primary
 * screen at 1440×900:
 *   - at least one board or chart above the fold (`media`);
 *   - at most one solid button visible above the fold, nav and banner included (`solid`);
 *   - content spanning at least 0.9 of the page's width (`coverage`). Until U1's
 *     width tokens, the width is `main`'s content box.
 *
 * A floor a screen misses is an annotation, not a failure, until the milestone
 * that fixes that screen sets its flag in ENFORCE (warn mode, decision D4). A board or chart
 * counts only when it carries this player's data; review holds that line.
 *
 * DENSITY_RECORD=1 also measures every state at 1440 and 1920 and writes
 * e2e/fixtures/parity/DENSITY.md, the table POLISH_PLAN.md §1 quotes.
 */
const PRIMARY = new Set(['today', 'progress', 'library', 'game-analysis', 'play', 'insights', 'pattern-report', 'training-plan'])

type Floor = 'media' | 'solid' | 'coverage'
/**
 * Per screen, the floors that fail the build; each milestone adds its own.
 * U1 (the frame): one solid button everywhere, and coverage of the page width.
 * U3: Today meets all three (its rail holds the rating chart).
 * U4: Library, Pattern Report, Training Plan and Insights meet `media` too.
 * U5: the live game meets `media`; Play before a game is waived (no position yet).
 */
const ENFORCE: Record<string, Floor[]> = {
  today: ['solid', 'coverage', 'media'],   // U3: the cockpit, with its rail
  progress: ['solid', 'coverage'],
  library: ['solid', 'coverage', 'media'],   // U4: the drills are a gallery of boards
  'game-analysis': ['solid', 'coverage'],
  play: ['solid', 'coverage', 'media'],   // U5: the live game has its board
  'pattern-report': ['solid', 'coverage', 'media'],   // U4: each item shows its costliest position
  'training-plan': ['solid', 'coverage', 'media'],    // U4: each session shows its positions
  insights: ['solid', 'coverage', 'media'],
}
/**
 * States a floor cannot apply to yet, with the milestone that answers them.
 * A one-line state (a new player, no report, a report from an older version)
 * cannot span a page; U7 gives empty states their own content.
 */
const WAIVE: Record<string, { floors: Floor[]; until: string }> = {
  // Before a game there is no position yet; a board of the target opening is not planned (decision open).
  'play/default': { floors: ['media'], until: 'open' },
  'play/one-game': { floors: ['media'], until: 'open' },
  'play/six-games': { floors: ['media'], until: 'open' },
  // The games list is one row per game: no board per game is planned (decision open).
  'library/default': { floors: ['media'], until: 'open' },
}

const COVERAGE_FLOOR = 0.9

function misses(l: Layout): { floor: Floor; detail: string }[] {
  const out: { floor: Floor; detail: string }[] = []
  if (l.boardsAboveFold + l.chartsAboveFold < 1) out.push({ floor: 'media', detail: `no board or chart above the fold (${l.boards} boards, ${l.charts} charts on the page)` })
  if (l.solidAboveFold > 1) out.push({ floor: 'solid', detail: `${l.solidAboveFold} solid buttons above the fold: ${l.solidLabels.join(', ')}` })
  if (l.coverage < COVERAGE_FLOOR) out.push({ floor: 'coverage', detail: `content spans ${Math.round(l.coverage * 100)}% of the page width (${l.left}–${l.right} of ${l.mainLeft}–${l.mainRight})` })
  return out
}

test.use({ locale: 'en-US', timezoneId: 'UTC' })

// A small laptop (1280×720), the plan's two widths, and a large screen. Phones are out of scope (20 Oct).
for (const [w, h] of [[1280, 720], [1440, 900], [1920, 1080]]) test.describe(`Density floor at ${w}`, () => {
  test.use({ viewport: { width: w, height: h } })
  for (const s of STATES.filter(x => PRIMARY.has(x.screen))) {
    test(`${s.screen} · ${s.state}`, async ({ page, api }, info) => {
      test.skip(info.project.name !== 'chromium', 'Measured at desktop width')
      await open(page, api, s)
      const l = await layout(page)
      const found = misses(l)
      for (const m of found) info.annotations.push({ type: `density: ${m.floor}`, description: m.detail })
      if (process.env.DENSITY_PRINT) console.log(`DENSITY ${s.screen}/${s.state}: ${found.length ? found.map(m => m.detail).join('; ') : 'meets every floor'}`)
      const waived = WAIVE[`${s.screen}/${s.state}`]?.floors ?? []
      const enforced = found.filter(m => (ENFORCE[s.screen] ?? []).includes(m.floor) && !waived.includes(m.floor))
      expect(enforced.map(m => m.detail), 'density floor').toEqual([])
    })
  }
})

/** Not a test: the baseline table at both widths. */
test.describe('Density record', () => {
  test.skip(!process.env.DENSITY_RECORD, 'Set DENSITY_RECORD=1 to write DENSITY.md')
  test.describe.configure({ mode: 'serial' })
  const rows: { s: ParityState; w: number; l: Layout }[] = []
  for (const [w, h] of [[1440, 900], [1920, 1080]]) {
    test(`record at ${w}`, async ({ page, api }, info) => {
      test.skip(info.project.name !== 'chromium', 'Desktop only')
      test.setTimeout(300_000)
      await page.setViewportSize({ width: w, height: h })
      for (const s of STATES) {
        // Later rules win: re-registering the defaults undoes the last state's overrides.
        api.installDefaults()
        await open(page, api, s)
        rows.push({ s, w, l: await layout(page) })
      }
    })
  }
  test.afterAll(() => {
    if (rows.length === 0) return
    const lines = [
      '# Density baseline',
      '',
      'Generated by `e2e/tests/density.spec.ts` with `DENSITY_RECORD=1` (docs/design/POLISH_PLAN.md, U0).',
      'Edges are CSS pixels from the left of the viewport. Coverage is the content width over `main`\'s content box',
      '(the page-width token after U1). Boards and charts are counted on the whole page, with how many start above',
      'the fold in brackets. Solid buttons are those visible above the fold, nav and banner included.',
      '',
    ]
    for (const w of [1440, 1920]) {
      lines.push(`## ${w} px`, '', '| Screen · state | Left | Right | Coverage | Boards | Charts | Solid above fold | Floors missed |', '|---|---:|---:|---:|---:|---:|---|---|')
      for (const { s, l } of rows.filter(r => r.w === w)) {
        const m = PRIMARY.has(s.screen) ? misses(l).map(x => x.floor).join(', ') || 'none' : 'n/a (not primary)'
        lines.push(`| ${s.screen} · ${s.state} | ${l.left} | ${l.right} | ${Math.round(l.coverage * 100)}% | ${l.boards} (${l.boardsAboveFold}) | ${l.charts} (${l.chartsAboveFold}) | ${l.solidAboveFold}${l.solidLabels.length ? ': ' + l.solidLabels.join(', ') : ''} | ${m} |`)
      }
      lines.push('')
    }
    fs.writeFileSync(path.join(__dirname, '../fixtures/parity/DENSITY.md'), lines.join('\n'))
  })
})
