import type { Page } from '@playwright/test'
import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import * as data from '../fixtures/data'

/**
 * The sync/analysis toolbar and Prax's reaction to a run.
 *
 * These assert the STATE MACHINE, not the animation. Under reducedMotion the
 * particle loop is frozen, so anything asserting on pixels would be testing
 * nothing at all. What matters is that the app reports the run correctly and
 * that Prax's semantic state follows it.
 */
test.describe('Analysis lifecycle', () => {
  test('an idle, up-to-date library shows no banner; Settings keeps every action', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/')
    await expect(page.getByLabel('Library status')).toHaveCount(0)

    await app.goto('/settings')
    const library = page.getByRole('region', { name: 'Library', exact: true })
    await expect(library).toContainText('101 analyzed')
    await expect(library.getByRole('button', { name: /sync now/i })).toBeEnabled()
    await expect(library.getByRole('button', { name: /re-sync/i })).toBeEnabled()
    await expect(library.getByRole('button', { name: /re-analyze all/i })).toBeEnabled()
  })

  test('the banner appears for new or pending games, with only those actions', async ({ page, api }) => {
    api.json('/api/sync/new-count', { count: 8 })
    api.json('/api/sync/status', { ...data.syncStatus, games_pending: 3 })
    const app = new AppPage(page)
    await app.goto('/')

    const banner = page.getByLabel('Library status')
    await expect(banner).toContainText('8 new games on Chess.com')
    await expect(banner).toContainText('3 games waiting for analysis')
    await expect(banner.getByRole('button', { name: 'Sync Now' })).toBeVisible()
    await expect(banner.getByRole('button', { name: 'Analyze 3', exact: false })).toBeVisible()
    await expect(banner.getByRole('button', { name: /re-analyze all/i })).toHaveCount(0)
  })

  test('re-analyzing everything asks first', async ({ page, api }) => {
    const app = new AppPage(page)
    await app.goto('/settings')
    const library = page.getByRole('region', { name: 'Library', exact: true })
    await library.getByRole('button', { name: /re-analyze all/i }).click()
    await library.getByRole('button', { name: 'Cancel' }).click()
    expect(api.requestsMatching(/\/api\/analysis\/reanalyze/)).toHaveLength(0)
  })

  test('shows real counts while a run is in flight', async ({ page, api }) => {
    api.json('/api/analysis/progress', data.analysisRunning)

    const app = new AppPage(page)
    await app.goto('/')

    // The counts must come from the payload, not from an indeterminate spinner.
    await expect(page.getByText(/Analyzing 47 \/ 101 games/)).toBeVisible()
    await expect(page.getByRole('button', { name: /^Stop$/ })).toBeVisible()
  })

  /**
   * REGRESSION — Prax went dormant on navigation and never came back.
   *
   * NAVIGATION_START drops Prax to `dormant` so a page change reads as an
   * interruption. Nothing ever undid it: ANALYSIS_STARTED is edge-triggered and
   * `analysisWasBusy` is module-scoped, so it fires exactly once per run. The
   * sweep therefore stopped for the rest of the analysis the moment you changed
   * tab.
   *
   * Before the fix this lands on 'dormant'. After it, 'thinking'.
   *
   * Asserted on the runtime's own snapshot rather than on pixels — the motion is
   * frozen under reducedMotion, but the semantic state still transitions.
   */
  test('Prax resumes the run after a tab change', async ({ page, api }) => {
    api.json('/api/analysis/progress', data.analysisRunning)

    const app = new AppPage(page)
    await app.goto('/')

    // Today raises its own focus insight, so Prax legitimately sits in
    // `insight` here rather than `thinking`. Either proves the run registered.
    await expect
      .poll(() => praxState(page), { timeout: 15_000 })
      .toMatch(/thinking|insight/)

    await app.navigateTo('Progress')
    await app.navigateTo('Library')

    await expect
      .poll(() => praxState(page), {
        timeout: 15_000,
        message: 'Prax fell dormant after navigation and never resumed the run',
      })
      .toBe('thinking')
  })

  test('Prax settles once the run finishes', async ({ page, api }) => {
    api.json('/api/analysis/progress', data.analysisRunning)

    const app = new AppPage(page)
    await app.goto('/')
    await app.navigateTo('Library')

    await expect.poll(() => praxState(page), { timeout: 15_000 }).toBe('thinking')

    api.json('/api/analysis/progress', data.analysisIdle)

    await expect
      .poll(() => praxState(page), { timeout: 20_000 })
      .not.toBe('thinking')
  })
})

test.describe('Where Prax appears', () => {
  test('its body shows on Today, Progress and Library only', async ({ page }) => {
    const app = new AppPage(page)
    for (const path of ['/', '/progress', '/library']) {
      await app.goto(path)
      await expect(app.praxContainer, `Prax on ${path}`).toBeVisible()
    }
    for (const path of ['/insights', '/settings']) {
      await app.goto(path)
      await expect(app.praxContainer, `no Prax on ${path}`).toBeHidden()
    }
  })

  test('the body survives a page that hides it', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/library')
    await app.navigateTo('Insights')
    await expect(app.praxContainer).toBeHidden()
    await app.navigateTo('Library')
    await expect(app.praxContainer).toBeVisible()
    await expect(page.locator('canvas')).toHaveCount(1)
  })
})

/**
 * The dev-only debug handle PraxCanvas installs on window, available because the
 * suite runs against `vite dev` (import.meta.env.DEV). Reading it is far more
 * stable than inferring state from a frozen canvas.
 */
function praxState(page: Page): Promise<string | null> {
  return page.evaluate(() => {
    const prax = (window as unknown as Record<string, any>).__prax
    return prax ? prax.runtime.getSnapshot().state : null
  })
}
