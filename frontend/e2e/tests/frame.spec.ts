import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import { PlayPage } from '../pages/PlayPage'

/**
 * U1, the frame (docs/design/POLISH_PLAN.md): page widths, the rail slot, one
 * board theme, bundled fonts. Density floors live in density.spec.ts.
 */

const LIGHT = 'rgb(227, 219, 209)'   // --board-light #E3DBD1
const DARK = 'rgb(74, 67, 64)'       // --board-dark  #4A4340

test.describe('Page widths (D1)', () => {
  test.use({ viewport: { width: 1920, height: 1080 } })

  const width = (page: import('@playwright/test').Page) =>
    page.locator('[data-page]').evaluate(el => ({ kind: (el as HTMLElement).dataset.page, w: Math.round(el.getBoundingClientRect().width), left: Math.round(el.getBoundingClientRect().left), right: Math.round(el.getBoundingClientRect().right) }))

  test('every page is centred on one of three widths', async ({ page }) => {
    const app = new AppPage(page)
    const expected: [string, string, number][] = [
      ['/', 'standard', 1200], ['/progress', 'standard', 1200], ['/library', 'standard', 1200],
      ['/insights', 'standard', 1200], ['/settings', 'standard', 1200], ['/play', 'standard', 1200],
      ['/ask', 'reading', 760],
    ]
    for (const [path, kind, w] of expected) {
      await app.goto(path)
      const box = await width(page)
      expect(box.kind, path).toBe(kind)
      expect(box.w, path).toBe(w)
      // Centred: equal margins to the viewport (one pixel of rounding either way).
      expect(Math.abs(box.left - (1920 - box.right)), `${path} centred`).toBeLessThanOrEqual(1)
    }
  })

  test('Game Analysis and the live game use all of main', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/games/7c3c9a2e-1111-4a22-8b33-445566778899')
    expect((await width(page)).kind).toBe('wide')
    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    expect((await width(page)).kind).toBe('wide')
  })
})

test.describe('The rail slot (D6)', () => {
  test('8 and 4 of 12 columns side by side from 1200px', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/dev/rail')
    const main = (await page.getByTestId('rail-main').boundingBox())!
    const rail = (await page.getByTestId('rail').boundingBox())!
    expect(rail.x).toBeGreaterThan(main.x + main.width)
    expect(main.width / rail.width).toBeGreaterThan(1.8)
    expect(main.width / rail.width).toBeLessThan(2.2)
  })

  test('stacked under the content below 1200px', async ({ page }) => {
    await page.setViewportSize({ width: 900, height: 900 })
    await page.goto('/dev/rail')
    const main = (await page.getByTestId('rail-main').boundingBox())!
    const rail = (await page.getByTestId('rail').boundingBox())!
    expect(rail.y).toBeGreaterThan(main.y + main.height - 1)
  })
})

test.describe('One board theme (N5)', () => {
  test('the live game is bone and graphite, like every other board', async ({ page }) => {
    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    await expect(page.locator('[data-board] [data-square="e2"]').first()).toHaveCSS('background-color', LIGHT)
    await expect(page.locator('[data-board] [data-square="e1"]').first()).toHaveCSS('background-color', DARK)
    // N6: the panel says what it is with a micro-label, as before the game.
    await expect(page.getByText('This game is pressing')).toHaveCount(0)
    await expect(page.locator('.micro-label', { hasText: 'This game' })).toBeVisible()
  })
})

test.describe('Fonts (D5)', () => {
  test('Inter Tight and JetBrains Mono load from the app itself, no font CDN', async ({ page }) => {
    const external: string[] = []
    page.on('request', r => {
      const u = new URL(r.url())
      if (r.resourceType() === 'font' && u.hostname !== 'localhost' && u.hostname !== '127.0.0.1') external.push(r.url())
      if (/fonts\.(googleapis|gstatic)\.com/.test(u.hostname)) external.push(r.url())
    })
    await new AppPage(page).goto('/insights')
    await page.evaluate(() => document.fonts.ready)
    expect(await page.evaluate(() => document.fonts.check('600 16px "Inter Tight"'))).toBe(true)
    expect(await page.evaluate(() => document.fonts.check('400 13px "JetBrains Mono"'))).toBe(true)
    expect(external).toEqual([])
  })
})
