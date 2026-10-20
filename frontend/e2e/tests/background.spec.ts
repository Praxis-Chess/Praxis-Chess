import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'

/**
 * The ambient background (docs/design/BACKGROUND_PLAN.md): white on black,
 * behind every page, livelier while Prax works, and chosen in Settings → Display.
 * The suite runs with reduced motion, so the field is one still frame; energy
 * is still computed, which is what these read (data-energy on the wrapper).
 */
const energy = (page: import('@playwright/test').Page) =>
  page.locator('.ambient-background').evaluate(el => Number((el as HTMLElement).dataset.energy ?? 'NaN'))

const send = (page: import('@playwright/test').Page, type: string) =>
  page.evaluate(t => (window as unknown as Record<string, any>).__prax.runtime.send({ type: t }), type)

test.describe('Ambient background', () => {
  test('is Off by default: plain black, no canvas', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/')
    await expect(app.background).toHaveCount(0)
    await expect(page.locator('canvas')).toHaveCount(0)
  })
})

// While the setting is hidden (BACKGROUND_SETTING, 20 Oct) the background is Off for everyone.
test.describe('Ambient background while its setting is hidden', () => {
  test('Settings has no Display card', async ({ page }) => {
    await page.goto('/settings')
    await expect(page.getByRole('heading', { name: 'Estimated time' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Display' })).toHaveCount(0)
    await expect(page.getByLabel('Background')).toHaveCount(0)
  })

  test('a style saved before is not drawn: it stays Off', async ({ page }) => {
    const app = new AppPage(page)
    await page.addInitScript(() => localStorage.setItem('praxis.background', JSON.stringify({ style: 'contours', motion: 'reacts' })))
    await app.goto('/')
    await expect(page.locator('canvas')).toHaveCount(0)
    await expect(app.background).toHaveCount(0)
  })
})

// The styles themselves, for when the setting comes back.
test.describe('Ambient background styles', () => {
  test.skip(true, 'The Display setting is hidden (BACKGROUND_SETTING = false); these return with it')

  test('a chosen style sits behind the content, never taking a click', async ({ page }) => {
    const app = new AppPage(page)
    await page.addInitScript(() => localStorage.setItem('praxis.background', JSON.stringify({ style: 'contours', motion: 'reacts' })))
    await app.goto('/')
    await expect(app.background).toHaveAttribute('data-background', 'contours')
    await expect(app.background).toHaveCSS('z-index', '-1')
    await expect(app.background).toHaveCSS('pointer-events', 'none')
  })

  /** G2: a question makes it livelier within half a second, and it settles after the answer. */
  test('speeds up while Prax works on a question, and settles after', async ({ page }) => {
    const app = new AppPage(page)
    await page.addInitScript(() => localStorage.setItem('praxis.background', JSON.stringify({ style: 'contours', motion: 'reacts' })))
    // Settings: no insight of its own to keep Prax busy, so it starts at rest.
    await app.goto('/settings')
    await page.waitForFunction(() => !!(window as never as Record<string, never>).__prax)
    await expect.poll(() => energy(page)).toBeLessThan(0.15)

    await send(page, 'QUERY_STARTED')
    await expect.poll(() => energy(page), { timeout: 1500 }).toBeGreaterThan(0.4)

    await send(page, 'QUERY_FINISHED')
    await expect.poll(() => energy(page), { timeout: 3000 }).toBeLessThan(0.15)
  })

  test('Calm holds it low whatever Prax does', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/settings')
    await page.getByRole('combobox', { name: 'Background' }).selectOption('contours')
    await page.getByRole('combobox', { name: 'Motion' }).selectOption('calm')
    await expect(app.background).toHaveAttribute('data-motion', 'calm')
    await page.waitForFunction(() => !!(window as never as Record<string, never>).__prax)
    await send(page, 'QUERY_STARTED')
    await page.waitForTimeout(800)
    expect(await energy(page)).toBeLessThan(0.15)
  })

  test('each style can be chosen, applies at once, and survives a reload; Off removes it', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/settings')
    const style = page.getByRole('combobox', { name: 'Background' })
    await expect(style).toHaveValue('off')
    await expect(page.getByRole('combobox', { name: 'Motion' })).toBeDisabled()
    for (const key of ['dots', 'ridges', 'trails', 'stars', 'horizon', 'contours']) {
      await style.selectOption(key)
      await expect(app.background).toHaveAttribute('data-background', key)
    }
    await style.selectOption('ridges')
    await page.reload()
    await expect(app.background).toHaveAttribute('data-background', 'ridges')

    await page.getByRole('combobox', { name: 'Background' }).selectOption('off')
    await expect(app.background).toHaveCount(0)
    await expect(page.getByRole('combobox', { name: 'Motion' })).toBeDisabled()
  })

  /**
   * Readability (BACKGROUND_PLAN.md, section 5): however lively, the field
   * stays dim. Measured on the drawn canvas, times the layer's opacity; the
   * CSS blur only spreads the light, so the mean is what text sits on.
   */
  test('stays dim enough to read over, in every style, at high energy', async ({ page }) => {
    const app = new AppPage(page)
    for (const key of ['contours', 'dots', 'ridges', 'trails', 'stars', 'horizon']) {
      await page.addInitScript(k => localStorage.setItem('praxis.background', JSON.stringify({ style: k, motion: 'reacts' })), key)
      await app.goto('/')
      await page.waitForFunction(() => !!(window as never as Record<string, never>).__prax)
      await send(page, 'QUERY_STARTED')
      await expect.poll(() => energy(page)).toBeGreaterThan(0.5)
      await page.waitForTimeout(300)
      const mean = await app.backgroundCanvas.evaluate(c => {
        const cv = c as HTMLCanvasElement
        const d = cv.getContext('2d')!.getImageData(0, 0, cv.width, cv.height).data
        let sum = 0
        for (let i = 0; i < d.length; i += 4) sum += 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2]
        return (sum / (d.length / 4) / 255) * Number(getComputedStyle(cv).opacity)
      })
      expect(mean, `${key} mean luminance`).toBeLessThan(0.12)
    }
  })
})
