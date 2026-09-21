import { test, expect } from '../fixtures/test'
import { PlayPage } from '../pages/PlayPage'
import * as data from '../fixtures/data'

/**
 * The Prax column floats over the page at z-index 60 and its cards are
 * pointer-events:auto, so anywhere it lands it takes clicks away from whatever
 * is underneath. On desktop it sits in the empty gutter beside <main> and that
 * is fine. On a phone there is no gutter, and the card was landing mid-page —
 * silently eating the Resign button, which looked like a dead button rather
 * than like an overlay.
 *
 * These assert the invariant directly — no element that the user can click is
 * covered by the Prax column — rather than asserting a specific position, so
 * they keep holding if the placement rule is tuned again.
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
  { name: 'phone', width: 375, height: 812 },
  { name: 'desktop', width: 1280, height: 800 },
]) {
  test(`the Prax card covers no clickable control on ${viewport.name}`, async ({ page, api }) => {
    // A running analysis is what makes the progress card appear at all.
    api.json('/api/analysis/progress', data.analysisRunning)

    await page.setViewportSize(viewport)
    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    await expect(play.resignButton).toBeVisible()

    // Let the follow-Prax rAF loop settle on a final position.
    await page.waitForTimeout(600)

    expect(await controlsCovered(page)).toEqual([])
  })
}

test('the card docks to the bottom edge on a phone', async ({ page, api }) => {
  api.json('/api/analysis/progress', data.analysisRunning)
  await page.setViewportSize({ width: 375, height: 812 })

  const play = new PlayPage(page)
  await play.open()
  await play.startButton.click()
  await page.waitForTimeout(600)

  const box = await page.locator('div[style*="z-index: 60"], div[style*="zIndex: 60"]').first().boundingBox()
  expect(box, 'the Prax column never rendered').not.toBeNull()
  // Within the edge clearance of the floor — a predictable strip, not a
  // position that drifts with the organism.
  expect(812 - (box!.y + box!.height)).toBeLessThanOrEqual(20)
})
