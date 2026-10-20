import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import * as data from '../fixtures/data'

/**
 * The Prax column floats over the page at z-index 60 and its cards are
 * pointer-events:auto, so anywhere it lands it takes clicks away from whatever
 * is underneath. On desktop it sits in the empty gutter beside <main> and that
 * is fine. In a narrow window there is no gutter, and the card was landing mid-page —
 * silently eating the Resign button, which looked like a dead button rather
 * than like an overlay.
 *
 * These assert the invariant directly — no element that the user can click is
 * covered by the Prax column — rather than asserting a specific position, so
 * they keep holding if the placement rule is tuned again.
 *
 * The card docks bottom right now that the particle body is retired; on a
 * phone it is a bottom sheet. Run on Library, with an analysis running.
 */

/** Every interactive control the card is actually sitting on top of. */
async function controlsCovered(page: import('@playwright/test').Page) {
  return page.evaluate(() => {
    const hits: string[] = []
    const controls = document.querySelectorAll('main button, main a, main input, main select')
    controls.forEach(el => {
      const r = el.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) return
      if (r.bottom < 0 || r.top > innerHeight) return   // scrolled out of view
      const cx = r.x + r.width / 2
      const cy = r.y + r.height / 2
      const top = document.elementFromPoint(cx, cy)
      if (!top) return
      // Covered if the topmost element at the control's centre is neither the
      // control nor inside it.
      if (top === el || el.contains(top)) return
      hits.push((el.textContent || el.tagName).trim().slice(0, 40))
    })
    return hits
  })
}

for (const viewport of [
  { name: 'a very narrow window', width: 375, height: 812 },
  { name: 'desktop', width: 1280, height: 800 },
]) {
  test(`the Prax card covers no clickable control on ${viewport.name}`, async ({ page, api }) => {
    // A running analysis is what makes the progress card appear at all.
    api.json('/api/analysis/progress', data.analysisRunning)

    await page.setViewportSize(viewport)
    await new AppPage(page).goto('/library')

    // Let the follow-Prax rAF loop settle on a final position.
    await page.waitForTimeout(600)

    expect(await controlsCovered(page)).toEqual([])
  })
}

/**
 * A run's progress card appears only in the margin beside the page. A phone has
 * no margin, so there is no card there at all: the sync banner under the nav
 * already shows the run, and nothing covers the page.
 */
test('no progress card on a phone: the banner carries the run', async ({ page, api }) => {
  api.json('/api/analysis/progress', data.analysisRunning)
  await page.setViewportSize({ width: 375, height: 812 })

  await new AppPage(page).goto('/library')
  await page.waitForTimeout(600)

  await expect(page.locator('div[style*="z-index: 60"]')).toHaveCount(0)
  await expect(page.getByLabel('Library status')).toContainText(/Analysing 47 \/ 101 games/)
})

/** On a wide screen the margin beside the centred page holds it, clear of the page. */
test('the progress card sits in the margin beside the page on a wide screen', async ({ page, api }) => {
  api.json('/api/analysis/progress', data.analysisRunning)
  await page.setViewportSize({ width: 1920, height: 1080 })

  await new AppPage(page).goto('/library')
  await page.waitForTimeout(600)

  const card = (await page.locator('div[style*="z-index: 60"]').first().boundingBox())!
  const pageBox = (await page.locator('[data-page]').boundingBox())!
  expect(card, 'the Prax column never rendered').not.toBeNull()
  expect(card.x).toBeGreaterThanOrEqual(pageBox.x + pageBox.width)
})

test('no Prax column on a page outside PRAX_CARD_ROUTES', async ({ page, api }) => {
  api.json('/api/analysis/progress', data.analysisRunning)
  await page.setViewportSize({ width: 375, height: 812 })
  await new AppPage(page).goto('/play')
  await page.waitForTimeout(600)
  await expect(page.locator('div[style*="z-index: 60"]')).toHaveCount(0)
})
