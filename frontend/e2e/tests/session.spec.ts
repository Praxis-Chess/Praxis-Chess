import { test, expect } from '../fixtures/test'

/**
 * The drill session — the route with the most state in the app and, until now,
 * no coverage at all. It has a card queue, a reveal step, a four-way rating,
 * a progress counter and a completion screen, and every one of those is a
 * place where a card can be recorded twice, skipped, or lost.
 */

const SESSION = '9c1f0b7a-3d4e-4a21-8f60-1b2c3d4e5f60'

const card = (id: string, over: Partial<Record<string, unknown>> = {}) => ({
  id,
  fen_position: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4',
  move_played: 'Ng5',
  better_move: 'd2d3',
  severity: 'MISTAKE',
  tactical_motif: 'FORK',
  game_phase: 'OPENING',
  player_color: 'white',
  explanation: 'The knight had nothing to attack there.',
  status: 'REVIEW',
  interval_days: 1,
  due_date: '2026-08-22',
  review_count: 2,
  lapse_count: 0,
  ...over,
})

const session = (over: Partial<Record<string, unknown>> = {}) => ({
  id: SESSION,
  cards_total: 2,
  cards_completed: 0,
  budget_minutes: 12,
  completed: false,
  started_at: '2026-08-22T09:00:00Z',
  completed_at: null,
  ...over,
})

test.describe('Drill session', () => {
  test('shows a card and hides the answer until it is asked for', async ({ page, api }) => {
    api.json(`/api/sessions/${SESSION}`, session())
    api.json(`/api/sessions/${SESSION}/next`, card('c1'))

    await page.goto(`/session/${SESSION}`)

    // The prompt names the move that was played, because the drill is about
    // YOUR mistake — not a generic puzzle.
    await expect(page.getByText('Ng5')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Show answer' })).toBeVisible()

    // Revealing before the user asks would destroy the retrieval practice the
    // whole scheduler exists to schedule.
    await expect(page.getByText('d2d3')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Good' })).toHaveCount(0)
  })

  test('revealing shows the better move and the four ratings', async ({ page, api }) => {
    api.json(`/api/sessions/${SESSION}`, session())
    api.json(`/api/sessions/${SESSION}/next`, card('c1'))

    await page.goto(`/session/${SESSION}`)
    await page.getByRole('button', { name: 'Show answer' }).click()

    await expect(page.getByText('d2d3')).toBeVisible()
    await expect(page.getByText('The knight had nothing to attack there.')).toBeVisible()
    for (const r of ['Again', 'Hard', 'Good', 'Easy']) {
      await expect(page.getByRole('button', { name: r })).toBeVisible()
    }
  })

  test('a rating records the attempt against the card that was shown', async ({ page, api }) => {
    api.json(`/api/sessions/${SESSION}`, session())
    api.json(`/api/sessions/${SESSION}/next`, card('c1'))

    const posted: unknown[] = []
    api.set(`/api/sessions/${SESSION}/attempt`, (req: { postData(): string | null }) => {
      posted.push(JSON.parse(req.postData() ?? '{}'))
      return { body: session({ cards_completed: 1 }) }
    })

    await page.goto(`/session/${SESSION}`)
    await page.getByRole('button', { name: 'Show answer' }).click()
    await page.getByRole('button', { name: 'Good' }).click()

    await expect.poll(() => posted.length).toBe(1)
    const body = posted[0] as Record<string, unknown>
    // card_id, not an index: the queue is server-owned and the client must not
    // guess which card it just answered.
    expect(body.card_id).toBe('c1')
    expect(body.rating).toBe('GOOD')
    // Revealed answers are not credited as recalled.
    expect(body.correct).toBe(false)
  })

  test('the counter reflects the server, not a local tally', async ({ page, api }) => {
    api.json(`/api/sessions/${SESSION}`, session({ cards_completed: 3, cards_total: 8 }))
    api.json(`/api/sessions/${SESSION}/next`, card('c1'))

    await page.goto(`/session/${SESSION}`)
    await expect(page.getByText('3 / 8')).toBeVisible()
  })

  test('an exhausted queue ends the session instead of hanging on a blank board', async ({ page, api }) => {
    api.json(`/api/sessions/${SESSION}`, session({ cards_completed: 2, completed: true }))
    api.json(`/api/sessions/${SESSION}/next`, null)

    await page.goto(`/session/${SESSION}`)

    await expect(page.getByRole('button', { name: /Back to Today/i })).toBeVisible()
  })

  test('a failed attempt is reported, not swallowed', async ({ page, api }) => {
    api.json(`/api/sessions/${SESSION}`, session())
    api.json(`/api/sessions/${SESSION}/next`, card('c1'))
    api.set(`/api/sessions/${SESSION}/attempt`, { status: 500, body: { error: 'nope' } })

    await page.goto(`/session/${SESSION}`)
    await page.getByRole('button', { name: 'Show answer' }).click()
    await page.getByRole('button', { name: 'Good' }).click()

    // Silently dropping the rating would lose the review and quietly corrupt
    // the schedule. Two things must hold: the failure is SHOWN, and the
    // ratings stay usable so the attempt can be made again.
    //
    // The text is asserted loosely on purpose — the message is currently the
    // raw `API error 500: …` from the fetch wrapper, which is worth improving,
    // and pinning that string here would make the improvement look like a
    // regression.
    await expect(page.getByText(/error|failed|could not/i).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Good' })).toBeEnabled()
  })
})
