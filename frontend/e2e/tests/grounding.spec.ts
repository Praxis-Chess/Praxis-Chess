import { test, expect } from '../fixtures/test'

/**
 * The grounding invariant, seen from the UI.
 *
 * A substantive factual answer may only ship if evidence entered the run
 * through a trusted mechanism. The backend enforces that and has its own tests;
 * what is asserted here is the half the backend cannot see — that a refusal
 * still LOOKS like a refusal by the time it reaches the screen, and that a
 * web-sourced answer arrives with its sources attached and countable.
 *
 * This exists because of a real failure: asked about a player it had no data
 * on, Prax produced a fluent biography of a person who does not exist. Nothing
 * in the UI distinguished that from a grounded answer.
 */

const RUN = 'cc33dd44-ee55-4f66-a077-112233445566'
const THREAD = 'dd44ee55-ff66-4077-b188-223344556677'

function reply(over: Record<string, unknown>) {
  return {
    answer: '',
    findings: [],
    evidence: [],
    steps: [],
    partial: false,
    sources: [],
    lane: 'GENERAL',
    grounding: 'REFUSED',
    artifacts: [],
    conversation_id: THREAD,
    ...over,
  }
}

function mockRun(api: { set: Function; json: Function }, body: Record<string, unknown>) {
  api.set('/api/prax/ask/stream', { status: 202, body: { run_id: RUN } })
  api.json(`/api/prax/run/${RUN}`, {
    run_id: RUN, status: 'DONE', steps: body.steps, artifacts: body.artifacts ?? [],
    answer: body, error: null, elapsed_ms: 900,
  })
}

async function ask(page: import('@playwright/test').Page, q: string) {
  await page.getByPlaceholder(/Ask about your games/i).fill(q)
  await page.getByRole('button', { name: /^Ask$/ }).click()
}

test.describe('Grounding', () => {
  test('a refusal is shown as a refusal, with nothing dressed up as a source', async ({ page, api }) => {
    mockRun(api, reply({
      answer: "I don't have anything on that, and web search is not reachable right now.",
      grounding: 'REFUSED',
    }))

    await page.goto('/ask')
    await ask(page, 'Who is Pal Benyamin Larsen?')

    await expect(page.getByText(/I don't have anything on that/)).toBeVisible()
    // The tell of the original bug: a confident-looking answer carrying a
    // source count it never earned.
    await expect(page.getByText(/web sources?$/)).toHaveCount(0)
    await expect(page.getByText(/Based on \d+ of your games/)).toHaveCount(0)
  })

  test('a web-sourced answer arrives with its sources listed and counted', async ({ page, api }) => {
    mockRun(api, reply({
      answer: 'The Nimzowitsch–Larsen Attack opens 1.b3.',
      lane: 'GENERAL',
      grounding: 'ESCALATED_TO_WEB',
      sources: [
        { title: 'Nimzowitsch–Larsen Attack', url: 'https://en.wikipedia.org/wiki/X', domain: 'en.wikipedia.org' },
        { title: 'Larsen opening theory', url: 'https://www.chessprogramming.org/Y', domain: 'chessprogramming.org' },
      ],
    }))

    await page.goto('/ask')
    await ask(page, 'What is the Nimzowitsch-Larsen Attack?')

    await expect(page.getByText('2 web sources')).toBeVisible()
    // The domain, not the raw URL: the user judges a source by where it came
    // from, and a 90-character link is unreadable in a narrow card.
    await expect(page.getByText(/en\.wikipedia\.org/)).toBeVisible()
    await expect(page.getByText(/chessprogramming\.org/)).toBeVisible()
    // Each source is a real link out, opened safely.
    const link = page.getByRole('link', { name: /Nimzowitsch/ })
    await expect(link).toHaveAttribute('href', 'https://en.wikipedia.org/wiki/X')
    await expect(link).toHaveAttribute('rel', /noopener/)
  })

  test('one source is singular', async ({ page, api }) => {
    mockRun(api, reply({
      answer: 'It opens 1.b3.',
      grounding: 'ESCALATED_TO_WEB',
      sources: [{ title: 'Only one', url: 'https://en.wikipedia.org/wiki/X', domain: 'en.wikipedia.org' }],
    }))

    await page.goto('/ask')
    await ask(page, 'What is 1.b3?')
    await expect(page.getByText('1 web source')).toBeVisible()
  })

  test('a grounded answer about the player cites games, not the web', async ({ page, api }) => {
    mockRun(api, reply({
      answer: 'You lose material in the middlegame more than anywhere else.',
      lane: 'PLAYER',
      grounding: 'GROUNDED',
      steps: [{ tool: 'get_player_profile', sample_size: 101 }],
    }))

    await page.goto('/ask')
    await ask(page, 'What is my weakness?')

    await expect(page.getByText(/Based on 101 of your games/)).toBeVisible()
    await expect(page.getByText(/web sources?/)).toHaveCount(0)
  })

  test('a partial answer says so rather than presenting itself as complete', async ({ page, api }) => {
    mockRun(api, reply({
      answer: 'Your middlegame is the weak spot.',
      lane: 'PLAYER',
      grounding: 'GROUNDED',
      partial: true,
      steps: [{ tool: 'get_player_profile', sample_size: 12 }],
    }))

    await page.goto('/ask')
    await ask(page, 'What is my weakness?')

    await expect(page.getByText('Your middlegame is the weak spot.')).toBeVisible()
    // The sample size is the caveat: 12 games is not 101, and the number has to
    // reach the screen for the reader to weigh the claim.
    await expect(page.getByText(/Based on 12 of your games/)).toBeVisible()
  })
})
