import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import * as data from '../fixtures/data'
import * as reports from '../fixtures/reports'
import { drill } from '../fixtures/parity/states'

/** U4, boards in lists (docs/design/POLISH_PLAN.md): a position is shown, not only named. */
test.describe('Boards in lists', () => {
  test('Game Analysis opens on the costliest mistake, not the starting position', async ({ page, api }) => {
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [
      { ...data.scholarsMistake, id: 'e1', move_number: 4, move_played: 'd6', severity: 'MISTAKE', win_pct_drop: 12 },
      { ...data.scholarsMistake, id: 'e2', move_number: 6, move_played: 'Nf6', severity: 'BLUNDER', win_pct_drop: 61 },
    ])
    await new AppPage(page).goto(`/games/${data.WHY_GAME_ID}`)
    await expect(page.getByText('Position before move 3.Nf6')).toBeVisible()
    await expect(page.getByText('Select a mistake to see the position')).toHaveCount(0)
  })

  test('the Library\'s drills are a gallery of their positions', async ({ page, api }) => {
    api.json(/^\/api\/drills/, [drill('d1', 'Rxf8', 36), drill('d2', 'exd5', 27)])
    await new AppPage(page).goto('/library')
    await page.getByRole('tab', { name: 'Drills' }).click()
    const tile = page.getByRole('listitem', { name: 'Drill: Rxf8' })
    await expect(tile.locator('[data-board]')).toBeVisible()
    await expect(tile.getByRole('link', { name: 'Drill this' })).toHaveAttribute('href', /\/drills\?positions=/)
  })

  test('a Pattern Report item shows its costliest position', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await new AppPage(page).goto('/insights/patterns')
    const item = page.getByRole('listitem', { name: "Ignoring your opponent's threat" })
    await expect(item.getByRole('img', { name: /23\.Qxc6: the costliest/ })).toBeVisible()
  })

  test('a Training Plan session shows its positions', async ({ page, api }) => {
    api.json('/api/training-plan', reports.trainingPlan)
    api.json('/api/patterns', reports.pattern)
    await new AppPage(page).goto('/insights/training')
    await expect(page.getByRole('listitem', { name: 'Session 1' }).locator('[data-board]')).toHaveCount(3)
  })

  test('a thrown-away win shows the position where it slipped', async ({ page }) => {
    await new AppPage(page).goto('/insights')
    await expect(page.getByRole('img', { name: 'Before 24.f3' })).toBeVisible()
  })

  test('a static board is the right way round: a1 dark, h1 light, White to move from below', async ({ page, api }) => {
    api.json(/^\/api\/drills/, [drill('d1', 'Rxf8', 36)])
    await new AppPage(page).goto('/library')
    await page.getByRole('tab', { name: 'Drills' }).click()
    const cells = page.locator('[data-board="static"]').first().locator('span')
    await expect(cells.nth(56)).toHaveCSS('background-color', 'rgb(74, 67, 64)')     // a1
    await expect(cells.nth(63)).toHaveCSS('background-color', 'rgb(227, 219, 209)')  // h1
    await expect(cells.nth(56)).toContainText('♜')                                    // the rook on a1
  })
})
