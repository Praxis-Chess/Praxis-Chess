/**
 * Explore with sample games (POLISH_PLAN.md U7).
 *
 * A first-run player has nothing to look at until their games are synced and
 * analysed, which takes an hour. Sample mode shows the whole app on a sample
 * library instead: games played by Stockfish at club strength under a made-up
 * name, analysed by the real pipeline, every API response captured once into
 * `library.json` by `scripts/sample/capture.py`. Nothing here is invented by the
 * browser: it replays what the backend said about those games.
 *
 * While it is on, every `/api` read is answered from the library and every
 * write is refused, so nothing the player does here touches their own data.
 * The choice lives in this browser only.
 */

const KEY = 'praxis.sample'

export interface SampleLibrary {
  /** When the library was captured, and from how many games. */
  captured_at: string
  games: number
  /** GET responses by path and query, exactly as requested ("/api/games?limit=20"). */
  responses: Record<string, unknown>
  /** Questions Ask Prax was asked at capture, each to the run id its answer is stored under. */
  asks?: Record<string, string>
}

// Present only once a library has been captured; the build works without it.
const LIBRARY = import.meta.glob<{ default: SampleLibrary }>('./library.json')

/** Whether this build carries a sample library at all. */
export const sampleAvailable = Object.keys(LIBRARY).length > 0

export function sampleOn(): boolean {
  try { return sampleAvailable && localStorage.getItem(KEY) === '1' } catch { return false }
}

/** Into the sample, starting from Today. A reload, so no cached data of the player's own survives. */
export function enterSample() {
  try { localStorage.setItem(KEY, '1') } catch { /* private window: stays off */ }
  window.location.assign('/')
}

export function exitSample() {
  try { localStorage.removeItem(KEY) } catch { /* nothing to undo */ }
  window.location.assign('/')
}

const json = (body: unknown, status = 200) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/**
 * Answers `/api` from the library. A read the library does not hold is a 404,
 * as an empty server would say; a write is a 403 that names why.
 */
export async function installSampleFetch(): Promise<void> {
  if (!sampleOn()) return
  const lib = (await Object.values(LIBRARY)[0]()).default
  const real = window.fetch.bind(window)
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, window.location.origin)
    if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/')) return real(input, init)
    const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase()
    // Ask Prax: a question asked at capture gets its recorded answer; any other is refused, saying why.
    if (method === 'POST' && url.pathname === '/api/prax/ask/stream') {
      let question = ''
      try { question = (JSON.parse(String(init?.body ?? '{}')) as { question?: string }).question?.trim() ?? '' } catch { /* not JSON */ }
      const run = lib.asks?.[question]
      if (run) return json({ run_id: run }, 202)
    }
    if (method !== 'GET') return json({ error: 'Sample games are read-only. Exit the sample to use your own games.' }, 403)
    const exact = lib.responses[url.pathname + url.search]
    if (exact !== undefined) return exact === null ? json(null) : json(exact)
    const path = lib.responses[url.pathname]
    if (path !== undefined) return json(path)
    if (import.meta.env.DEV) console.debug('[sample] not in the library:', url.pathname + url.search)
    return json({ error: 'Not in the sample library' }, 404)
  }
}
