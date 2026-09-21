import { test, expect } from '../fixtures/test'
import { PlayPage } from '../pages/PlayPage'
import { GameReviewPage } from '../pages/GameReviewPage'
import * as data from '../fixtures/data'

test.describe('My playing patterns', () => {
  test('shows a pattern with its finding', async ({ page, api }) => {
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.main.getByRole('heading', { name: 'My playing patterns' })).toBeVisible()
    await expect(play.main.getByText(/You castled after move 10 in 4 of 6 games/)).toBeVisible()
  })

  /**
   * The difference between a diagnostic and a learning feature. A player told
   * only "you castle late" learns nothing unless they already knew why — and
   * one who knew would not need the detector. So these never collapse.
   */
  test('always shows why it costs and what to do, without expanding anything', async ({ page, api }) => {
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.main.getByText(/An uncastled king stays on the file/)).toBeVisible()
    await expect(play.main.getByText(/Aim to castle inside the first ten moves/)).toBeVisible()
  })

  /**
   * A drill needs a position whose best move IS the lesson. That is true of a
   * motif and false of castling — the engine's choice at move 10 is whatever
   * the position demands, which is usually not "castle".
   */
  test('offers a drill for the motif pattern and not for the habit pattern', async ({ page, api }) => {
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.main.getByRole('button', { name: 'Practise this' })).toHaveCount(1)
  })

  test('hedges near the sample floor rather than stating a tendency flatly', async ({ page, api }) => {
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.main.getByText('Early signal').first()).toBeVisible()
    await expect(play.main.getByText(/on this evidence so far/).first()).toBeVisible()
  })

  /**
   * All practice games are the player's weakest opening by construction, so a
   * habit and an unfamiliar opening are indistinguishable from inside this
   * feature. Saying so is the difference between a caveat and a false lesson.
   */
  test('warns that every game is the same opening', async ({ page, api }) => {
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.main.getByText(/may be specific to that opening/)).toBeVisible()
  })

  test('names what it did not measure', async ({ page, api }) => {
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.main.getByText(/practice games are untimed/)).toBeVisible()
  })

  test('below the floor it shows the counts and declines to claim', async ({ page, api }) => {
    api.json('/api/play/patterns', {
      games_considered: 3,
      claimable: false,
      caveat: 'Only 3 analysed practice games so far. Here is what they show, but 5 are needed before calling anything a tendency.',
      opening_caveat: null,
      patterns: [],
      not_measured: [],
    })

    const play = new PlayPage(page)
    await play.open()

    await expect(play.main.getByText(/5 are needed before calling anything a tendency/)).toBeVisible()
  })

  test('stays out of the way entirely when there are no games', async ({ page }) => {
    const play = new PlayPage(page)
    await play.open()

    await expect(play.main.getByRole('heading', { name: 'My playing patterns' })).toHaveCount(0)
  })
})

test.describe('Pattern evidence', () => {
  /**
   * Evidence has to be reachable, not just true. A move number is checkable by
   * someone who can rebuild the position in their head — not the player this is for.
   */
  test('an evidence line opens the review at that move', async ({ page, api }) => {
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await play.main.getByRole('button', { name: /Show the 2 games/ }).click()
    await play.main.getByRole('button', { name: /Castled on move 12/ }).click()

    await expect(page).toHaveURL(new RegExp(`/play/review/${data.PRACTICE_GAME_ID}\\?ply=23$`))
  })

  test('a game-level finding opens the review with no ply', async ({ page, api }) => {
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await play.main.getByRole('button', { name: /Show the 2 games/ }).click()
    await play.main.getByRole('button', { name: /Never castled/ }).click()

    await expect(page).toHaveURL(new RegExp(`/play/review/${data.PRACTICE_GAME_ID}$`))
  })

  /** A ply the game does not contain must open the game, not break the page. */
  test('an unresolvable ply falls back to the start of the game', async ({ page }) => {
    const review = new GameReviewPage(page)
    await page.goto(`/play/review/${data.PRACTICE_GAME_ID}?ply=999`)

    await expect(review.heading).toBeVisible()
    await expect(review.main).toContainText('Position before 1. b3')
  })

  test('a valid ply seeds the board at that move', async ({ page }) => {
    const review = new GameReviewPage(page)
    await page.goto(`/play/review/${data.PRACTICE_GAME_ID}?ply=4`)

    await expect(review.main).toContainText('Position before 2... Nc6')
  })
})
