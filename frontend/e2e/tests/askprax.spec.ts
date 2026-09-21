import { test, expect } from '../fixtures/test'

/**
 * The Ask Prax workspace, and the conversation scoping behind it.
 *
 * The scoping matters more than it looks: before conversations were persisted
 * and scoped, a single global history meant a question asked in one browser tab
 * silently changed the answer given in another.
 */

const THREAD = 'aa11bb22-cc33-4d44-8e55-ff6677889900'
const RUN = 'bb22cc33-dd44-4e55-9f66-001122334455'

function answer(text: string, overrides: Record<string, unknown> = {}) {
  return {
    answer: text,
    findings: [],
    evidence: [],
    steps: [{ tool: 'get_player_profile', sample_size: 101 }],
    partial: false,
    sources: [],
    lane: 'PLAYER',
    grounding: 'GROUNDED',
    artifacts: [],
    conversation_id: THREAD,
    ...overrides,
  }
}

async function ask(page: import('@playwright/test').Page, q: string) {
  await page.getByPlaceholder(/Ask about your games/i).fill(q)
  await page.getByRole('button', { name: /^Ask$/ }).click()
}

/**
 * The workspace starts a RUN and polls it, so a question needs both endpoints
 * mocked. Returns the list of request bodies the client sent.
 */
function mockRun(api: { set: Function; json: Function }, body: Record<string, unknown>) {
  const sent: unknown[] = []
  api.set('/api/prax/ask/stream', (req: { postData(): string | null }) => {
    sent.push(JSON.parse(req.postData() ?? '{}'))
    return { status: 202, body: { run_id: RUN } }
  })
  api.json(`/api/prax/run/${RUN}`, {
    run_id: RUN, status: 'DONE', steps: body.steps, artifacts: body.artifacts ?? [],
    answer: body, error: null, elapsed_ms: 1200,
  })
  return sent
}

test.describe('Ask Prax workspace', () => {
  test('offers starting points before anything has been asked', async ({ page }) => {
    await page.goto('/ask')

    await expect(page.getByRole('heading', { name: 'Ask Prax' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'What is my weakness?' })).toBeVisible()
  })

  test('shows the question and its answer as one exchange', async ({ page, api }) => {
    mockRun(api, answer('Your middlegame is where the rating leaks.'))

    await page.goto('/ask')
    await ask(page, "What's my weakness?")

    await expect(page.getByText("What's my weakness?")).toBeVisible()
    await expect(page.getByText('Your middlegame is where the rating leaks.')).toBeVisible()
    // Depth, not machinery: how many games it rests on, never the function name.
    await expect(page.getByText(/Based on 101 of your games/)).toBeVisible()
    await expect(page.getByText(/get_player_profile/)).toHaveCount(0)
  })

  test('carries the conversation id into the next question', async ({ page, api }) => {
    const sent = mockRun(api, answer('An answer.'))

    await page.goto('/ask')
    await ask(page, 'First question')
    await expect(page.getByText('An answer.')).toBeVisible()
    await ask(page, 'Second question')
    await expect(page.getByText('An answer.').first()).toBeVisible()

    await expect.poll(() => sent.length).toBe(2)
    // The first question opens a thread; the second must continue it, or every
    // follow-up would be answered with no idea what came before.
    expect((sent[0] as { conversationId: string | null }).conversationId).toBeNull()
    expect((sent[1] as { conversationId: string }).conversationId).toBe(THREAD)
  })

  test('New chat drops the thread without deleting it', async ({ page, api }) => {
    const sent = mockRun(api, answer('An answer.'))

    await page.goto('/ask')
    await ask(page, 'First question')
    await expect(page.getByText('An answer.')).toBeVisible()

    await page.getByRole('button', { name: 'New chat' }).click()
    await expect(page.getByText('First question')).toHaveCount(0)

    await ask(page, 'Fresh start')
    await expect.poll(() => sent.length).toBe(2)
    // A new chat forgets the id; it does not delete the old conversation, which
    // stays readable in History.
    expect((sent[1] as { conversationId: string | null }).conversationId).toBeNull()
  })

  test('lists earlier conversations and reopens one', async ({ page, api }) => {
    api.json('/api/prax/conversations', [
      { id: THREAD, title: 'What is my weakness?', last_message_at: '2026-08-21T10:00:00Z' },
    ])
    api.json(`/api/prax/conversations/${THREAD}`, {
      id: THREAD,
      title: 'What is my weakness?',
      messages: [
        { id: 'm1', role: 'user', content: 'What is my weakness?', created_at: null, payload: {} },
        {
          id: 'm2', role: 'assistant', content: 'Middlegame tactics.', created_at: null,
          payload: answer('Middlegame tactics.'),
        },
      ],
    })

    await page.goto('/ask')
    await page.getByRole('button', { name: 'History' }).click()
    // Scoped to the panel: the empty state offers the same text as a SUGGESTION,
    // and clicking that would ask the question rather than reopen the thread.
    await page.getByRole('region', { name: 'Earlier conversations' })
      .getByRole('button', { name: 'What is my weakness?' })
      .click()

    // Reopening must restore what the player SAW, not just the prose — the
    // stored payload carries evidence, sources and artifacts too.
    await expect(page.getByText('Middlegame tactics.')).toBeVisible()
    await expect(page.getByText(/Based on 101 of your games/)).toBeVisible()
  })

  /**
   * The whole reason the progressive path exists.
   *
   * A question takes 15-40s. If nothing appears until the very end, a
   * full-width workspace reads as broken. The board must be on screen while the
   * run is still going — the most useful part of the answer arriving first.
   */
  test('shows a board and a working word while the answer is still being written',
    async ({ page, api }) => {
      let polls = 0
      api.set('/api/prax/ask/stream', { status: 202, body: { run_id: RUN } })
      api.set(`/api/prax/run/${RUN}`, () => {
        polls++
        // First two polls: still working, but a board already exists.
        if (polls < 3) {
          return {
            status: 200,
            body: {
              run_id: RUN, status: 'RUNNING',
              steps: [{ tool: 'find_mistakes', sample_size: 0 },
                      { tool: 'analyze_position', sample_size: 0 }],
              artifacts: [{
                type: 'CHESS_POSITION', id: 'pos-1', title: 'The position when it went wrong',
                fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
                orientation: 'white', highlights: ['e2'],
                arrows: [{ from: 'e2', to: 'e4', role: 'BEST' }],
                caption: null,
              }],
              answer: null, error: null, elapsed_ms: 900,
            },
          }
        }
        return {
          status: 200,
          body: {
            run_id: RUN, status: 'DONE', steps: [], artifacts: [],
            answer: answer('Here is the finished answer.'), error: null, elapsed_ms: 3000,
          },
        }
      })

      await page.goto('/ask')
      await ask(page, "What's my weakness?")

      // Board visible BEFORE any prose exists — the point of the whole
      // progressive path.
      await expect(page.locator('[data-square="e2"]').first()).toBeVisible()
      await expect(page.getByText('Here is the finished answer.')).toHaveCount(0)

      // A word, not a function name. Reading `analyze_position` is not the same
      // as being told something is happening.
      const status = page.locator('main p[style*="italic"]').first()
      await expect(status).toBeVisible()
      await expect(status).not.toContainText('_')
      await expect(page.getByText(/analyze_position/)).toHaveCount(0)

      // Then the prose lands.
      await expect(page.getByText('Here is the finished answer.')).toBeVisible()
    })

  test('stops polling and explains when a run has been evicted', async ({ page, api }) => {
    api.set('/api/prax/ask/stream', { status: 202, body: { run_id: RUN } })
    api.set(`/api/prax/run/${RUN}`, { status: 404, body: { error: 'gone' } })

    await page.goto('/ask')
    await ask(page, 'Anything')

    // A 404 means no answer is coming; polling an id that will never resolve
    // would leave the player waiting forever.
    await expect(page.getByText(/no longer available/i)).toBeVisible()
  })

  test('reports a failed request against the question that caused it', async ({ page, api }) => {
    api.fail('/api/prax/ask/stream', 503)

    await page.goto('/ask')
    await ask(page, 'Anything')

    await expect(page.getByText(/Prax is unavailable \(503\)/)).toBeVisible()
    // The question stays on screen so it can be retried or edited.
    await expect(page.getByText('Anything')).toBeVisible()
  })
})
