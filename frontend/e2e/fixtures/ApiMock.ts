import type { Page, Request, Route } from '@playwright/test'
import * as data from './data'

export interface Reply {
  status?: number
  /** Serialised as JSON. Omit entirely for a 204. */
  body?: unknown
  /** Simulates a slow endpoint, for loading-state assertions. */
  delayMs?: number
}

type Matcher = string | RegExp
type Handler = Reply | ((req: Request) => Reply | Promise<Reply>)

interface Rule {
  matcher: Matcher
  handler: Handler
}

/**
 * Intercepts every /api/** call in the browser.
 *
 * Interception happens before the request leaves Chromium, so Vite's proxy is
 * never reached and the Spring backend does not need to be running at all.
 *
 * Two properties worth preserving if you change this:
 *
 *  1. Later rules win. Defaults are registered first; a test overrides one by
 *     calling set() again for the same path.
 *
 *  2. An unmatched /api/ call is a LOUD failure (501 + recorded), never a silent
 *     pass-through. A mock suite that quietly lets real requests escape is worse
 *     than no mock suite — it passes on one machine and fails on another.
 */
export class ApiMock {
  private rules: Rule[] = []
  /** Every /api path the page asked for, in order. Assertable. */
  readonly requested: string[] = []
  /** Paths with no matching rule. A non-empty array fails the mocked suite. */
  readonly unmatched: string[] = []

  constructor(private readonly page: Page) {}

  /** Register (or override) a handler. Later registrations take precedence. */
  set(matcher: Matcher, handler: Handler): this {
    this.rules.push({ matcher, handler })
    return this
  }

  /** Convenience for the common case: 200 with a JSON body. */
  json(matcher: Matcher, body: unknown): this {
    return this.set(matcher, { status: 200, body })
  }

  /** Force an endpoint to fail, for error-state tests. */
  fail(matcher: Matcher, status = 500, message = 'mocked failure'): this {
    return this.set(matcher, { status, body: { error: message } })
  }

  async install(): Promise<void> {
    // Anchored on the origin, NOT a '**/api/**' glob. The glob matches any URL
    // containing "/api/", which includes Vite's own module request for
    // /src/api/client.ts — so the app's source code was being intercepted and
    // reported as an unmocked endpoint.
    await this.page.route(
      /^https?:\/\/[^/]+\/api\//,
      async (route: Route) => {
        const requestUrl = new URL(route.request().url())
        // Path only; query is matched via regex when a test cares about it.
        const path = requestUrl.pathname + requestUrl.search

        this.requested.push(path)

        // Reverse order so the most recently registered rule wins.
        for (let i = this.rules.length - 1; i >= 0; i--) {
          const { matcher, handler } = this.rules[i]
          if (!matches(matcher, path)) continue

          const reply = typeof handler === 'function' ? await handler(route.request()) : handler
          if (reply.delayMs) await new Promise(r => setTimeout(r, reply.delayMs))

          const status = reply.status ?? 200
          if (status === 204 || reply.body === undefined) {
            await route.fulfill({ status: status === 200 ? 204 : status })
            return
          }
          await route.fulfill({
            status,
            contentType: 'application/json',
            body: JSON.stringify(reply.body),
          })
          return
        }

        this.unmatched.push(path)
        await route.fulfill({
          status: 501,
          contentType: 'application/json',
          body: JSON.stringify({ error: `No ApiMock rule for ${path}` }),
        })
      },
    )
  }

  /**
   * The default world: one analysed player, nothing in flight.
   *
   * Registered first so any test can override a single endpoint without
   * restating the other twenty.
   */
  installDefaults(): this {
    this.json('/api/today', data.todayInsight)
    this.json('/api/practice/streak', data.practiceStreak)
    this.json('/api/sync/status', data.syncStatus)
    this.json('/api/sync/new-count', { count: 0 })
    this.json('/api/analysis/progress', data.analysisIdle)
    this.json('/api/dashboard/stats', data.dashboardStats)
    this.json('/api/dashboard/rating-history', data.dashboardStats.rating_history)
    this.json('/api/games', data.games)
    // The per-game detail route. Registered here rather than per-test because
    // every path that reaches /games/:id needs both halves of it, and the
    // exact-match rule above deliberately does not swallow the id form.
    this.json(/^\/api\/games\/[0-9a-f-]+$/, data.games[0])
    // The whole-game review. The detail rule above is $-anchored and so never
    // reaches this path, but both are kept together to keep that visible.
    this.json(/^\/api\/games\/[0-9a-f-]+\/review$/, data.gameReview)
    this.json(/^\/api\/analysis\/[0-9a-f-]+$/, [])
    this.json('/api/insights', data.insights)
    this.json('/api/progress', data.progress)
    this.json('/api/patterns', null)
    this.json('/api/training-plan', null)
    this.json(/^\/api\/drills/, [])

    // Play & Improve
    this.json('/api/play/status', { available: true })
    this.json(/^\/api\/play\/preview/, data.opponentProfile)
    this.json('/api/play/history', [])
    // Empty by default so the patterns section stays out of unrelated play
    // assertions; tests that want it override with data.practicePatterns.
    this.json('/api/play/patterns', {
      games_considered: 0, claimable: false, caveat: null, opening_caveat: null,
      patterns: [], not_measured: [],
    })
    this.json('/api/play/session', data.playSession)
    this.json(/^\/api\/play\/session\/[^/]+\/resign$/, data.resignResult)
    this.json(/^\/api\/play\/session\/[^/]+\/undo$/, {
      ...data.resignResult, status: 'IN_PROGRESS', result: null, end_reason: null,
    })
    this.json(/^\/api\/play\/session\/[^/]+\/move$/, {
      ...data.resignResult, status: 'IN_PROGRESS', result: null, end_reason: null,
      san_moves: 'e4 e5', fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2',
    })
    // 202 with analysed:false — the real server's "still working" reply. The UI
    // must keep polling rather than render an invented report.
    this.set(/^\/api\/play\/report\//, { status: 202, body: data.pendingReport })

    // Prax answers nothing by default; tests that need it override.
    this.json(/^\/api\/prax/, { answer: '', evidence: [], findings: [], steps: [] })

    // Voice is off under test. Kokoro is a separate local service and speech
    // synthesis has nothing to contribute to a headless assertion.
    this.json('/api/voice/status', { available: false })
    this.set(/^\/api\/voice\/speak/, { status: 503 })

    return this
  }

  /** Every /api path requested so far that matches a pattern. */
  requestsMatching(re: RegExp): string[] {
    return this.requested.filter(p => re.test(p))
  }
}

function matches(matcher: Matcher, path: string): boolean {
  if (typeof matcher === 'string') {
    // Exact, or exact-plus-query — so '/api/games' does not swallow '/api/games/123'.
    return path === matcher || path.startsWith(matcher + '?')
  }
  return matcher.test(path)
}
