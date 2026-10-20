import { test, expect } from '../fixtures/test'

/**
 * Interaction polish (POLISH_PLAN.md U8): every control answers hover and
 * press, in 120–180 ms, in neutral washes (pink is the next action and the
 * active state, never a hover), and reduced motion turns movement off.
 */
const seconds = (d: string) => d.split(',').map(x => parseFloat(x) * (x.trim().endsWith('ms') ? 0.001 : 1))
const ACCENT = /231,\s*166,\s*214/

test.describe('Interaction (U8)', () => {
  // The suite runs with reduced motion (playwright.config.ts); these tests ask for motion unless they test its absence.
  test.beforeEach(async ({ page }) => { await page.emulateMedia({ reducedMotion: 'no-preference' }) })
  test('controls move in 120–180 ms', async ({ page }) => {
    await page.goto('/insights')
    const button = page.getByRole('button').first()
    const durations = seconds(await button.evaluate(e => getComputedStyle(e).transitionDuration))
    expect(durations.length).toBeGreaterThan(0)
    for (const d of durations) expect(d).toBeGreaterThanOrEqual(0.12), expect(d).toBeLessThanOrEqual(0.18)
  })

  test('a pressed button dips, and a hovered row lifts in a neutral wash', async ({ page }) => {
    await page.goto('/insights')
    const row = page.getByRole('row', { name: /Sicilian Defense/ })
    await row.hover()
    await expect.poll(() => row.evaluate(e => getComputedStyle(e).backgroundColor)).not.toBe('rgba(0, 0, 0, 0)')
    expect(await row.evaluate(e => getComputedStyle(e).backgroundColor)).not.toMatch(ACCENT)

    const chip = page.getByRole('group', { name: 'Time control' }).getByRole('button', { name: 'All' })
    await chip.scrollIntoViewIfNeeded()
    const box = (await chip.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await expect.poll(() => chip.evaluate(e => getComputedStyle(e).transform)).toBe('matrix(1, 0, 0, 1, 0, 1)')
    await page.mouse.up()
  })

  test('a row that is one link lifts on hover', async ({ page }) => {
    await page.goto('/insights')
    const thrown = page.locator('a.row-link').first()
    await thrown.hover()
    await expect.poll(() => thrown.evaluate(e => getComputedStyle(e).backgroundColor)).not.toBe('rgba(0, 0, 0, 0)')
    expect(await thrown.evaluate(e => getComputedStyle(e).backgroundColor)).not.toMatch(ACCENT)
  })

  test('reduced motion: no transition, no press travel, a still spinner', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/insights')
    const button = page.getByRole('button').first()
    for (const d of seconds(await button.evaluate(e => getComputedStyle(e).transitionDuration))) expect(d).toBe(0)

    const chip = page.getByRole('group', { name: 'Time control' }).getByRole('button', { name: 'All' })
    await chip.scrollIntoViewIfNeeded()
    const box = (await chip.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    expect(await chip.evaluate(e => getComputedStyle(e).transform)).toBe('none')
    await page.mouse.up()

    // The loading mark: its still frame, not the loop.
    await page.route('**/api/patterns', () => { /* never answers: the page stays loading */ })
    await page.goto('/insights/patterns')
    const img = page.getByRole('img', { name: 'Loading the report…' })
    await expect(img).toBeVisible()
    expect(await img.evaluate(e => (e as HTMLImageElement).currentSrc)).toMatch(/praxis_balancing_still/)
  })

  // N17: a day still to come is plainly drawn, as a dashed ring.
  test('the week strip draws days still to come', async ({ page }) => {
    await page.clock.setFixedTime(new Date('2026-08-19T10:00:00Z'))   // a Wednesday
    await page.goto('/')
    const upcoming = page.locator('[title$="— upcoming"]').first()
    await expect(upcoming).toBeVisible()
    const dot = upcoming.locator('span')
    expect(await dot.evaluate(e => getComputedStyle(e).borderTopStyle)).toBe('dashed')
    expect(Number(await dot.evaluate(e => getComputedStyle(e).opacity))).toBe(1)
  })
})

// One tab look (components/Tabs.tsx): Library's in-page tabs and Insights' link tabs.
test.describe('Tabs', () => {
  const look = (l: import('@playwright/test').Locator) => l.evaluate(e => {
    const s = getComputedStyle(e)
    return [s.borderBottomStyle, s.borderBottomWidth, s.borderBottomColor, s.color, s.backgroundColor, s.fontWeight, s.fontSize].join(' ')
  })

  test('Library and Insights draw their active tab the same way: an underline, not a pill', async ({ page }) => {
    await page.goto('/library')
    const library = await look(page.getByRole('tab', { name: 'Games' }))
    await page.goto('/insights')
    const insights = await look(page.getByRole('group', { name: 'Insights sections' }).getByRole('link', { name: 'Overview' }))
    expect(insights).toBe(library)
    expect(library).toMatch(/^solid 2px /)
    expect(library).toContain('rgba(0, 0, 0, 0)')
  })

  test('in-page tabs are a tablist: arrow keys move, the panel follows', async ({ page }) => {
    await page.goto('/library')
    const games = page.getByRole('tab', { name: 'Games' })
    await expect(games).toHaveAttribute('aria-selected', 'true')
    await games.focus()
    await page.keyboard.press('ArrowRight')
    const drills = page.getByRole('tab', { name: 'Drills' })
    await expect(drills).toHaveAttribute('aria-selected', 'true')
    await expect(drills).toBeFocused()
    await expect(page.getByRole('tabpanel', { name: 'Drills' })).toBeVisible()
  })
})
