import { test, expect } from '../fixtures/test'
import { PlayPage } from '../pages/PlayPage'
import * as data from '../fixtures/data'

/**
 * No page may scroll sideways in a narrow desktop window.
 *
 * The `narrow` project is a laptop window snapped to half the screen (683px).
 * The Prax card makes the same thing happen on any desktop: while it's open,
 * <main> gives up a 372px strip, so a normal window has ~860px of content.
 *
 * How this was found: phone emulation (since removed — nobody opens a
 * localhost app on a phone) failed four Game Review / Play clicks. Fixed
 * `380px 1fr` and `repeat(5, 1fr)` grids can't shrink below their content, so
 * the page was wider than the window. On a phone that shows up as a widened,
 * zoomed-out layout; in a desktop window it shows up as a horizontal
 * scrollbar and content cut off at the right. Both are this assertion.
 */

async function assertFits(page: import('@playwright/test').Page, where: string) {
  const m = await page.evaluate(() => ({
    window: window.innerWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(m.scroll, `${where}: the page is ${m.scroll}px wide in a ${m.window}px window — something can't shrink`)
    .toBeLessThanOrEqual(m.window + 1)
}

const ROUTES = [
  '/', '/progress', '/library', '/play', '/ask', '/insights',
  '/dashboard', '/games', '/games/7c3c9a2e-1111-4a22-8b33-445566778899',
  '/drills', '/patterns', '/training',
  `/play/review/${data.PRACTICE_GAME_ID}`,
]

for (const route of ROUTES) {
  test(`${route} fits the device width`, async ({ page }) => {
    await page.goto(route)
    await page.getByRole('main').waitFor()
    await page.waitForTimeout(300)
    await assertFits(page, route)
  })
}

// The Play page changes shape as a game progresses; each state is its own layout.
test('Play fits the device during a game and after the report', async ({ page, api }) => {
  api.json(/^\/api\/play\/report\//, data.analysedReport)
  const play = new PlayPage(page)
  await play.open()
  await play.startButton.click()
  await expect(play.resignButton).toBeVisible()
  await assertFits(page, '/play (in game)')

  await play.resignButton.click()
  await expect(page.getByRole('button', { name: /Show full analysis/i })).toBeVisible()
  await assertFits(page, '/play (report)')
})

test('Game Analysis fits the device with mistakes listed', async ({ page, api }) => {
  api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [
    { id: '1', move_number: 12, player_color: 'white', move_played: 'Qc5', better_move: 'e3b3',
      fen_position: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1', severity: 'MISTAKE',
      tactical_motif: 'POSITIONAL', game_phase: 'MIDDLEGAME',
      explanation: 'A deliberately long explanation, so that a column which cannot shrink would show it by pushing the row past the edge of a phone screen.' },
  ])
  await page.goto('/games/7c3c9a2e-1111-4a22-8b33-445566778899')
  await expect(page.getByText(/mistakes? identified/i)).toBeVisible()
  await assertFits(page, '/games/:id (with mistakes)')
})
