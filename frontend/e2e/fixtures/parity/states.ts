import type { Page } from '@playwright/test'
import type { ApiMock } from '../ApiMock'
import * as data from '../data'
import * as reports from '../reports'
import type { Drill, Pattern, TrainingPlan } from '../../../src/api/types'

/**
 * Every screen in the inventory, in every state whose text differs: the caveats
 * the readability work promises to keep show up only in some of them (few rows,
 * no clock data, an old report, a finished session, first run).
 *
 * Shared by parity-capture.spec.ts (writes the goldens) and readability.spec.ts
 * (checks against them), so both see exactly the same page.
 */

/** Fixed, so relative times ("3 h ago") and absolute ones agree on every run. */
export const NOW = '2026-10-16T12:00:00Z'
const hoursAgo = (h: number) => new Date(Date.parse(NOW) - h * 3_600_000).toISOString()

export interface ParityState {
  screen: string
  state: string
  path: string
  setup?: (api: ApiMock) => void
}

// The current model line-up: qwen2.5:7b is retired, per-move explanations are off.
const ai = { ...data.aiLocal, features: data.aiLocal.features
  .filter(f => f.feature !== 'explanations')
  .map(f => (f.feature === 'reports' ? { ...f, model: 'qwen3:4b-instruct' } : f)) }
const models = [
  { name: 'qwen3:4b-instruct', role: 'Pattern report, training plan, Ask Prax', state: 'present' as const, completed: 0, total: 0, message: null },
  { ...data.setupReady.models[2] },
]
const ready = { ...data.setupReady, models,
  checks: data.setupReady.checks.map(c => (c.key === 'ollama' ? { ...c, detail: '2 model(s) ready' } : c)) }
const fresh = { ...data.setupFresh, models: [
  { ...models[0], state: 'downloading' as const, completed: 1_300_000_000, total: 2_500_000_000 },
  { ...models[1], state: 'waiting' as const },
], checks: data.setupFresh.checks.map(c => (c.key === 'ollama' ? { ...c, detail: 'Downloading 2 model(s)' } : c)) }
const backups = {
  ...data.backups,
  last_check_at: hoursAgo(3),
  last_verification: { ...data.backups.last_verification!, verified_at: hoursAgo(50) },
  snapshots: data.backups.snapshots.map((s, i) => ({ ...s, taken_at: hoursAgo(27 + 24 * i) })),
}
backups.latest = backups.snapshots[0]

const SESSION = '9c1f0b7a-3d4e-4a21-8f60-1b2c3d4e5f60'
const card = {
  id: 'c1', fen_position: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4',
  move_played: 'Ng5', better_move: 'd2d3', severity: 'MISTAKE', tactical_motif: 'FORK', game_phase: 'OPENING',
  player_color: 'white', explanation: 'Ng5 leaves e4 undefended; d3 keeps the centre.', status: 'REVIEW',
  interval_days: 1, due_date: '2026-08-22', review_count: 2, lapse_count: 0,
}

const GAME = '7c3c9a2e-1111-4a22-8b33-445566778899'
export const drill = (id: string, move: string, ply = 36): Drill => ({
  id, fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3', best_move: 'f1c4', move_played: move,
  severity: 'BLUNDER', tactical_motif: 'FORK', game_phase: 'MIDDLEGAME', explanation: null, player_color: 'white', game_id: GAME,
  ply,
})

/** A long opening name, to test wrapping in rows, chips and popovers. */
export const LONG_OPENING = 'French Defense, Winawer Variation'
const longNames: Pattern = {
  ...reports.pattern,
  facts: {
    ...reports.pattern.facts!,
    top_causes: reports.pattern.facts!.top_causes.map(w => ({
      ...w, positions: w.positions.map(p => ({ ...p, opening: LONG_OPENING })) })),
    openings: [{ ...reports.pattern.facts!.openings[0], name: LONG_OPENING, eco: 'C18' }],
  },
}
const oldShapePattern: Pattern = { ...reports.pattern, facts: null }
const plan = (progress: TrainingPlan['progress']): TrainingPlan => ({ ...reports.trainingPlan, progress })
const oldShapePlan: TrainingPlan = {
  ...reports.trainingPlan,
  plan: { ...reports.trainingPlan.plan!, sessions: reports.trainingPlan.plan!.sessions.map(s => ({ ...s, goal: undefined as unknown as string })) },
}

// ── Play & Improve: the states the page's text differs in ─────────────────────
const UNTIMED = 'Time and decision-making — practice games are untimed, so no clock is recorded and none can be reconstructed.'
const centreCaveat = 'Every one of these games is the Center Game, because your opponent is built to play your weakest ' +
  'opening. Some of what follows may be specific to that opening rather than a habit you carry everywhere.'
/** One analysed practice game, below the patterns threshold: the screen in the review that prompted the redesign. */
const oneGameHistory = [{
  id: '77777777-7777-4777-8777-777777777777', game_id: data.PRACTICE_GAME_ID,
  started_at: '2026-10-01T18:20:00Z', finished_at: '2026-10-01T18:41:00Z', status: 'FINISHED' as const,
  result: 'loss', skill_level: 14, target_opening: 'Center Game', rated: true, analysed: true,
}]
const oneGamePatterns = {
  games_considered: 1, claimable: false, opening_caveat: centreCaveat,
  caveat: 'Only 1 analysed practice game so far. Here is what they show, but 5 are needed before calling anything a tendency.',
  patterns: [], not_measured: [UNTIMED],
}
const newPlayer = { ...data.opponentProfile, personalised: false, target_eco: null, target_opening: null,
  target_phase: null, target_motif: null, targeted_weakness: null, skill_level: 5,
  rationale: ['No analysed games yet, so this opponent is not shaped by your play. Sync and analyse some games and it will be.'] }

const base = (api: ApiMock) => {
  api.json('/api/settings/ai', ai)
  api.json('/api/setup/status', ready)
  api.json('/api/backups', backups)
}
const withReport = (p: Pattern | null) => (api: ApiMock) => api.json('/api/patterns', p)
const withPlan = (p: TrainingPlan | null) => (api: ApiMock) => {
  api.json('/api/training-plan', p)
  api.json('/api/patterns', reports.pattern)
}

export const STATES: ParityState[] = [
  { screen: 'today', state: 'default', path: '/' },
  { screen: 'today', state: 'first-run', path: '/', setup: api => { api.json('/api/setup/status', fresh); api.json('/api/settings/player', data.playerNone) } },
  { screen: 'progress', state: 'default', path: '/progress' },
  { screen: 'library', state: 'default', path: '/library' },
  { screen: 'game-analysis', state: 'default', path: `/games/${data.WHY_GAME_ID}`,
    setup: api => api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [data.scholarsMistakeVerified]) },
  { screen: 'play', state: 'default', path: '/play' },
  { screen: 'play', state: 'new-player', path: '/play', setup: api => api.json(/^\/api\/play\/preview/, newPlayer) },
  { screen: 'play', state: 'one-game', path: '/play', setup: api => {
      api.json('/api/play/history', oneGameHistory)
      api.json('/api/play/patterns', oneGamePatterns)
    } },
  { screen: 'play', state: 'six-games', path: '/play', setup: api => {
      api.json('/api/play/history', data.practiceHistory)
      api.json('/api/play/patterns', data.practicePatterns)
    } },
  { screen: 'practice-review', state: 'default', path: `/play/review/${data.PRACTICE_GAME_ID}` },
  { screen: 'ask', state: 'default', path: '/ask' },
  { screen: 'insights', state: 'default', path: '/insights' },
  { screen: 'pattern-report', state: 'default', path: '/insights/patterns', setup: withReport(reports.pattern) },
  { screen: 'pattern-report', state: 'long-names', path: '/insights/patterns', setup: withReport(longNames) },
  { screen: 'pattern-report', state: 'old-shape', path: '/insights/patterns', setup: withReport(oldShapePattern) },
  { screen: 'pattern-report', state: 'none', path: '/insights/patterns', setup: withReport(null) },
  { screen: 'training-plan', state: 'one-done', path: '/insights/training', setup: withPlan(reports.trainingPlan) },
  { screen: 'training-plan', state: 'none-done', path: '/insights/training', setup: withPlan(plan({})) },
  { screen: 'training-plan', state: 'all-done', path: '/insights/training', setup: withPlan(plan({
      '1': { done_at: '2026-10-17T10:00:00Z', solved: 2, total: 3 },
      '2': { done_at: '2026-10-19T18:30:00Z', solved: 1, total: 2 } })) },
  { screen: 'training-plan', state: 'old-shape', path: '/insights/training', setup: withPlan(oldShapePlan) },
  { screen: 'training-plan', state: 'none', path: '/insights/training', setup: withPlan(null) },
  { screen: 'drills', state: 'empty', path: '/drills' },
  { screen: 'drills', state: 'plan-session', path: `/drills?positions=${GAME}:36,${GAME}:27&session=2&plan=t1`,
    setup: api => api.json(/^\/api\/drills/, [drill('d1', 'Rxf8', 36), drill('d2', 'exd5', 27)]) },
  { screen: 'drill-session', state: 'default', path: `/session/${SESSION}`, setup: api => {
      api.json(`/api/sessions/${SESSION}`, { id: SESSION, cards_total: 2, cards_completed: 0, budget_minutes: 12,
        completed: false, started_at: '2026-08-22T09:00:00Z', completed_at: null })
      api.json(`/api/sessions/${SESSION}/next`, card)
    } },
  { screen: 'settings', state: 'default', path: '/settings' },
  { screen: 'settings', state: 'first-run', path: '/settings', setup: api => { api.json('/api/setup/status', fresh); api.json('/api/settings/player', data.playerNone) } },
]

/** Loads a state: fixed clock, the shared overrides, then the state's own. */
export async function open(page: Page, api: ApiMock, s: ParityState) {
  await page.clock.setFixedTime(new Date(NOW))
  base(api)
  s.setup?.(api)
  await page.goto(s.path)
  await page.waitForLoadState('networkidle')
  // The pages settle on a second query (the setup status, the sync banner).
  await page.waitForTimeout(600)
}

// ── Text data ──────────────────────────────────────────────────────────────

/** Words that are data even though no fixture holds them: states, markers, calendar. */
const STATE_WORDS = [
  'few', 'too few', 'done', 'verified', 'passed', 'queued', 'downloading', 'the rest', 'no cause found',
  'unclear', 'win', 'loss', 'draw', 'blunder', 'blunders', 'mistake', 'inaccuracy', 'needs analysis', 'this laptop',
  'pending', 'failed', 'analysed', 'analyzed', 'white', 'black', 'opening', 'middlegame', 'endgame',
  'fork', 'pin', 'skewer', 'hanging piece', 'discovered attack', 'back rank', 'positional', 'got mated',
  'ignored a threat', 'not set yet', 'recorded today', 'daily', 'before re-analysis', 'white to move', 'black to move', 'off',
  'jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec',
  'mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun', 'morning', 'afternoon', 'evening', 'night',
]

/**
 * Text data: names the fixtures hold (openings, causes, tactics, models, labels)
 * plus STATE_WORDS. Long strings are sentences (instructions, explanations), so
 * they are left out: only names of up to 8 words. Anything with a digit (a move,
 * a score) is a data token already.
 */
export function vocabulary(): string[] {
  const out = new Set<string>(STATE_WORDS)
  const skip = (s: string) =>
    /^[0-9a-f-]{36}$/i.test(s) || /^\d{4}-\d\d-\d\d/.test(s) || s.includes('/') || s.includes('{') ||
    /^[A-Z_]+$/.test(s) || /\d/.test(s) || s.split(/\s+/).length > 8 || s.length < 3 || !/\p{L}/u.test(s)
  const walk = (v: unknown, depth: number) => {
    if (depth > 8 || v == null) return
    if (typeof v === 'string') { if (!skip(v)) out.add(v); return }
    if (Array.isArray(v)) { v.forEach(x => walk(x, depth + 1)); return }
    if (typeof v === 'object') Object.values(v as object).forEach(x => walk(x, depth + 1))
  }
  walk(data, 0)
  walk(reports, 0)
  walk([ai, models, LONG_OPENING], 0)
  return [...out]
}
