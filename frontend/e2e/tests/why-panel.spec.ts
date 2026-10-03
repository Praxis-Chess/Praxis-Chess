import { test, expect } from '../fixtures/test'
import * as data from '../fixtures/data'

/**
 * Game Analysis — the "Why?" panel (plan §17.2, D3).
 *
 * The contracts: nothing is fetched until the player asks (the first view of a
 * mistake costs engine time), the explanation shown is the verified one, and
 * each step puts the position that shows it on the page's board.
 */
const WHY = new RegExp(`/api/diagnosis/why/${data.WHY_GAME_ID}/6$`)

test.describe('Why? panel', () => {
  test.beforeEach(async ({ api }) => {
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [data.scholarsMistake])
    api.json(WHY, data.scholarsWhy)
  })

  async function openWhy(page: import('@playwright/test').Page) {
    await page.goto(`/games/${data.WHY_GAME_ID}`)
    await page.getByText('3.Nf6??').click()
    await page.getByRole('button', { name: 'Why?' }).click()
    return page.getByRole('region', { name: 'Why this was a mistake' })
  }

  test('is offered only for the selected mistake, and fetches nothing until opened', async ({ page, api }) => {
    await page.goto(`/games/${data.WHY_GAME_ID}`)
    await expect(page.getByText(/mistakes? identified/i)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Why?' })).toHaveCount(0)

    await page.getByText('3.Nf6??').click()
    await expect(page.getByRole('button', { name: 'Why?' })).toBeVisible()
    expect(api.requestsMatching(WHY)).toHaveLength(0)
  })

  test('opened at a move (?ply=), that mistake is selected and named', async ({ page }) => {
    await page.goto(`/games/${data.WHY_GAME_ID}?ply=6&from=thrown`)

    await expect(page.getByRole('status').filter({ hasText: 'The win slipped at 3...Nf6' })).toBeVisible()
    // Selected: its board caption and its own "Why?" button are showing.
    await expect(page.getByText('Position before move 3.Nf6')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Why?' })).toBeVisible()
  })

  test('shows the verified cause and its steps', async ({ page }) => {
    const why = await openWhy(page)

    await expect(why).toContainText('Got mated')
    await expect(why).toContainText('Ignored a threat')
    await expect(why).toContainText('White already threatened Qxf7#')
    await expect(why.getByRole('list', { name: 'Steps' }).getByRole('listitem')).toHaveCount(6)
    await expect(why).toContainText('Checked against the engine’s analysis')
    await expect(why).toContainText('rules, not AI')
  })

  test('a step puts its position on the board, and the board can go back', async ({ page }) => {
    const why = await openWhy(page)

    const outcome = why.getByRole('button', { name: /Mate in 1/ })
    await outcome.click()
    await expect(outcome).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByLabel('Board caption')).toContainText('After Qxf7#, where it costs you')
    // The mating queen is on f7 — the board really changed.
    await expect(page.locator('[data-square="f7"] [data-piece="wQ"]')).toBeVisible()

    await page.getByRole('button', { name: 'Back to the position' }).click()
    await expect(page.getByLabel('Board caption')).toHaveCount(0)
    await expect(page.getByText('Position before move 3.Nf6')).toBeVisible()
    await expect(page.locator('[data-square="h5"] [data-piece="wQ"]')).toBeVisible()
  })

  test('clicking the active step again goes back to the position', async ({ page }) => {
    const why = await openWhy(page)
    const threat = why.getByRole('button', { name: /already threatened/ })

    await threat.click()
    await expect(page.getByLabel('Board caption')).toContainText('Before your move')
    await threat.click()
    await expect(threat).toHaveAttribute('aria-pressed', 'false')
    await expect(page.getByLabel('Board caption')).toHaveCount(0)
  })

  test('says so when its engine move differs from the one stored with the game', async ({ page, api }) => {
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [{ ...data.scholarsMistake, better_move: 'd7d6' }])
    const why = await openWhy(page)
    await expect(why).toContainText('This check prefers g6; the game’s analysis listed d7d6')
  })

  test('says nothing about it when they agree', async ({ page }) => {
    const why = await openWhy(page)
    await expect(why).toContainText('White already threatened')
    await expect(why).not.toContainText('This check prefers')
  })

  test('a composite mistake says no single cause is named', async ({ page, api }) => {
    api.json(WHY, {
      ...data.scholarsWhy,
      diagnosis: { ...data.scholarsWhy.diagnosis, mechanism: 'UNCLEAR', composite: true },
    })
    const why = await openWhy(page)
    await expect(why).toContainText('No single cause')
  })

  test('an explanation that failed its check is flagged', async ({ page, api }) => {
    api.json(WHY, { ...data.scholarsWhy, diagnosis: { ...data.scholarsWhy.diagnosis, verified: false } })
    const why = await openWhy(page)
    await expect(why).toContainText('did not pass the check')
  })

  test('a failure is reported, not left spinning', async ({ page, api }) => {
    api.set(WHY, { status: 404 })
    const why = await openWhy(page)
    await expect(why.getByRole('alert')).toContainText('Couldn’t work out why')
  })

  test('choosing another mistake resets the board', async ({ page, api }) => {
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [
      data.scholarsMistake,
      { ...data.scholarsMistake, id: 'e2222222-2222-2222-2222-222222222222', move_number: 20, move_played: 'Qe7' },
    ])
    const why = await openWhy(page)
    await why.getByRole('button', { name: /Mate in 1/ }).click()
    await expect(page.getByLabel('Board caption')).toBeVisible()

    await page.getByText('10.Qe7??').click()
    await expect(page.getByLabel('Board caption')).toHaveCount(0)
    await expect(page.getByRole('region', { name: 'Why this was a mistake' })).toHaveCount(0)
  })
})

/**
 * Phase 9: the rules won the pre-registered comparison, so a mistake's card leads
 * with their verified diagnosis — on every mistake, not only the three per game
 * the LLM wrote up — and the LLM's text, where one exists, follows as commentary.
 */
test.describe('Mistake card (Phase 9)', () => {
  test('leads with the verified diagnosis, and keeps the AI text as commentary', async ({ page, api }) => {
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [data.scholarsMistakeVerified])
    await page.goto(`/games/${data.WHY_GAME_ID}`)

    const verified = page.getByLabel('Verified diagnosis').first()
    await expect(verified).toContainText('✓ Verified')
    await expect(verified).toContainText('Got mated')
    await expect(verified).toContainText('Ignored a threat')
    await expect(verified).toContainText('Qxf7# was already threatened')
    await expect(page.getByText('AI commentary:').first()).toBeVisible()
    await expect(page.getByText('Nf6 leaves f7 to the queen.').first()).toBeVisible()
  })

  test('a verified mistake is never shown as "not written up"', async ({ page, api }) => {
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [{
      ...data.scholarsMistakeVerified, explanation: null, analysis_state: 'SKIPPED',
    }])
    await page.goto(`/games/${data.WHY_GAME_ID}`)

    await expect(page.getByLabel('Verified diagnosis').first()).toContainText('Qxf7# was already threatened')
    await expect(page.getByText('not among the mistakes written up')).toHaveCount(0)
  })

  test('the trained model’s checked commentary replaces the old AI text (Phase 9b)', async ({ page, api }) => {
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [{
      ...data.scholarsMistakeVerified,
      verified: { ...data.scholarsMistakeVerified.verified,
        commentary: 'Nf6 leaves the mate on f7 standing; g6 would have blocked the queen.',
        commentary_model: 'praxis-grid-2b-r3' },
    }])
    await page.goto(`/games/${data.WHY_GAME_ID}`)

    const commentary = page.getByLabel('AI commentary').first()
    await expect(commentary).toContainText('checked')
    await expect(commentary).toContainText('g6 would have blocked the queen')
    await expect(page.getByText('Nf6 leaves f7 to the queen.')).toHaveCount(0)
  })

  test('without a verified diagnosis, the card is as before', async ({ page, api }) => {
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [data.scholarsMistake])
    await page.goto(`/games/${data.WHY_GAME_ID}`)

    await expect(page.getByLabel('Verified diagnosis')).toHaveCount(0)
    await expect(page.getByText('Nf6 leaves f7 to the queen.').first()).toBeVisible()
    await expect(page.getByText('AI commentary:')).toHaveCount(0)
  })
})
