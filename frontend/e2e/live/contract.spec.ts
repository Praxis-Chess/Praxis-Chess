import { test, expect } from '@playwright/test'

/**
 * Wire-format contract tests. Tagged @live — excluded from the default run.
 *
 *   npm run test:e2e:live      (requires the Spring backend on :8086)
 *
 * WHY THIS FILE EXISTS
 *
 * The mocked suite cannot catch a serialisation mismatch. A mock is written from
 * the same assumption the frontend already holds, so when the server starts
 * disagreeing, both sides of the mocked test move together and stay green.
 *
 * That is exactly how the /session/undefined/move bug survived: the frontend
 * read `session_id`, the server sent `sessionId` (Jackson's SNAKE_CASE strategy
 * does not apply to Map keys), and nothing in the app compared the two.
 *
 * These tests talk to the real server and assert the KEYS. They are deliberately
 * shallow — no values, no game logic. Shape only.
 */

const API = process.env.E2E_API_URL ?? 'http://localhost:8086/api'

/** Every payload the app consumes is snake_case. One camelCase key is a bug. */
function expectSnakeCase(obj: Record<string, unknown>, where: string) {
  const camel = Object.keys(obj).filter(k => /[a-z][A-Z]/.test(k))
  expect(camel, `${where} returned camelCase keys — the frontend reads snake_case`).toEqual([])
}

test.describe('@live backend contract', () => {
  test('the backend is reachable', async ({ request }) => {
    const res = await request.get(`${API}/play/status`)
    expect(res.ok(), `no backend on ${API} — start it from IntelliJ first`).toBeTruthy()
  })

  test('sync status is snake_case', async ({ request }) => {
    const body = await (await request.get(`${API}/sync/status`)).json()
    expectSnakeCase(body, '/sync/status')
    expect(body).toHaveProperty('games_analyzed')
  })

  test('analysis progress is snake_case', async ({ request }) => {
    const body = await (await request.get(`${API}/analysis/progress`)).json()
    expectSnakeCase(body, '/analysis/progress')
    expect(body).toHaveProperty('percent_complete')
  })

  test('practice streak is snake_case', async ({ request }) => {
    const body = await (await request.get(`${API}/practice/streak`)).json()
    expectSnakeCase(body, '/practice/streak')
    expect(body).toHaveProperty('practice_days')
    // The server owns "today" — the browser must never compute it.
    expect(body).toHaveProperty('today')
  })

  test('opponent preview is snake_case', async ({ request }) => {
    const body = await (await request.get(`${API}/play/preview`)).json()
    expectSnakeCase(body, '/play/preview')
    expect(body).toHaveProperty('skill_level')
  })

  /**
   * THE REGRESSION. This is the test that would have caught the bug.
   *
   * Creates a real session, asserts the id arrives under `session_id`, then
   * abandons it so the probe leaves nothing behind.
   */
  test('a play session returns session_id, not sessionId', async ({ request }) => {
    const res = await request.post(`${API}/play/session`, { data: { color: 'white' } })
    test.skip(res.status() === 503, 'Stockfish not configured on this machine')
    expect(res.ok()).toBeTruthy()

    const body = await res.json()

    try {
      expect(
        body,
        'the controller is serialising a Map — Jackson does not apply SNAKE_CASE to Map keys',
      ).toHaveProperty('session_id')
      expect(body).not.toHaveProperty('sessionId')
      expectSnakeCase(body, '/play/session')

      expect(body).toHaveProperty('player_color')
      expect(body).toHaveProperty('san_moves')
      expect(body).toHaveProperty('skill_level')
      expect(String(body.session_id)).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
      )
    } finally {
      const id = body.session_id ?? body.sessionId
      if (id) await request.post(`${API}/play/session/${id}/abandon`)
    }
  })

  test('games list is snake_case', async ({ request }) => {
    const body = await (await request.get(`${API}/games`)).json()
    expect(Array.isArray(body)).toBeTruthy()
    if (body.length === 0) test.skip(true, 'no games synced on this machine')

    expectSnakeCase(body[0], '/games[0]')
    expect(body[0]).toHaveProperty('analysis_status')

    // Guards the games.source NULL regression, where a filter that did not
    // tolerate NULL made the API report one game instead of a hundred.
    expect(body.length, 'suspiciously few games — check the source filter').toBeGreaterThan(1)
  })
})
