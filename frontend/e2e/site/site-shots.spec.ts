import * as fs from 'fs'
import * as path from 'path'
import { test } from '../fixtures/test'
import * as data from '../fixtures/data'
import * as reports from '../fixtures/reports'
import { STATES, open } from '../fixtures/parity/states'

/**
 * Screenshots for the Praxis Chess website (assets/app/*.webp there).
 *
 * The real frontend, photographed at 1440×900 at 2×, on the suite's own
 * made-up data (ApiMock and the fixtures): no database, no engine, no model,
 * and nobody's real games. Off in every normal run; set SITE_SHOTS to take
 * them:
 *
 *   SITE_SHOTS=1 SHOTS_OUT=<dir> npx playwright test e2e/site --project=chromium
 *
 * then convert with the website's tools/towebp.py. A few screens get a fuller
 * made-up library than the tests need (a 60-game trend, every hour and day),
 * because a screenshot of a nearly empty chart argues against the sentence it
 * sits under.
 */
const OUT = process.env.SHOTS_OUT ?? 'test-results/site-shots'
const state = (key: string) => {
  const s = STATES.find(x => `${x.screen}/${x.state}` === key)
  if (!s) throw new Error(`no parity state ${key}`)
  return s
}

// A seeded generator, so a re-capture draws the same library.
let seed = 20261020
const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff)

/** Sixty games over three months: accuracy edging up from the mid-60s, Elo from 1180 to 1284. */
function trend() {
  const start = Date.parse('2026-07-18T18:00:00Z')
  let avg: number[] = []
  return Array.from({ length: 60 }, (_, i) => {
    const t = start + i * 1.5 * 86_400_000 + rnd() * 6 * 3_600_000
    const accuracy = Math.round((62 + i * 0.13 + (rnd() - 0.5) * 22) * 10) / 10
    avg = [...avg, accuracy].slice(-10)
    return {
      date: new Date(t).toISOString().slice(0, 10), played_at: new Date(t).toISOString(),
      accuracy, moving_avg: Math.round((avg.reduce((a, b) => a + b, 0) / avg.length) * 10) / 10,
      rating: Math.round(1180 + i * 1.75 + (rnd() - 0.5) * 30),
    }
  })
}
const bucket = (label: string, games: number, wins: number) => ({ label, games, wins, win_pct: Math.round((1000 * wins) / games) / 10 })

const insights = {
  ...data.insights,
  accuracy_trend: trend(),
  time_of_day: [bucket('Night', 14, 4), bucket('Morning', 18, 10), bucket('Afternoon', 22, 11), bucket('Evening', 47, 23)],
  day_of_week: [bucket('Mon', 12, 6), bucket('Tue', 13, 4), bucket('Wed', 14, 7), bucket('Thu', 12, 6),
                bucket('Fri', 15, 9), bucket('Sat', 18, 10), bucket('Sun', 17, 6)],
  openings: [
    { eco: 'B20', name: 'Sicilian Defense', games: 21, win_pct: 52.4, avg_accuracy: 68.8 },
    { eco: 'C50', name: 'Italian Game', games: 17, win_pct: 58.8, avg_accuracy: 71.2 },
    { eco: 'A01', name: 'Nimzovich-Larsen Attack', games: 12, win_pct: 33.3, avg_accuracy: 49.2 },
    { eco: 'D02', name: "Queen's Pawn Game", games: 9, win_pct: 44.4, avg_accuracy: 66.0 },
    { eco: 'C21', name: 'Center Game', games: 5, win_pct: 20.0, avg_accuracy: 58.4 },
  ],
}

/**
 * The Game Analysis shot uses a game from the app's sample library: Stockfish
 * playing a made-up player at club strength, analysed by the real pipeline
 * (scripts/sample). A loss with two blunders and two mistakes, and its curve.
 */
const LIB_FILE = path.join(__dirname, '../../src/sample/library.json')
// Read only when present, so a checkout without the sample library still loads this file (and skips it).
const LIB = (fs.existsSync(LIB_FILE) ? JSON.parse(fs.readFileSync(LIB_FILE, 'utf8')) : { responses: {} }) as { responses: Record<string, unknown> }
const SAMPLE_GAME = '5afcc5c9-6cea-4eb6-a45a-7b0cf591d7de'
const sampleGame = (a: import('../fixtures/ApiMock').ApiMock) => {
  for (const [k, v] of Object.entries(LIB.responses)) {
    if (k.includes(SAMPLE_GAME)) a.json(k, v)
  }
}

/** Three weeks of reviews ending on the fixture clock's day (16 Oct), most days. */
function history() {
  const out = []
  for (let d = 20; d >= 0; d--) {
    if (rnd() < 0.25) continue
    const day = new Date(Date.parse('2026-10-16T12:00:00Z') - d * 86_400_000).toISOString().slice(0, 10)
    const o = 2 + Math.floor(rnd() * 4), m = 5 + Math.floor(rnd() * 7), e = 1 + Math.floor(rnd() * 3)
    const share = 0.62 + (20 - d) * 0.009 + (rnd() - 0.5) * 0.12
    const oc = Math.min(o, Math.round(o * (share + 0.08))), mc = Math.min(m, Math.round(m * (share - 0.05))), ec = Math.min(e, Math.round(e * share))
    const reviewed = o + m + e, correct = oc + mc + ec
    out.push({ date: day, reviewed, correct, again: reviewed - correct, accuracy: Math.round((1000 * correct) / reviewed) / 10,
               avg_interval_days: Math.round((2.4 + (20 - d) * 0.12) * 10) / 10,
               phases: { opening_correct: oc, opening_total: o, middlegame_correct: mc, middlegame_total: m, endgame_correct: ec, endgame_total: e } })
  }
  return out
}

const RUN = 'cc33dd44-ee55-4f66-8a77-112233445566'
const QUESTION = 'What mistake am I repeating most often?'
const answer = {
  answer: 'Ignoring your opponent\'s threat, and mostly in the middlegame. Of 145 serious mistakes the rules could explain, 32 were moves made without answering what the opponent had just threatened, 19 of them blunders, and they cost you 27% of your winning chances each. Most land between moves 11 and 30, where you make 17.6 serious mistakes per 100 moves against 10.8 in the opening. Before each middlegame move, name your opponent\'s best reply; your training plan already has the 32 positions.',
  findings: [
    "Ignoring your opponent's threat: 32 of 145 serious mistakes (22%)",
    '19 of those 32 were blunders',
    'Middlegame: 17.6 serious mistakes per 100 moves, opening 10.8',
    'Forks are the tactic you miss most: 14, 11 of them blunders',
  ],
  evidence: [
    { label: "Ignoring your opponent's threat", value: '32 / 145', sample_size: 145, source: 'pattern report' },
    { label: 'Of them blunders', value: '19', sample_size: 32, source: 'pattern report' },
    { label: 'Winning chance lost each', value: '−27%', sample_size: 32, source: 'engine' },
    { label: 'Middlegame per 100 moves', value: '17.6', sample_size: 547, source: 'pattern report' },
    { label: 'Positions in your plan', value: '32', sample_size: 32, source: 'training plan' },
  ],
  // Each lookup's sample is counted in games: the footnote reads "Based on 101 of your games".
  steps: [{ tool: 'get_pattern_report', sample_size: 101 }, { tool: 'get_phase_rates', sample_size: 101 },
          { tool: 'get_training_plan', sample_size: 101 }, { tool: 'get_player_profile', sample_size: 101 }],
  partial: false, sources: [], lane: 'PLAYER', grounding: 'GROUNDED', artifacts: [],
  conversation_id: 'dd44ee55-ff66-4a77-8b88-223344556677',
}

test.describe('Website screenshots', () => {
  test.skip(!process.env.SITE_SHOTS, 'Set SITE_SHOTS=1 to capture the website screenshots')
  test.use({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, colorScheme: 'dark' })

  type Page = import('@playwright/test').Page
  type Box = { x: number; y: number; width: number; height: number }

  /** The whole viewport: for a screen the site shows in full. */
  const shot = (page: Page, name: string) => page.screenshot({ path: path.join(OUT, `${name}.png`) })

  const box = async (page: Page, selector: string, text?: string | RegExp): Promise<Box> => {
    const l = text ? page.locator(selector, { hasText: text }).first() : page.locator(selector).first()
    const b = await l.boundingBox()
    if (!b) throw new Error(`nothing to frame: ${selector} ${text ?? ''}`)
    return b
  }

  /**
   * A capture framed for its slot (the website crops with object-fit: cover,
   * so a capture already at the slot's shape shows exactly what it frames).
   * `region` is what must be seen; it grows to `aspect` inside `bounds`, first
   * the short way, then (if that runs out) by trimming the long way from the
   * end, so the region's start is never cut. `grow` says which way height goes.
   */
  async function framed(page: Page, name: string, region: Box, aspect: number,
                        opts: { bounds?: Partial<Box>; grow?: 'down' | 'up' } = {}) {
    await page.evaluate(() => window.scrollTo(0, 0))
    const doc = await page.evaluate(() => ({ w: document.documentElement.clientWidth, h: document.documentElement.scrollHeight }))
    const B = { x: opts.bounds?.x ?? 0, y: opts.bounds?.y ?? 0, width: opts.bounds?.width ?? doc.w, height: opts.bounds?.height ?? doc.h }
    let { x, y, width: w, height: h } = region
    if (w / h < aspect) {
      // Too narrow: widen about the centre, within bounds; failing that, take the bounds and shorten.
      const want = h * aspect
      if (want <= B.width) { x = Math.min(Math.max(B.x, x - (want - w) / 2), B.x + B.width - want); w = want }
      else { x = B.x; w = B.width; h = w / aspect }
    } else {
      // Too wide: grow the height (down by default); failing that, narrow from the right.
      const want = w / aspect
      if (want <= B.height) {
        if (opts.grow === 'up') y = Math.max(B.y, y + h - want)
        y = Math.min(y, B.y + B.height - want); h = want
      } else { h = B.height; y = B.y; w = h * aspect }
    }
    await page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: true,
                            clip: { x: Math.round(x), y: Math.round(y), width: Math.round(w), height: Math.round(h) } })
  }
  const span = (a: Box, b: Box, pad = 16): Box => {
    const x = Math.min(a.x, b.x) - pad, y = Math.min(a.y, b.y) - pad
    return { x, y, width: Math.max(a.x + a.width, b.x + b.width) + pad - x, height: Math.max(a.y + a.height, b.y + b.height) + pad - y }
  }

  test('today', async ({ page, api }) => {
    await open(page, api, { ...state('today/default'), setup: a => {
      a.json('/api/patterns', reports.pattern)
      a.json('/api/training-plan', reports.trainingPlan)
    } })
    await page.getByRole('button', { name: /Show evidence/i }).first().click()
    await page.waitForTimeout(400)
    // Its main column, from the focus card to the week (a 4:3 panel), clear of the rail.
    const main = await box(page, '.page-main')
    const focus = await box(page, '.page-main .card', /focus/i)
    await framed(page, 'today', span(focus, { ...main, y: main.y + main.height - 1, height: 1 }, 20), 4 / 3,
                 { bounds: { x: main.x - 28, width: main.width + 40 } })
  })

  test('report', async ({ page, api }) => {
    await open(page, api, { screen: 'game-analysis', state: 'sample', path: `/games/${SAMPLE_GAME}`, setup: sampleGame })
    await page.waitForTimeout(800)
    // The screen below the nav, without the empty margins (16:10).
    const frame = await box(page, '[data-page]')
    const nav = await box(page, 'nav, header')
    const card = await box(page, '[id^="mistake-"]')
    const top = nav.y + nav.height + 8
    await framed(page, 'report', { x: frame.x - 16, y: top, width: frame.width + 32, height: card.y + card.height + 20 - top }, 16 / 10)
  })

  test('patterns', async ({ page, api }) => {
    await open(page, api, state('pattern-report/default'))
    // The 3:1 band: the costliest habit and the first weakness, whole.
    const habit = await box(page, 'main p, main div', /^Your costliest habit/)
    const first = await box(page, 'main li', /Ignoring your opponent/)
    await framed(page, 'patterns', span(habit, first, 22), 3 / 1)
  })

  test('insights', async ({ page, api }) => {
    await open(page, api, { ...state('insights/default'), setup: a => { a.json('/api/insights', insights) } })
    // From the takeaway to the end of the trend (16:10).
    const takeaway = await box(page, '[aria-label="Takeaway"]')
    const trend = await box(page, '.card', /Accuracy & Elo Trend/)
    const nav = await box(page, 'nav, header')
    await framed(page, 'insights', span(takeaway, trend, 22), 16 / 10, { grow: 'up', bounds: { y: nav.y + nav.height + 8 } })
  })

  test('play', async ({ page, api }) => {
    // A 4:3 window: the page fills it, nav to the practice patterns.
    await page.setViewportSize({ width: 1200, height: 900 })
    await open(page, api, state('play/six-games'))
    await shot(page, 'play')
  })

  test('drill', async ({ page, api }) => {
    await open(page, api, state('drills/plan-session'))
    // The session's heading and the drill's board and words (16:10), not the card's empty right half.
    const head = await box(page, 'main h1, main h2', /Session/)
    const drill = await box(page, 'main .card')
    const top = head.y - 24, bottom = drill.y + drill.height + 24
    await framed(page, 'drill', { x: drill.x - 24, y: top, width: (bottom - top) * 1.6, height: bottom - top }, 16 / 10)
  })

  test('progress', async ({ page, api }) => {
    await open(page, api, { ...state('progress/default'), setup: a => { a.json('/api/progress', { ...data.progress, history: history() }) } })
    await shot(page, 'progress')
  })

  test('ask', async ({ page, api }) => {
    await open(page, api, { ...state('ask/default'), setup: a => {
      a.json('/api/patterns', null)
      a.set('/api/prax/ask/stream', { status: 202, body: { run_id: RUN } })
      a.json(`/api/prax/run/${RUN}`, { run_id: RUN, status: 'DONE', steps: answer.steps, artifacts: [], answer, error: null, elapsed_ms: 4200 })
    } })
    await page.getByPlaceholder(/Ask about your games/i).fill(QUESTION)
    await page.getByRole('button', { name: /^Ask$/ }).click()
    await page.getByText('Ignoring your opponent\'s threat, and mostly').waitFor()
    await page.waitForTimeout(600)
    // The question, its answer and evidence, under the page's heading (4:3).
    const frame = await box(page, '[data-page]')
    const foot = await box(page, 'main *', /^Based on \d+ of your games/)
    await framed(page, 'ask', { x: frame.x - 28, y: 60, width: frame.width + 56, height: foot.y + foot.height + 24 - 60 }, 4 / 3)
  })

  // The tall slot (3:4): the same report in a portrait window, where its items stack.
  test('patterns-tall', async ({ page, api }) => {
    await page.setViewportSize({ width: 840, height: 1120 })
    await open(page, api, state('pattern-report/default'))
    await shot(page, 'patterns-tall')
  })
})
