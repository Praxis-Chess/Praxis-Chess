/**
 * Captures the sample library (POLISH_PLAN.md U7): every /api read the app
 * makes, answered by a backend running on the sample database, into
 * frontend/src/sample/library.json. Sample mode replays it in the browser.
 *
 * The app is served by the usual dev server; its /api requests are sent to the
 * sample backend instead and recorded. Then the per-game reads (each game, its
 * review, its mistakes, each mistake's "Why?") are fetched directly, and the
 * questions Ask Prax offers from the sample's report are asked once each.
 *
 *   node scripts/sample/capture.mjs --app http://localhost:5174 --api http://localhost:8087
 */
import { createRequire } from 'module'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontend = path.join(here, '../../frontend')
const { chromium } = createRequire(path.join(frontend, 'package.json'))('@playwright/test')

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d }
const APP = arg('app', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8087')
const OUT = path.join(frontend, 'src/sample/library.json')

const responses = {}
const keep = (key, body) => { responses[key] = body }
// The host machine stays out of the sample: no backup files, no install paths.
const SCRUB = { '/api/backups': [] }

async function get(p) {
  const r = await fetch(API + p)
  if (r.status === 204) return null
  if (!r.ok) return undefined
  return r.json()
}

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.route('**/api/**', async route => {
  const u = new URL(route.request().url())
  // Only the backend: the app's own modules live under /src/api/ too.
  if (!u.pathname.startsWith('/api/')) return route.continue()
  const key = u.pathname + u.search
  if (route.request().method() !== 'GET') return route.fulfill({ status: 403, json: { error: 'capture is read-only' } })
  const r = await fetch(API + key)
  const text = await r.text()
  if (r.ok) keep(key, r.status === 204 || !text ? null : JSON.parse(text))
  await route.fulfill({ status: r.status, body: text, headers: { 'content-type': 'application/json' } })
})

const visit = async (p, clicks = []) => {
  await page.goto(APP + p)
  await page.waitForLoadState('networkidle')
  for (const name of clicks) {
    const el = page.getByRole('tab', { name }).or(page.getByRole('link', { name, exact: true })).first()
    if (await el.count()) { await el.click(); await page.waitForLoadState('networkidle') }
  }
}

for (const p of ['/', '/progress', '/library', '/play', '/ask', '/insights', '/insights/patterns', '/insights/training', '/drills', '/settings']) await visit(p)
await visit('/library', ['Drills'])

const games = (await get('/api/games')) ?? []
for (const g of games) {
  await visit(`/games/${g.id}`)
  for (const k of [`/api/games/${g.id}`, `/api/games/${g.id}/review`, `/api/analysis/${g.id}`]) {
    const b = await get(k); if (b !== undefined) keep(k, b)
  }
  for (const m of (await get(`/api/analysis/${g.id}`)) ?? []) {
    const k = `/api/diagnosis/why/${g.id}/${m.move_number}`
    const b = await get(k); if (b !== undefined) keep(k, b)
  }
}

// Ask Prax: the questions its empty state offers from this report, asked once.
const report = await get('/api/patterns')
const f = report?.facts
const questions = []
if (f?.top_causes?.[0]) {
  const t = f.top_causes[0].title.replace(/\byour\b/g, 'my')
  questions.push(/ing$/.test(t.split(' ')[0]) ? `Why do I keep ${t[0].toLowerCase()}${t.slice(1)}?` : `How do I stop this: ${t.toLowerCase()}?`)
}
if (f?.top_tactics?.[0]) questions.push(`Show me the ${f.top_tactics[0].title.toLowerCase()} I missed`)
if (f?.openings?.[0]?.name) questions.push(`What goes wrong in my ${f.openings[0].name}?`)
if (f?.worst_phase) questions.push(`Why do I make the most mistakes in the ${f.worst_phase.toLowerCase()}?`)
// A re-capture keeps the answers already recorded rather than asking the model again.
const previous = fs.existsSync(OUT) ? JSON.parse(fs.readFileSync(OUT, 'utf8')) : null
const asks = {}
for (const [q, run] of Object.entries(previous?.asks ?? {})) {
  if (!questions.includes(q)) continue
  asks[q] = run; keep(`/api/prax/run/${run}`, previous.responses[`/api/prax/run/${run}`])
}
for (const [i, question] of questions.entries()) {
  if (asks[question]) continue
  const started = await fetch(API + '/api/prax/ask/stream', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ question, conversationId: null }),
  }).then(r => r.json())
  for (let n = 0; n < 400; n++) {
    await new Promise(r => setTimeout(r, 1500))
    const view = await get(`/api/prax/run/${started.run_id}`)
    if (view?.status === 'DONE' || view?.status === 'FAILED') {
      if (view.status === 'DONE') { asks[question] = `sample-${i}`; keep(`/api/prax/run/sample-${i}`, view) }
      console.log(`asked: ${question} -> ${view.status}`)
      break
    }
  }
}
// The conversations those questions made, as Recent questions.
const conversations = (await get('/api/prax/conversations')) ?? []
keep('/api/prax/conversations', conversations)
for (const c of conversations) {
  const b = await get(`/api/prax/conversations/${c.id}`); if (b !== undefined) keep(`/api/prax/conversations/${c.id}`, b)
}

await browser.close()
Object.assign(responses, SCRUB)
fs.writeFileSync(OUT, JSON.stringify({
  captured_at: new Date().toISOString(), games: games.length, asks, responses,
}))
console.log(`wrote ${OUT}: ${Object.keys(responses).length} responses, ${games.length} games`)
