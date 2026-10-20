import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import type { NavLabel } from '../pages/AppPage'

const TABS: { label: NavLabel; path: string; heading: RegExp }[] = [
  { label: 'Today',    path: '/',         heading: /today|focus|practice|weakness/i },
  { label: 'Progress', path: '/progress', heading: /progress/i },
  { label: 'Library',  path: '/library',  heading: /library|games/i },
  { label: 'Play',     path: '/play',     heading: /play & improve/i },
  { label: 'Ask Prax', path: '/ask',      heading: /ask prax/i },
  { label: 'Insights', path: '/insights', heading: /insights/i },
]

test.describe('Navigation shell', () => {
  test('renders every primary tab', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/')

    for (const { label } of TABS) {
      await expect(app.navLink(label)).toBeVisible()
    }
  })

  for (const { label, path } of TABS) {
    test(`navigates to ${label} and marks it current`, async ({ page, consoleErrors }) => {
      const app = new AppPage(page)
      await app.goto('/')

      await app.navigateTo(label)

      await expect(page).toHaveURL(new RegExp(`${path === '/' ? '/$' : path}`))
      await expect(app.nav.locator('a[aria-current="page"]')).toHaveText(label)
      await expect(app.main).toBeVisible()

      expect(consoleErrors, 'page logged errors while rendering').toEqual([])
    })
  }

  test('deep links load directly, without going through Today first', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/insights')

    await expect(app.nav.locator('a[aria-current="page"]')).toHaveText('Insights')
  })

  test('an unknown route redirects home rather than white-screening', async ({ page }) => {
    const app = new AppPage(page)
    await app.goto('/no-such-page')

    await expect(page).toHaveURL(/\/$/)
    await expect(app.main).toBeVisible()
  })

  // Skipped while the Display setting is hidden (BACKGROUND_SETTING = false): no style can be drawn. Returns with it.
  test.skip('the background mounts once and is hidden from assistive tech', async ({ page }) => {
    const app = new AppPage(page)
    await page.addInitScript(() => localStorage.setItem('praxis.background', JSON.stringify({ style: 'contours', motion: 'reacts' })))
    await app.goto('/')

    // Decorative: a moving field has nothing to announce. The attribute sits
    // on the wrapper, which is what actually removes the subtree from the a11y tree.
    await expect(app.background).toHaveAttribute('aria-hidden', 'true')
  })

  // Skipped while the Display setting is hidden (BACKGROUND_SETTING = false): no style can be drawn. Returns with it.
  test.skip('the background survives navigation without remounting', async ({ page }) => {
    const app = new AppPage(page)
    await page.addInitScript(() => localStorage.setItem('praxis.background', JSON.stringify({ style: 'contours', motion: 'reacts' })))
    await app.goto('/')

    // It sits outside <Routes> so the field is never rebuilt on a page change.
    // Tagging the element proves the same node is still there afterwards.
    await app.backgroundCanvas.evaluate(el => el.setAttribute('data-e2e-marker', 'original'))

    await app.navigateTo('Progress')
    await app.navigateTo('Play')

    await expect(app.backgroundCanvas).toHaveAttribute('data-e2e-marker', 'original')
  })
})
