import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import { PlayPage } from '../pages/PlayPage'
import * as data from '../fixtures/data'

/** U5, Game Analysis and live Play (docs/design/POLISH_PLAN.md: N6, N7). */
test.describe('Game Analysis', () => {
  test.beforeEach(async ({ api }) => {
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [data.scholarsMistakeVerified])
  })

  test('shows the game as a curve and a move list; any move shows its position', async ({ page, api }) => {
    api.json(/^\/api\/games\/[0-9a-f-]+\/review$/, data.scholarsReview)
    await new AppPage(page).goto(`/games/${data.WHY_GAME_ID}`)
    await expect(page.locator('[data-chart="eval"]')).toBeVisible()
    const moves = page.getByRole('list', { name: 'Moves' })
    await expect(moves.getByRole('button', { name: '3...Nf6, blunder' })).toHaveAttribute('aria-pressed', 'true')

    await moves.getByRole('button', { name: '2.Bc4' }).click()
    await expect(page.getByLabel('Board caption')).toContainText('After 2.Bc4')
    await page.getByRole('button', { name: 'Back to the mistake' }).click()
    await expect(page.getByText('Position before move 3.Nf6')).toBeVisible()
  })

  test('without a stored curve it says why, and offers to re-analyse the game', async ({ page, api }) => {
    api.json(/^\/api\/games\/[0-9a-f-]+\/review$/, { ...data.scholarsReview, evals: null })
    api.json(/^\/api\/games\/[0-9a-f-]+\/analyze$/, { message: 'queued' })
    await new AppPage(page).goto(`/games/${data.WHY_GAME_ID}`)
    await expect(page.getByText(/analysed before curves were kept/)).toBeVisible()
    await page.getByRole('button', { name: 'Re-analyse this game' }).click()
    await expect(page.getByText('Re-analysis queued')).toBeVisible()
    expect(api.requestsMatching(/\/api\/games\/[0-9a-f-]+\/analyze$/)).toHaveLength(1)
  })

  test('N7: a mistake card says each thing once', async ({ page, api }) => {
    const POS = 'No material or mate changes hands within the horizon; the difference is positional.'
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [{ ...data.scholarsMistake, tactical_motif: 'POSITIONAL',
      verified: { consequence: 'POSITIONAL', mechanism: 'NONE', motif: 'POSITIONAL', explanation: POS, passed: true,
                  commentary: POS, commentary_model: 'praxis-grid-2b-r3' } }])
    await new AppPage(page).goto(`/games/${data.WHY_GAME_ID}`)
    await expect(page.getByText(/^positional$/i)).toHaveCount(1)
    await expect(page.getByLabel('AI commentary')).toHaveCount(0)
    await expect(page.getByText(POS)).toHaveCount(1)
  })
})

test.describe('Live Play (N6)', () => {
  test('the panel says what the game is, the habit to watch, and the material', async ({ page, api }) => {
    api.json('/api/play/session', { ...data.playSession, san_moves: 'e4 e5 Nf3 Nc6',
      fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3' })
    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    await expect(page.getByText('Watch for')).toBeVisible()
    await expect(page.getByText(/Material\s*even/)).toBeVisible()
  })
})
