import { test, expect } from '../fixtures/test'
import { PlayPage } from '../pages/PlayPage'
import { GameReviewPage } from '../pages/GameReviewPage'
import * as data from '../fixtures/data'

test.describe('Practice so far', () => {
  test('shows a pattern with how often it recurred, and what to do now', async ({ page, api }) => {
    api.json('/api/play/history', data.practiceHistory)
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    const castling = play.main.getByRole('listitem', { name: 'You castle late' })
    await expect(castling).toContainText('4 of 6 games')
    await expect(castling).toContainText('Castle inside the first ten moves')
  })

  /**
   * Why it costs, the full corrective principle and the finding sentence are one
   * tap away for the whole section (PLAY_PLAN.md, P3): the count and the
   * imperative carry the action; the reasoning is there for whoever wants it.
   */
  test('the finding and why it costs are one tap away, for the whole section', async ({ page, api }) => {
    api.json('/api/play/history', data.practiceHistory)
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.main.getByText(/An uncastled king stays on the file/)).toHaveCount(0)
    await play.main.getByRole('button', { name: 'Details' }).click()
    await expect(play.main.getByText(/An uncastled king stays on the file/)).toBeVisible()
    await expect(play.main.getByText(/You castled after move 10 in 4 of 6 games/)).toBeVisible()
  })

  /**
   * A drill needs a position whose best move IS the lesson. That is true of a
   * motif and false of castling. The drill is the pattern's own positions (I7),
   * never a random set: Drills reads positions, not a motif.
   */
  test('drills the motif pattern\'s own positions, and offers nothing for the habit', async ({ page, api }) => {
    api.json('/api/play/history', data.practiceHistory)
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    const drills = play.main.getByRole('link', { name: /^Drill (this position|these \d+ positions)$/ })
    await expect(drills).toHaveCount(1)
    await expect(drills).toHaveAttribute('href', `/drills?positions=${encodeURIComponent(`${data.PRACTICE_GAME_ID}:14`)}`)
  })

  test('hedges near the sample floor rather than stating a tendency flatly', async ({ page, api }) => {
    api.json('/api/play/history', data.practiceHistory)
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.main.getByText('Early signal').first()).toBeVisible()
  })

  /**
   * Every practice game is aimed at the player's weakest opening, so a habit and
   * an unfamiliar opening look alike from here. Said once, as "aimed at": the
   * opponent forces only its first moves.
   */
  test('says every game was aimed at one opening, and that games are untimed', async ({ page, api }) => {
    api.json('/api/play/history', data.practiceHistory)
    api.json('/api/play/patterns', data.practicePatterns)

    const play = new PlayPage(page)
    await play.open()

    await expect(play.historyPanel).toContainText('All aimed at the Nimzovich-Larsen Attack')
    await expect(play.historyPanel).toContainText('Untimed: no clock')
    // Said once, so no column repeats it on every row.
    await expect(play.historyPanel.getByRole('columnheader', { name: 'Aimed at' })).toHaveCount(0)
  })

  test('below the threshold it shows progress toward it, and claims nothing', async ({ page, api }) => {
    api.json('/api/play/history', data.practiceHistory.slice(0, 1))
    api.json('/api/play/patterns', {
      games_considered: 1, claimable: false, caveat: null, opening_caveat: null,
      patterns: [], not_measured: [], min_games: 5, aimed_at: null,
    })

    const play = new PlayPage(page)
    await play.open()

    await expect(play.historyPanel).toContainText('1 of 5 games · patterns from 5')
    await expect(play.main.getByText(/here is what they show/i)).toHaveCount(0)
  })

  test('each game shows its accuracy and what was flagged, as the review counts it', async ({ page, api }) => {
    api.json('/api/play/history', data.practiceHistory)

    const play = new PlayPage(page)
    await play.open()

    const lost = play.historyPanel.getByRole('row', { name: /^Lost/ }).first()
    await expect(lost).toContainText('28 Aug')
    await expect(lost).toContainText('80%')
    await expect(lost).toContainText('1 mistake')
    await expect(play.historyPanel.getByRole('row', { name: /^Won/ })).toContainText('1 blunder · 2 mistakes · 1 inaccuracy')
  })

  test('stays out of the way entirely when there are no games', async ({ page }) => {
    const play = new PlayPage(page)
    await play.open()

    await expect(play.historyPanel).toHaveCount(0)
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
