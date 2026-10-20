import * as fs from 'fs'
import * as path from 'path'
import { test, expect } from '../fixtures/test'
import {
  checkParity, collect, expandAll, metrics, readingBudget, tokens, type Exceptions, type Golden,
} from '../fixtures/readability'
import { STATES, open, vocabulary, type ParityState } from '../fixtures/parity/states'

/**
 * "Show, don't tell" (DESIGN_SYSTEM.md, principle 12) and the no-data-loss rule
 * (docs/design/READABILITY_PLAN.md).
 *
 * Parity: every fact in the golden (captured before the readability work) is
 * still on the screen, in its own item, after every disclosure and popover is
 * opened, unless parity-exceptions.json records why not.
 *
 * Budget: prose words in the default state, per screen, once that screen has
 * been through its milestone; and the per-element budget wherever an element
 * declares what it is with data-reading.
 */
const DIR = path.join(__dirname, '../fixtures/parity')
const ex: Exceptions = JSON.parse(fs.readFileSync(path.join(DIR, 'parity-exceptions.json'), 'utf8'))
const vocab = vocabulary()
const golden = (s: ParityState): Golden | null => {
  const f = path.join(DIR, 'golden', `${s.screen}.${s.state}.json`)
  return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : null
}

/**
 * Prose words allowed in the default state, as a share of the state's own
 * baseline: at least 40% fewer. A screen is listed when its milestone ships.
 * States that started under 100 words (empty states, old shapes) only have to
 * meet the element budgets: there is too little in them to cut by a ratio.
 * Measured on desktop, where the goldens were captured; at phone width a
 * stacked table names each value's column, which adds words on purpose.
 */
const PROSE_RATIO: Record<string, number> = {
  'pattern-report': 0.6,   // M1
  'training-plan': 0.6,    // M2
  insights: 0.6,           // M3
  settings: 0.6,           // M4
  today: 0.73,             // M5: D8, about −27%; its words are tiles, weekdays and the headline
  play: 0.5,               // Play redesign: at most half of each state's baseline
}
/** Below this many baseline words a state only meets the element budgets. Play's target covers every state. */
const RATIO_FROM: Record<string, number> = { play: 0 }
/**
 * States added as the "before" of a later milestone (POLISH_PLAN.md U0): their
 * screen's prose ratio applies once that milestone has been through them.
 */
const RATIO_PENDING = new Set<string>(['play/in-game'])   // U5
/** Screens that may show no paragraph at all by default. */
const NO_PARAGRAPHS = new Set<string>(['pattern-report', 'training-plan', 'insights', 'play'])
/** Words visible in one element that declares its role. */
const ELEMENT_BUDGET: Record<string, number> = { takeaway: 20, intro: 15, item: 8 }

test.use({ locale: 'en-US', timezoneId: 'UTC' })

async function check(page: import('@playwright/test').Page, api: import('../fixtures/ApiMock').ApiMock, s: ParityState,
                     desktop = false) {
  const g = golden(s)
  expect(g, `No golden for ${s.screen}/${s.state}: run PARITY_CAPTURE=1 npx playwright test parity-capture`).not.toBeNull()
  await open(page, api, s)

  const m = metrics(await collect(page))
  const reading = `prose ${g!.metrics.prose} → ${m.prose} · data ${g!.metrics.data} → ${m.data} · paragraphs ${m.paragraphs.length}`
  test.info().annotations.push({ type: 'reading', description: reading })
  // READABILITY_PRINT=1 lists every screen's before → after, for a milestone summary.
  if (process.env.READABILITY_PRINT) {
    console.log([`READING ${s.screen}/${s.state}: ${reading}`, ...m.paragraphs.map(p => `  ¶ ${p}`)].join('\n'))
  }
  const ratio = PROSE_RATIO[s.screen]
  if (ratio != null && desktop && !RATIO_PENDING.has(`${s.screen}/${s.state}`) && g!.metrics.prose >= (RATIO_FROM[s.screen] ?? 100)) {
    expect(m.prose, `prose words over budget (${Math.round((1 - ratio) * 100)}% fewer than ${g!.metrics.prose})`)
      .toBeLessThanOrEqual(Math.ceil(g!.metrics.prose * ratio))
  }
  if (NO_PARAGRAPHS.has(s.screen)) expect(m.paragraphs, 'paragraphs visible by default').toEqual([])
  for (const r of await readingBudget(page)) {
    expect(tokens(r.text).length, `${r.kind} "${r.text}"`).toBeLessThanOrEqual(ELEMENT_BUDGET[r.kind] ?? Infinity)
  }

  const failures = checkParity(g!, await expandAll(page), vocab, ex)
  expect(failures, 'facts missing after opening every disclosure (add a reviewed exception if deliberate)').toEqual([])
}

test.describe('Parity and reading budget', () => {
  for (const s of STATES) {
    test(`${s.screen} · ${s.state}`, async ({ page, api }, info) => { await check(page, api, s, info.project.name === 'chromium') })
  }
})

/** A check that cannot fail proves nothing: lose one fact, move another, and both must be caught. */
test('parity catches a fact that is lost or leaves its item', async ({ page, api }) => {
  const s = STATES.find(x => x.screen === 'pattern-report' && x.state === 'default')!
  await open(page, api, s)
  // The stat lines are in each item's details.
  for (const name of ['Missing your own chances', "Ignoring your opponent's threat"]) {
    await page.getByRole('listitem', { name }).getByRole('button', { name: 'Details' }).click()
  }
  await page.evaluate(() => {
    const cause = document.querySelector('li[aria-label="Missing your own chances"]')!
    const stat = Array.from(cause.querySelectorAll('span')).find(e => e.textContent?.startsWith('22 of 145'))!
    stat.textContent = '22 of 145 (15%) · −19% winning chance each'          // "9 blunders" lost
    const other = document.querySelector('li[aria-label="Forks"] h4')!
    other.textContent += ' 32'                                               // a copy of cause 1's count, elsewhere
    const first = document.querySelector('li[aria-label="Ignoring your opponent\'s threat"]')!
    const own = Array.from(first.querySelectorAll('span')).find(e => e.textContent?.startsWith('32 of 145'))!
    own.textContent = own.textContent!.replace('32 of', 'of')                // ...and gone from its own item
  })
  const failures = checkParity(golden(s)!, await expandAll(page), vocab, ex)
  expect(failures.join('\n')).toContain("Missing your own chances :: 9 — was 1, now 0")
  expect(failures.join('\n')).toContain("Ignoring your opponent's threat :: 32 — was 1, now 0")
})

/**
 * The `narrow` project is a half-width laptop window, not a phone. The hard rule
 * is about phones (no hover), so parity also runs at 390px with touch.
 */
test.describe('Parity at 390px', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true })
  for (const s of STATES) {
    test(`${s.screen} · ${s.state} @phone`, async ({ page, api }, info) => {
      test.skip(info.project.name !== 'chromium', 'One phone run is enough')
      await check(page, api, s)
    })
  }
})
