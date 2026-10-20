import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import * as data from '../fixtures/data'

/**
 * U2, truthful data (docs/design/POLISH_PLAN.md): what a figure says must be what
 * was measured. The backend half (the deck rule, openings at sync) is covered by
 * CardGeneratorServiceTest and OpeningFromPgnTest.
 */

test.describe('Today: what is due today (N1, D8)', () => {
  test('shows the server\'s count of what is due today, not every card in the deck', async ({ page, api }) => {
    api.json('/api/progress', { ...data.progress, deck_summary: { ...data.progress.deck_summary, due_today: 14 } })
    await new AppPage(page).goto('/')
    const due = page.locator('.card', { hasText: 'Due today' })
    await expect(due).toContainText('14')
    await expect(due).not.toContainText('146')
  })

  test('counts are neutral: amber is mistake severity, green a result (N16)', async ({ page }) => {
    await new AppPage(page).goto('/')
    for (const label of ['Learning', 'Review', 'New']) {
      const value = page.locator('.card', { hasText: label }).locator('div').first()
      await expect(value).toHaveCSS('color', 'rgb(242, 237, 234)')
    }
  })
})

test.describe('Progress: no data is not failure (N2)', () => {
  test.use({ timezoneId: 'UTC' })

  test('no reviews in the last 7 days reads as a dash, not 0% in red', async ({ page, api }) => {
    await page.clock.setFixedTime(new Date('2026-10-16T12:00:00Z'))
    api.json('/api/progress', { ...data.progress, history: data.progress.history.map(d => ({ ...d, date: '2026-09-01' })) })
    await new AppPage(page).goto('/progress')
    await expect(page.getByText('No reviews in the last 7 days')).toBeVisible()
    await expect(page.getByText('0%', { exact: true })).toHaveCount(0)
  })

  test('recent reviews still give a 7-day recall', async ({ page, api }) => {
    await page.clock.setFixedTime(new Date('2026-08-21T12:00:00Z'))
    api.json('/api/progress', data.progress)
    await new AppPage(page).goto('/progress')
    // 24 of 34 reviews correct on 19 and 20 Aug.
    await expect(page.getByText('71%', { exact: true })).toBeVisible()
  })
})

test.describe('Insights: a win rate from few games is marked (N10)', () => {
  test('a band with fewer than 5 games is faded and says few', async ({ page, api }) => {
    api.json('/api/insights', { ...data.insights, opponent_strength: [
      ...data.insights.opponent_strength.slice(0, 2),
      { bucket: 'Above 1400', games: 1, wins: 1, win_pct: 100, avg_accuracy: 86.9 },
    ] })
    await new AppPage(page).goto('/insights')
    await expect(page.getByText(/100% · 1g · 86\.9% acc · few/)).toBeVisible()
    await expect(page.getByText(/44\.8% · 58g · 67\.4% acc$/)).toBeVisible()
  })
})

test.describe('One date format (N11, N18)', () => {
  test('the Library dates a game "19 Aug 2026", not by the locale', async ({ page }) => {
    await new AppPage(page).goto('/library')
    await expect(page.getByText(/\d{1,2} Aug 2026/).first()).toBeVisible()
    await expect(page.getByText(/8\/19\/2026/)).toHaveCount(0)
  })
})
