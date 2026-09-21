import { test, expect } from '../fixtures/test'

/**
 * Prax must not be drawn on top of the page.
 *
 * `anchorRegistry` falls back to a fixed viewport fraction when a page does not
 * register a `<PraxAnchor>`. Six routes never did — Game Analysis, the game
 * list, Dashboard, Drills, Pattern Report and Training Plan — and the old
 * fallback (0.68/0.46) is roughly the middle of the content column, so the
 * organism rendered over the page. On Game Analysis it sat squarely on the
 * mistake list.
 *
 * The check is a real 2-D intersection, and it took two wrong versions to get
 * there:
 *
 *  1. comparing the anchor's x against the content's right edge ignored the
 *     RADIUS, so it passed with half the organism on the text;
 *  2. comparing x with the radius ignored the Y AXIS, and failed `/ask` and
 *     `/training` over right-aligned header buttons that sit hundreds of pixels
 *     above where Prax actually is.
 *
 * Both earlier versions were confidently green or confidently red about the
 * wrong thing.
 */

/**
 * The organism's on-screen half-extents, as viewport fractions.
 *
 * MEASURED, not guessed: projecting all 2562 vertices through the live camera
 * at 1920x1080 gives a body 463px wide and 393px tall.
 *
 * Worth recording — the gutter beside `<main>` (max-width 1280, centred) is
 * 320px at that viewport and the body is 463px, so it does not fit. On pages
 * whose content fills the column Prax necessarily bleeds off the right edge.
 * That is a deliberate treatment, which is why this asserts clearance from the
 * content and not containment within the viewport.
 */
const HALF_W = 463 / 2 / 1920   // 0.121
const HALF_H = 393 / 2 / 1080   // 0.182

const ROUTES = [
  '/', '/progress', '/library', '/play', '/ask', '/insights',
  '/dashboard', '/games', '/games/7c3c9a2e-1111-4a22-8b33-445566778899',
  '/drills', '/patterns', '/training',
]

/**
 * Every rectangle the page actually draws in, in CSS pixels.
 *
 * Text is measured with a `Range` over each text node rather than the element
 * box: a block-level `<h1>` spans the whole column — 1232px on this viewport —
 * while the word inside it is 90px of glyphs. Measuring boxes reports the page
 * drawing out to 0.82 when it visibly stops at 0.24.
 */
async function contentRects(page: import('@playwright/test').Page) {
  return page.evaluate(() => {
    const out: Array<{ l: number; t: number; r: number; b: number; what: string }> = []
    const main = document.querySelector('main')
    if (!main) return out

    const walker = document.createTreeWalker(main, NodeFilter.SHOW_TEXT)
    const range = document.createRange()
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      const text = (n.textContent || '').trim()
      if (!text) continue
      range.selectNodeContents(n)
      for (const b of Array.from(range.getClientRects())) {
        if (b.width === 0 || b.height === 0) continue
        if (b.top > innerHeight || b.bottom < 0) continue
        out.push({ l: b.left, t: b.top, r: b.right, b: b.bottom, what: text.slice(0, 40) })
      }
    }

    // Boards, charts and images are content too, and carry no text nodes.
    main.querySelectorAll('svg, canvas, img, table').forEach(el => {
      const b = el.getBoundingClientRect()
      if (b.width === 0 || b.height === 0) return
      if (b.top > innerHeight || b.bottom < 0) return
      out.push({ l: b.left, t: b.top, r: b.right, b: b.bottom, what: `<${el.tagName}>` })
    })
    return out
  })
}

for (const route of ROUTES) {
  test(`${route} places Prax clear of its own content`, async ({ page }) => {
    await page.setViewportSize({ width: 1920, height: 1080 })
    await page.goto(route)
    await page.waitForFunction(() => !!(window as never as Record<string, never>).__prax)
    await page.waitForTimeout(300)

    const anchor = await page.evaluate(() => {
      const reg = (window as unknown as Record<string, any>).__prax.registry
      return { hasAnchor: reg.hasAnchor(), placement: reg.getPlacement() }
    })

    expect(anchor.hasAnchor,
      `${route} registers no <PraxAnchor>, so Prax lands on the registry fallback`)
      .toBe(true)

    const vw = 1920, vh = 1080
    const body = {
      l: (anchor.placement.x - HALF_W) * vw,
      r: (anchor.placement.x + HALF_W) * vw,
      t: (anchor.placement.y - HALF_H) * vh,
      b: (anchor.placement.y + HALF_H) * vh,
    }

    const hits = (await contentRects(page))
      .filter(c => c.l < body.r && c.r > body.l && c.t < body.b && c.b > body.t)
      .map(c => `"${c.what}" at ${Math.round(c.l)},${Math.round(c.t)}-${Math.round(c.r)},${Math.round(c.b)}`)

    expect(hits,
      `Prax is anchored at (${anchor.placement.x}, ${anchor.placement.y}), covering ` +
      `${Math.round(body.l)},${Math.round(body.t)}-${Math.round(body.r)},${Math.round(body.b)} ` +
      `— which intersects ${hits.length} piece(s) of page content`)
      .toEqual([])
  })
}

test('the fallback itself is clear of the content column', async ({ page }) => {
  // A page that forgets to place Prax should look untidy, not broken.
  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.goto('/')
  await page.waitForFunction(() => !!(window as never as Record<string, never>).__prax)

  const { fallbackX, mainRightFrac } = await page.evaluate(() => {
    const reg = (window as unknown as Record<string, any>).__prax.registry
    // Dropping the live anchor makes the registry report its fallback.
    reg.set(null, { x: 0, y: 0 })
    const p = reg.getPlacement()
    const main = document.querySelector('main')!.getBoundingClientRect()
    return { fallbackX: p.x, mainRightFrac: main.right / window.innerWidth }
  })

  expect(fallbackX).toBeGreaterThanOrEqual(mainRightFrac)
})
