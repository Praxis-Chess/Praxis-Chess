import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import * as reports from '../fixtures/reports'

/**
 * U3, Today as a cockpit (docs/design/POLISH_PLAN.md): today's work in the main
 * column, its context in the rail.
 */
test.describe('Today', () => {
  test('the focus shows one of your own positions, the costliest of the top habit', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await new AppPage(page).goto('/')
    const figure = page.getByRole('figure', { name: 'One of your positions' })
    await expect(figure.locator('[data-board]')).toBeVisible()
    await expect(figure).toContainText('23.Qxc6')
    await expect(figure.getByRole('link', { name: 'Open the game' })).toHaveAttribute('href', /\/games\/.+\?ply=45$/)
  })

  test('the rail: rating, last game, next session and the top habit, each one tap from its page', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    api.json('/api/training-plan', reports.trainingPlan)
    await new AppPage(page).goto('/')
    const rail = page.getByRole('complementary', { name: 'Context' })
    await expect(rail.getByRole('region', { name: 'Rating' }).locator('[data-chart]')).toBeVisible()
    await expect(rail.getByRole('region', { name: 'Last game' }).getByRole('link', { name: 'Nimzovich-Larsen Attack' })).toBeVisible()
    await expect(rail.getByRole('region', { name: /^Next · session 2 of 2$/ })).toContainText('Find your own chances first')
    await expect(rail.getByRole('region', { name: 'Top habit' })).toContainText("Ignoring your opponent's threat")
    // Context, never the next action: the page keeps its one solid button.
    await expect(rail.locator('button.solid')).toHaveCount(0)
  })

  test('with no report or plan yet, the rail points to making one and draws no empty frames', async ({ page }) => {
    await new AppPage(page).goto('/')
    const rail = page.getByRole('complementary', { name: 'Context' })
    await expect(rail.getByRole('link', { name: 'Make a plan →' })).toHaveAttribute('href', '/insights/patterns')
    await expect(rail.getByRole('region', { name: 'Top habit' })).toHaveCount(0)
  })

  test('on a small laptop the rail sits beside the work, not under it', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 })
    await new AppPage(page).goto('/')
    const main = (await page.locator('.page-main').boundingBox())!
    const rail = (await page.getByRole('complementary', { name: 'Context' }).boundingBox())!
    expect(rail.x).toBeGreaterThan(main.x + main.width)
  })
})
