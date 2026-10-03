import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import * as data from '../fixtures/data'

test.describe('Today', () => {
  test('renders the insight and offers its evidence', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/')

    await expect(app.main).toContainText(data.todayInsight.title)
    await expect(app.main).toContainText(data.todayInsight.action)
    // A claim must always carry a way to see what it rests on.
    await expect(page.getByRole('button', { name: /Show evidence/i })).toBeVisible()
  })

  // This used to be skipped under phone emulation as a "harness quirk": the
  // click never settled although the button was on top at its own centre. That
  // is the symptom of a page wider than the device — the browser widens the
  // layout viewport and clicks stop landing where they're aimed — which
  // layout-width.spec.ts now guards against. The phone project is gone; this
  // runs everywhere.
  test('evidence is reachable, with its sample size', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/')

    await page.getByRole('button', { name: /Show evidence/i }).click()

    await expect(app.main).toContainText(data.todayInsight.evidence.value)
    // The sample size travels with the claim — a figure without one is exactly
    // what the grounding rules forbid.
    await expect(app.main).toContainText(`${data.todayInsight.evidence.sample_size} games`)
  })

  test('shows the practice streak with the server-supplied day', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/')

    await expect(app.main).toContainText(
      `${data.practiceStreak.current_streak} day streak`,
    )
    // `practiced_today` comes from the SERVER's date, never new Date() — the
    // wording must follow the payload rather than the browser's clock.
    await expect(app.main).toContainText(/Recorded today/i)
  })

  test('handles a player with no history without breaking', async ({ page, api }) => {
    api.json('/api/practice/streak', {
      ...data.practiceStreak,
      current_streak: 0,
      longest_streak: 0,
      total_days_practiced: 0,
      last_practice_date: null,
      practiced_today: false,
      practice_days: [],
    })

    const app = new AppPage(page)
    await app.goto('/')

    await expect(app.main).toBeVisible()
  })
})

test.describe('Progress', () => {
  test('renders the deck summary', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/progress')

    await expect(app.main).toContainText(String(data.progress.deck_summary.total_cards))
  })

  test('renders practice history for every month, not just the current one', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/progress')

    // practice_days spans July and August; both must survive into the strip.
    await expect(app.main).toContainText(/Jul/i)
    await expect(app.main).toContainText(/Aug/i)
  })
})

test.describe('Library', () => {
  test('lists games with their openings', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/library')

    await expect(app.main).toContainText('Nimzovich-Larsen Attack')
    await expect(app.main).toContainText('Sicilian Defense')
  })

  test('"Needs analysis" holds only games that will be analysed', async ({ page, api }) => {
    api.json('/api/games', [
      { ...data.games[2], in_analysis_scope: true },                        // rapid, pending: waiting
      { ...data.games[2], id: 'a1000000-0000-4000-8000-000000000009', opening_name: 'Bullet Scramble',
        time_class: 'bullet', in_analysis_scope: false },                   // bullet: synced on purpose
    ])
    const app = new AppPage(page)
    await app.goto('/library')

    await expect(app.main).toContainText('Needs analysis (1)')
    await expect(page.getByRole('link', { name: /Bullet Scramble/ })).toContainText('not analysed')
  })

  test('renders an empty library without erroring', async ({ page, api, consoleErrors }) => {
    api.json('/api/games', [])

    const app = new AppPage(page)
    await app.goto('/library')

    await expect(app.main).toBeVisible()
    expect(consoleErrors).toEqual([])
  })
})

test.describe('Insights', () => {
  test('renders analytics sections from the payload', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/insights')

    await expect(app.main).toContainText(/accuracy/i)
    // Opponent-strength buckets come straight from the fixture.
    await expect(app.main).toContainText('1200-1400')
  })

  test('draws Elo beside accuracy, on its own axis', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/insights')

    await expect(app.main).toContainText('Accuracy & Elo Trend')
    const chart = page.getByLabel('Accuracy and Elo trend')
    await expect(chart.getByText('Elo', { exact: true })).toBeVisible()
    await expect(chart.getByText('10-game avg', { exact: true })).toBeVisible()
    // The right-hand axis is fitted to the fixture's ratings (1180-1224), not 0-100.
    await expect(chart).toContainText('1250')
    await expect(chart).toContainText('1150')
  })

  test('without ratings, the chart stays the accuracy trend it was', async ({ page, api }) => {
    api.json('/api/insights', { ...data.insights,
      accuracy_trend: data.insights.accuracy_trend.map(p => ({ ...p, rating: null })) })
    const app = new AppPage(page)
    await app.goto('/insights')

    await expect(app.main).toContainText('Accuracy Trend')
    await expect(page.getByLabel('Accuracy and Elo trend').getByText('Elo', { exact: true })).toHaveCount(0)
  })

  test('opens on the analysed time control and switches speeds on request', async ({ page, api }) => {
    const app = new AppPage(page)
    await app.goto('/insights')

    const picker = page.getByRole('group', { name: 'Time control' })
    await expect(picker.getByRole('button', { name: 'Rapid · 113' })).toHaveAttribute('aria-pressed', 'true')
    // The first load leaves the choice to the server (rapid by default).
    expect(api.requested).toContain('/api/insights')

    api.json('/api/insights', { ...data.insights, time_class: null,
      accuracy_trend: data.insights.accuracy_trend.map(p => ({ ...p, rating: null })) })
    await picker.getByRole('button', { name: 'All' }).click()
    await expect(picker.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true')
    expect(api.requested).toContain('/api/insights?time_class=all')
    // Ratings differ per speed, so the mixed view draws no Elo line.
    await expect(page.getByLabel('Accuracy and Elo trend').getByText('Elo', { exact: true })).toHaveCount(0)
  })

  test('a thrown-away win links to the move where it slipped', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/insights')

    const row = page.getByRole('link', { name: /Sicilian Defense/ })
    await expect(row).toContainText('slipped at 24.f3')
    await expect(row).toHaveAttribute('href', '/games/a1000000-0000-4000-8000-000000000002?ply=47&from=thrown')
  })

  test('survives an insights endpoint failure', async ({ page, api }) => {
    api.fail('/api/insights', 500)

    const app = new AppPage(page)
    await app.goto('/insights')

    // The shell must stay usable so the player can navigate away.
    await expect(app.nav).toBeVisible()
    await expect(app.main).toBeVisible()
  })
})
