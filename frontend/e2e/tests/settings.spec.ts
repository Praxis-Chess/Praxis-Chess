import { test, expect } from '../fixtures/test'
import * as data from '../fixtures/data'
import type { Request } from '@playwright/test'
import type { ApiMock } from '../fixtures/ApiMock'
import type { SettingsSaved, SettingsUpdate } from '../../src/api/types'

/**
 * Settings: date ranges, the coverage view, and versioned engine settings.
 *
 * The contract worth protecting is that engine settings are a RULER. A save that
 * sends the wrong depth, or a page that hides which settings analysed which
 * games, turns every later comparison into noise without anything looking wrong.
 */

/** Answers GET with the view and PUT via `onPut`, recording what was sent. */
function mockSettings(
  api: ApiMock,
  onPut: (body: SettingsUpdate) => { status: number; body: unknown },
) {
  const sent: SettingsUpdate[] = []
  api.set('/api/settings', (req: Request) => {
    if (req.method() !== 'PUT') return { status: 200, body: data.settingsView }
    const body = req.postDataJSON() as SettingsUpdate
    sent.push(body)
    return onPut(body)
  })
  return sent
}

function saved(overrides: Partial<SettingsSaved> = {}): SettingsSaved {
  return {
    settings: {
      ...data.settingsView,
      library: { ...data.settingsView.library, id: 3, label: 'library-v1', multi_pv_depth: 24 },
    },
    library_version_created: true,
    practice_version_created: false,
    ...overrides,
  }
}

test.describe('Settings', () => {
  test('the header gear opens Settings', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('link', { name: 'Settings' }).click()
    await expect(page).toHaveURL(/\/settings$/)
    await expect(page.getByRole('heading', { name: 'Settings', level: 1 })).toBeVisible()
  })

  test('coverage shows totals, practice games separately, and settings per month', async ({ page }) => {
    await page.goto('/settings')
    const card = page.getByRole('region', { name: 'Coverage' })

    await expect(card).toContainText('2026-07-03')
    await expect(card).toContainText('2026-08-20')
    // The totals are a row of the same table, read by the same column headers.
    const total = card.getByRole('row', { name: /^Total/ })
    await expect(total.getByRole('cell')).toHaveText(['5', '4', '1', '0', ''])
    // Practice games are a different population and never folded into these.
    await expect(card).toContainText('practice 3 played')

    const august = card.getByRole('row', { name: /2026-08/ })
    await expect(august).toContainText('library-v0')
  })

  test('a month expands to its days', async ({ page }) => {
    await page.goto('/settings')
    const toggle = page.getByRole('button', { name: '2026-08: show days' })
    await expect(page.getByRole('rowheader', { name: '2026-08-20' })).toHaveCount(0)

    await toggle.click()

    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await expect(page.getByRole('rowheader', { name: '2026-08-20' })).toBeVisible()
    await expect(page.getByRole('rowheader', { name: '2026-08-05' })).toBeVisible()
  })

  test('saving a changed depth sends exactly that change and names the new version', async ({ page, api }) => {
    const sent = mockSettings(api, () => ({ status: 200, body: saved() }))
    await page.goto('/settings')

    await page.locator('#library-depth').fill('24')
    await page.getByRole('button', { name: 'Save settings' }).click()

    await expect(page.getByRole('status').filter({ hasText: 'library-v1' })).toBeVisible()
    expect(sent).toHaveLength(1)
    expect(sent[0].library).toEqual({
      sweep_move_time_ms: 100, multi_pv_depth: 24, multi_pv_lines: 3, max_explanations: 3,
    })
    // The practice group was untouched and is sent as it was.
    expect(sent[0].practice?.multi_pv_depth).toBe(20)
    // No range set: nulls, which the server reads as "keep the default behaviour".
    expect(sent[0].sync_from).toBeNull()
    expect(sent[0].analysis_to).toBeNull()
  })

  test('time controls: rapid by default, and a ticked one is sent in speed order', async ({ page, api }) => {
    const sent = mockSettings(api, () => ({ status: 200, body: saved() }))
    await page.goto('/settings')

    const group = page.getByRole('group', { name: 'Time controls to analyse' })
    await expect(group.getByLabel('rapid')).toBeChecked()
    await expect(group.getByLabel('bullet')).not.toBeChecked()

    await group.getByLabel('bullet').check()
    await page.getByRole('button', { name: 'Save settings' }).click()

    await expect.poll(() => sent.length).toBe(1)
    expect(sent[0].analysis_time_classes).toEqual(['bullet', 'rapid'])
  })

  test('AI models: everything on this laptop by default, no privacy notice', async ({ page }) => {
    await page.goto('/settings')
    const card = page.getByRole('region', { name: 'AI models' })
    await expect(card).toContainText('Prax chat')
    await expect(card).toContainText('qwen3:4b-instruct')
    await expect(card.getByText('this laptop')).toHaveCount(4)
    await expect(card.getByRole('note')).toHaveCount(0)
  })

  test('AI models: with no commentary model, the card points to the trained model on Hugging Face', async ({ page, api }) => {
    api.json('/api/settings/ai', {
      ...data.aiLocal,
      features: data.aiLocal.features.map(f => f.feature === 'commentary' ? { ...f, model: null } : f),
    })
    await page.goto('/settings')
    const card = page.getByRole('region', { name: 'AI models' })
    const link = card.getByRole('link', { name: 'Hugging Face' })
    await expect(link).toHaveAttribute('href', 'https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-2b-GGUF')
    await expect(card).toContainText('Checked commentary is off')
    // Not a privacy notice: nothing here leaves the laptop.
    await expect(card.getByRole('note')).toHaveCount(0)
  })

  test('AI models: a cloud feature names its provider and warns what leaves the laptop', async ({ page, api }) => {
    api.json('/api/settings/ai', data.aiCloud)
    await page.goto('/settings')
    const card = page.getByRole('region', { name: 'AI models' })
    await expect(card.getByText('api.openai.com', { exact: true })).toHaveCount(2)
    await expect(card.getByRole('note')).toContainText('send your positions and game data to api.openai.com')
    // The trained commentary model never leaves the laptop.
    await expect(card).toContainText('praxis-grid-2b-r3')
  })

  test('"every flagged move" sends null, not a number', async ({ page, api }) => {
    const sent = mockSettings(api, () => ({ status: 200, body: saved({ library_version_created: false }) }))
    await page.goto('/settings')

    const engine = page.getByRole('region', { name: 'Engine' })
    await engine.getByText('Retired explanations').click()
    await engine.getByRole('group', { name: /^Written explanations/ }).getByRole('group', { name: 'Chess.com games' })
      .getByRole('checkbox', { name: 'Every flagged move' }).check()
    await page.getByRole('button', { name: 'Save settings' }).click()

    await expect.poll(() => sent.length).toBe(1)
    expect(sent[0].library?.max_explanations).toBeNull()
  })

  test('each rejected field shows its own message', async ({ page, api }) => {
    mockSettings(api, () => ({
      status: 400,
      body: { errors: {
        library_multi_pv_depth: 'Deep-check depth must be 12–30.',
        sync_range: 'Set both ends of the sync range, or clear both to use the default.',
      } },
    }))
    await page.goto('/settings')

    await page.locator('#library-depth').fill('40')
    await page.locator('#sync-from').fill('2026-06-01')
    await page.getByRole('button', { name: 'Save settings' }).click()

    await expect(page.getByText('Deep-check depth must be 12–30.')).toBeVisible()
    await expect(page.getByText('Set both ends of the sync range')).toBeVisible()
    // Nothing claims it saved.
    await expect(page.getByRole('status').filter({ hasText: 'Saved' })).toHaveCount(0)
  })

  test('a measured estimate shows figures', async ({ page }) => {
    await page.goto('/settings')
    const card = page.getByRole('region', { name: 'Estimated time' })
    const library = card.getByRole('row', { name: /^Chess.com games/ })
    await expect(library).toContainText('46 s')          // 45,600 ms per game
    await expect(library).toContainText('5 in range')    // games in range
    await expect(library).toContainText('4 min')         // 228,000 ms total
    await expect(library).toContainText('12 games, library-v0')
    await expect(card.getByRole('columnheader', { name: 'Measured on' })).toBeVisible()
    await expect(card.getByRole('row', { name: /^Practice games/ })).toContainText('within the report window')
  })

  test('an unmeasured estimate says so instead of guessing', async ({ page, api }) => {
    api.json('/api/settings/estimate', data.estimateUnmeasured)
    await page.goto('/settings')
    const card = page.getByRole('region', { name: 'Estimated time' })
    await expect(card).toContainText('no estimate yet')
    await expect(card).not.toContainText(' s per game')
  })

  test('mixed settings are flagged, and outdated games re-analyse only after confirming', async ({ page, api }) => {
    api.json('/api/settings/coverage', data.coverageMixed)
    api.json('/api/analysis/reanalyze', { message: 'Reanalysis queued', games_queued: 3 })
    await page.goto('/settings')

    await expect(page.getByText(/span 2 engine settings/)).toBeVisible()
    await expect(page.getByText(/library-v0 \(3\), library-v1 \(1\)/)).toBeVisible()

    await page.getByRole('button', { name: 'Re-analyse 3 games with current settings' }).click()
    // It rewrites analyses, so it asks first — and hasn't fired yet.
    await expect(page.getByText(/keeps their drill cards/)).toBeVisible()
    expect(api.requestsMatching(/\/api\/analysis\/reanalyze/)).toHaveLength(0)

    await page.getByRole('button', { name: 'Yes, re-analyse' }).click()

    await expect(page.getByText('Queued 3 games')).toBeVisible()
    expect(api.requestsMatching(/\/api\/analysis\/reanalyze\?outdated_only=true$/)).toHaveLength(1)
  })

  /**
   * The dev-only PraxDebugPanel is fixed to the bottom-left corner. With the Save
   * button left-aligned at the end of the page, scrolling down put the button
   * underneath it — found in a screenshot, not by any test. The Playwright server
   * is the Vite dev server, so the panel is really there.
   */
  test('the Save button is not covered once scrolled into view', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/settings')
    const save = page.getByRole('button', { name: 'Save settings' })
    await save.scrollIntoViewIfNeeded()
    // The whole button, not just its centre: at a 900px window the panel covered
    // only the bottom half, and a centre-only check passed while half of the
    // button was hidden. Sample the centre and four points inset from the corners.
    const covered = await save.evaluate(el => {
      const r = el.getBoundingClientRect()
      const inset = 3
      const points: Array<[string, number, number]> = [
        ['centre', r.x + r.width / 2, r.y + r.height / 2],
        ['top-left', r.left + inset, r.top + inset],
        ['top-right', r.right - inset, r.top + inset],
        ['bottom-left', r.left + inset, r.bottom - inset],
        ['bottom-right', r.right - inset, r.bottom - inset],
      ]
      return points
        .filter(([, x, y]) => {
          const top = document.elementFromPoint(x, y)
          return !(top === el || el.contains(top))
        })
        .map(([name]) => name)
    })
    expect(covered, 'part of the Save button is drawn over').toEqual([])
  })

  test('cancelling the re-analysis sends nothing', async ({ page, api }) => {
    api.json('/api/settings/coverage', data.coverageMixed)
    await page.goto('/settings')
    await page.getByRole('button', { name: /Re-analyse 3 games/ }).click()
    await page.getByRole('button', { name: 'Cancel' }).click()
    expect(api.requestsMatching(/\/api\/analysis\/reanalyze/)).toHaveLength(0)
  })
})

test.describe('Insights and engine settings', () => {
  test('warns when the trends mix engine settings', async ({ page, api }) => {
    api.json('/api/settings/coverage', data.coverageMixed)
    await page.goto('/insights')
    await expect(page.getByText(/analysed with 2 different engine settings/)).toBeVisible()
    await expect(page.getByRole('link', { name: 'Re-analyse on the Settings page' })).toHaveAttribute('href', '/settings')
  })

  test('says nothing when every game shares one ruler', async ({ page }) => {
    await page.goto('/insights')
    await expect(page.getByRole('heading', { name: 'Insights' })).toBeVisible()
    await expect(page.getByText(/different engine settings/)).toHaveCount(0)
  })
})

test.describe('Settings: show, don\'t tell', () => {
  test('one solid button: Sync Now, until the form has changes, then Save settings', async ({ page }) => {
    await page.goto('/settings')
    const solid = page.locator('main button.solid')
    await expect(solid).toHaveCount(1)
    await expect(solid).toHaveText('Sync Now')

    await page.getByLabel('Deep-check depth (12–30), Chess.com games').fill('20')
    await expect(solid).toHaveCount(1)
    await expect(solid).toHaveText('Save settings')
  })

  test('help is a tap away, never only on hover', async ({ page }) => {
    await page.goto('/settings')
    await expect(page.locator('main [title]')).toHaveCount(0)
    const about = page.getByRole('button', { name: /^About Scan time per move/ })
    await expect(about).toHaveAttribute('aria-expanded', 'false')
    await about.click()
    await expect(page.getByText('The first pass over every position. Decides which moves get flagged.')).toBeVisible()
  })

  test('the engine settings are one table: each label once, a value per engine', async ({ page }) => {
    await page.goto('/settings')
    const engine = page.getByRole('region', { name: 'Engine' })
    await expect(engine.getByText(/^Scan time per move/)).toHaveCount(1)
    await expect(engine.getByLabel('Scan time per move (50–1000 ms), Chess.com games')).toHaveValue('100')
    await expect(engine.getByLabel('Scan time per move (50–1000 ms), Practice games')).toHaveValue('200')
    // Retired, but its values are one tap away.
    await engine.getByText('Retired explanations').click()
    await expect(engine.getByLabel('Written explanations per game (0–50), Chess.com games')).toHaveValue('3')
  })

  test.describe('on a phone', () => {
    test.use({ viewport: { width: 390, height: 844 }, hasTouch: true })
    test('the engine table stacks, and every value says which engine it is for', async ({ page }) => {
      await page.goto('/settings')
      const engine = page.getByRole('region', { name: 'Engine' })
      await expect(engine.locator('.only-stacked').first()).toBeVisible()
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
      expect(overflow).toBeLessThanOrEqual(0)
    })
  })
})
