import { test, expect } from '../fixtures/test'
import { GameReviewPage } from '../pages/GameReviewPage'
import { PlayPage } from '../pages/PlayPage'
import * as data from '../fixtures/data'

test.describe('Full analysis of a practice game', () => {
  /**
   * The distinction the whole view exists for: /api/analysis/{id} returns only
   * flagged moves, so a page built on it can only ever be a mistake list. This
   * asserts the unflagged moves are on screen too — if the review is ever
   * rewired to the analysis endpoint, only this fails.
   */
  test('lists every move, not only the flagged ones', async ({ page }) => {
    const review = new GameReviewPage(page)
    await review.open(data.PRACTICE_GAME_ID)

    await expect(review.heading).toBeVisible()

    await expect(review.move('b3')).toBeVisible()
    await expect(review.move('e5')).toBeVisible()
    await expect(review.move('Bb2')).toBeVisible()
    // The one flagged move carries its severity symbol.
    await expect(review.move('Nc6?')).toBeVisible()
  })

  test('counts the flagged moves against the player, not both sides', async ({ page }) => {
    const review = new GameReviewPage(page)
    await review.open(data.PRACTICE_GAME_ID)

    // Two of the four half-moves are the player's; one of those is flagged.
    await expect(review.summary).toContainText('2 of your moves')
    await expect(review.summary).toContainText('1 mistake')
  })

  test('explains a flagged move and names the engine’s choice', async ({ page }) => {
    const review = new GameReviewPage(page)
    await review.open(data.PRACTICE_GAME_ID)

    await review.move('Nc6?').click()

    await expect(review.main).toContainText('blocks the c-pawn')
    await expect(review.main).toContainText('d7d5')
  })

  /**
   * An unflagged move must not read as an unexamined one. The engine evaluated
   * every position; silence here means "cost less than an inaccuracy", and the
   * page has to say which kind of empty it is.
   */
  test('says why an unflagged move has no commentary', async ({ page }) => {
    const review = new GameReviewPage(page)
    await review.open(data.PRACTICE_GAME_ID)

    await review.move('e5').click()

    await expect(review.unflaggedNotice).toBeVisible()
  })

  test('marks opponent moves as outside the analysis', async ({ page }) => {
    const review = new GameReviewPage(page)
    await review.open(data.PRACTICE_GAME_ID)

    await review.move('b3').click()

    await expect(review.opponentMoveNotice).toBeVisible()
  })

  test('steps through the game with the arrow buttons', async ({ page }) => {
    const review = new GameReviewPage(page)
    await review.open(data.PRACTICE_GAME_ID)

    await expect(review.previousButton).toBeDisabled()

    await review.nextButton.click()
    await expect(review.main).toContainText('Position before 1... e5')
  })
})

test.describe('Practice history', () => {
  test('lists finished games and hides sessions that were never played', async ({ page, api }) => {
    api.json('/api/play/history', data.practiceHistory)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.historyPanel).toBeVisible()
    // Three FINISHED rows; the IN_PROGRESS and ABANDONED rows are not history.
    await expect(play.main.getByText('Nimzovich-Larsen Attack')).toHaveCount(4) // 3 rows + opponent card
    await expect(play.main.getByText('Won')).toBeVisible()
    await expect(play.main.getByText('Lost')).toHaveCount(2)
  })

  /**
   * An archived game is not a measured one. Offering Review here would open a
   * review with no findings, which reads as "you played perfectly".
   */
  test('offers Review only for games the engine actually measured', async ({ page, api }) => {
    api.json('/api/play/history', data.practiceHistory)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.reviewButtons).toHaveCount(2)
    await expect(play.main.getByText('not analysed')).toHaveCount(1)
  })

  test('a history Review opens that game, not the most recent one', async ({ page, api }) => {
    api.json('/api/play/history', data.practiceHistory)

    const play = new PlayPage(page)
    await play.open()

    await play.reviewButtons.first().click()
    await expect(page).toHaveURL(new RegExp(`/play/review/${data.PRACTICE_GAME_ID}$`))
  })
})

test.describe('Analysis progress', () => {
  /**
   * The bar is determinate only when the server sent counts. Those counts are
   * the practice game's own — /api/analysis/progress tracks the library run and
   * would report a different job's numbers.
   */
  test('shows the stage and its counted total', async ({ page }) => {
    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    await play.resignButton.click()

    await expect(play.analysingNotice).toBeVisible()
    await expect(play.main.getByText('reading position 12 / 78')).toBeVisible()

    const bar = play.analysisProgressBar
    await expect(bar).toHaveAttribute('aria-valuenow', '12')
    await expect(bar).toHaveAttribute('aria-valuemax', '78')
  })

  test('stays indeterminate when the server sends no counts', async ({ page, api }) => {
    // progress: null is what a game queued behind another analysis reports.
    api.set(/^\/api\/play\/report\//, {
      status: 202,
      body: { ...data.pendingReport, progress: null },
    })

    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    await play.resignButton.click()

    await expect(play.main.getByText('waiting for the engine')).toBeVisible()
    await expect(play.analysisProgressBar).not.toHaveAttribute('aria-valuenow', /.*/)
  })
})

test.describe('Reaching the full analysis from Play & Improve', () => {
  /**
   * The button must be absent while the report is still pending. game_id is set
   * at archive time, before the engine has measured anything, so a link gated on
   * the id alone would open an empty review — the same class of bug as the 202
   * that returned 200.
   */
  test('offers no analysis link while the game is still being measured', async ({ page }) => {
    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    await play.resignButton.click()

    await expect(play.analysingNotice).toBeVisible()
    await expect(page.getByRole('button', { name: /Show full analysis/i })).toHaveCount(0)
  })

  test('links to the review once the report is analysed', async ({ page, api }) => {
    api.json(/^\/api\/play\/report\//, data.analysedReport)

    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    await play.resignButton.click()

    const link = page.getByRole('button', { name: /Show full analysis/i })
    await expect(link).toBeVisible()

    await link.click()

    // The archived Game id, never the practice-session id — they are different
    // rows and the review endpoint only knows the former.
    await expect(page).toHaveURL(new RegExp(`/play/review/${data.PRACTICE_GAME_ID}$`))
    await expect(new GameReviewPage(page).heading).toBeVisible()
  })
})
