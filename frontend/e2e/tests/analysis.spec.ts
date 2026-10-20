import type { Page } from '@playwright/test'
import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import * as data from '../fixtures/data'

/**
 * The sync/analysis toolbar and Prax's reaction to a run.
 *
 * These assert the STATE MACHINE, not the animation. Under reducedMotion the
 * background draws one still frame, so anything asserting on motion would be
 * testing nothing at all. What matters is that the app reports the run correctly and
 * that Prax's semantic state follows it.
 */

test.describe('Analysis lifecycle', () => {
  test('an idle, up-to-date library shows no banner; Settings keeps every action', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/')
    await expect(page.getByLabel('Library status')).toHaveCount(0)

    await app.goto('/settings')
    const library = page.getByRole('region', { name: 'Library', exact: true })
    await expect(library).toContainText('101 analysed')
    await expect(library.getByRole('button', { name: /sync now/i })).toBeEnabled()
    await expect(library.getByRole('button', { name: /re-sync/i })).toBeEnabled()
    await expect(library.getByRole('button', { name: /re-analyse all/i })).toBeEnabled()
  })

  /** D2: idle news is a ghost chip in the nav, never a banner or a solid button. */
  test('new or pending games are nav chips, with only those actions', async ({ page, api }) => {
    api.json('/api/sync/new-count', { count: 8 })
    api.json('/api/sync/status', { ...data.syncStatus, games_pending: 3 })
    const app = new AppPage(page)
    await app.goto('/')

    await expect(page.getByLabel('Library status')).toHaveCount(0)
    const sync = app.nav.getByRole('button', { name: 'Sync 8 new games from Chess.com' })
    await expect(sync).toContainText('8 new · Sync')
    await expect(sync).not.toHaveClass(/solid/)
    await expect(app.nav.getByRole('button', { name: 'Analyse 3 waiting games' })).toContainText('3 waiting · Analyse')
    await expect(page.locator('button.solid')).toHaveCount(1)
  })

  test('a failed sync is an alert under the nav, with a way to try again', async ({ page, api }) => {
    api.json('/api/sync/new-count', { count: 8 })
    api.fail('/api/sync', 502, 'Chess.com did not answer')
    const app = new AppPage(page)
    await app.goto('/')
    await app.nav.getByRole('button', { name: /^Sync 8 new games/ }).click()

    const alert = page.getByRole('alert').filter({ hasText: "Couldn't sync with Chess.com" })
    await expect(alert).toBeVisible()
    await expect(alert.getByRole('button', { name: 'Try again' })).toBeVisible()
  })

  test('re-analyzing everything asks first', async ({ page, api }) => {
    const app = new AppPage(page)
    await app.goto('/settings')
    const library = page.getByRole('region', { name: 'Library', exact: true })
    await library.getByRole('button', { name: /re-analyse all/i }).click()
    await library.getByRole('button', { name: 'Cancel' }).click()
    expect(api.requestsMatching(/\/api\/analysis\/reanalyze/)).toHaveLength(0)
  })

  test('shows real counts while a run is in flight', async ({ page, api }) => {
    api.json('/api/analysis/progress', data.analysisRunning)

    const app = new AppPage(page)
    await app.goto('/')

    // The counts must come from the payload, not from an indeterminate spinner.
    const banner = page.getByLabel('Library status')
    await expect(banner.getByText(/Analysing 47 \/ 101 games/)).toBeVisible()
    await expect(banner.getByRole('button', { name: 'Stop analysis' })).toBeVisible()
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
  // Skipped while the Display setting is hidden (BACKGROUND_SETTING = false): no style can be drawn. Returns with it.
  test.skip('a chosen background is on every page, the old body on none', async ({ page }) => {
    const app = new AppPage(page)
    await page.addInitScript(() => localStorage.setItem('praxis.background', JSON.stringify({ style: 'contours', motion: 'reacts' })))
    for (const path of ['/', '/progress', '/library', '/insights', '/settings', '/play']) {
      await app.goto(path)
      await expect(app.background, `background on ${path}`).toHaveCount(1)
      await expect(page.locator('canvas'), `one canvas on ${path}`).toHaveCount(1)
    }
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
