import { test, expect } from '../fixtures/test'
import * as data from '../fixtures/data'
import type { Request } from '@playwright/test'

/**
 * Rule validation — hand labels for the rules.
 *
 * Two contracts matter more than the layout: the labeller is never shown the
 * rules' verdict (or the precision figure would measure deference), and a label
 * is saved exactly as chosen (or the precision figure would measure the page).
 */
test.describe('Rule validation', () => {
  test.beforeEach(async ({ api }) => {
    api.json('/api/diagnosis/next', data.labelCard)
    api.json('/api/diagnosis/report', data.ruleReport)
  })

  test('shows the position, both moves and both lines', async ({ page }) => {
    await page.goto('/labels')
    const card = page.getByRole('region', { name: 'Label 3... Nf6' })

    await expect(card).toContainText('Played Nf6')
    await expect(card).toContainText('engine g6')
    await expect(card).toContainText('Nf6 Qxf7#')
    await expect(card).toContainText('g6 Qf3 Nf6')
  })

  /**
   * The card must not carry the rules' verdict. The report further down does
   * show aggregate scores, which is fine — they say nothing about THIS mistake.
   */
  test('never shows the rules’ verdict for the mistake being labelled', async ({ page }) => {
    await page.goto('/labels')
    const card = page.getByRole('region', { name: 'Label 3... Nf6' })

    await expect(card).not.toContainText(/rules? said/i)
    await expect(card).not.toContainText(/rule verdict/i)
  })

  test('saving needs both answers, and sends exactly what was chosen', async ({ page, api }) => {
    const sent: unknown[] = []
    api.set(/^\/api\/diagnosis\/label\//, (req: Request) => {
      sent.push(req.postDataJSON())
      return { status: 200, body: { saved: true } }
    })
    await page.goto('/labels')

    const save = page.getByRole('button', { name: 'Save and next' })
    await expect(save).toBeDisabled()

    await page.getByRole('radio', { name: /^mated/ }).check()
    await expect(save).toBeDisabled()
    await page.getByRole('radio', { name: /^ignored threat/ }).check()
    await page.locator('#label-note').fill('  Qxf7 was on already  ')
    await save.click()

    await expect.poll(() => sent.length).toBe(1)
    expect(sent[0]).toEqual({ consequence: 'MATED', mechanism: 'IGNORED_THREAT', note: 'Qxf7 was on already' })
    expect(api.requestsMatching(/\/api\/diagnosis\/label\/22222222-2222-2222-2222-222222222222$/)).toHaveLength(1)
  })

  test('an empty note is sent as null, not an empty string', async ({ page, api }) => {
    const sent: unknown[] = []
    api.set(/^\/api\/diagnosis\/label\//, (req: Request) => {
      sent.push(req.postDataJSON())
      return { status: 200, body: { saved: true } }
    })
    await page.goto('/labels')
    await page.getByRole('radio', { name: /^not concrete/ }).check()
    await page.getByRole('radio', { name: /^none/ }).check()
    await page.getByRole('button', { name: 'Save and next' }).click()

    await expect.poll(() => sent.length).toBe(1)
    expect((sent[0] as { note: unknown }).note).toBeNull()
  })

  test('an empty queue says so', async ({ page, api }) => {
    api.set('/api/diagnosis/next', { status: 204 })
    await page.goto('/labels')
    await expect(page.getByText('Nothing waiting to be labelled')).toBeVisible()
  })

  test('building evidence reports what it did', async ({ page, api }) => {
    api.json(/^\/api\/diagnosis\/build/, { built: 25, failed: 0, total_built: 85, remaining: 737, millis: 12400 })
    await page.goto('/labels')
    await page.getByRole('button', { name: 'Build evidence for 25 more' }).click()

    await expect(page.getByRole('status').filter({ hasText: 'Built 25' })).toContainText('737 to go')
    expect(api.requestsMatching(/\/api\/diagnosis\/build\?limit=25$/)).toHaveLength(1)
  })

  test.describe('after a rule fix', () => {
    test('nothing is offered while every graph is current', async ({ page }) => {
      await page.goto('/labels')
      await expect(page.getByRole('region', { name: 'Rule scores' })).toBeVisible()
      await expect(page.getByRole('button', { name: /^Rebuild/ })).toHaveCount(0)
    })

    test('out-of-date graphs are flagged, and rebuilt on request', async ({ page, api }) => {
      api.json('/api/diagnosis/report', { ...data.ruleReport, stale: 60 })
      api.json(/^\/api\/diagnosis\/rebuild/, { built: 60, failed: 0, total_built: 60, remaining: 0, millis: 41000 })
      await page.goto('/labels')

      const notice = page.getByRole('region', { name: 'Out of date' })
      await expect(notice).toContainText('60 mistakes were built with older rules')
      await expect(notice).toContainText('Your labels are kept')

      await page.getByRole('button', { name: 'Rebuild 60 with the current rules' }).click()
      await expect(page.getByRole('status').filter({ hasText: 'Rebuilt 60' })).toContainText('41 s')
      expect(api.requestsMatching(/\/api\/diagnosis\/rebuild\?limit=100$/)).toHaveLength(1)
    })
  })

  test.describe('the scores', () => {
    test('show precision and recall with their intervals', async ({ page }) => {
      await page.goto('/labels')
      const scores = page.getByRole('region', { name: 'Rule scores' })

      const ignored = scores.getByRole('row', { name: /ignored threat/ })
      await expect(ignored).toContainText('75%')
      await expect(ignored).toContainText('30–95%')
      await expect(ignored).toContainText('3 / 1 / 0')
      // A rule that never fired has no precision, not 0%.
      await expect(scores.getByRole('row', { name: /removed defender/ })).toContainText('—')
    })

    test('show the composite rate, abstentions and the budget', async ({ page }) => {
      await page.goto('/labels')
      const scores = page.getByRole('region', { name: 'Rule scores' })

      await expect(scores).toContainText('23% fall in the composite subset')
      await expect(scores).toContainText('35% are not concrete')
      await expect(scores).toContainText('0 of 60 graphs over')
      await expect(scores).toContainText('max 588')
    })

    test('a rule diagnosis failing its own verifier is flagged as a bug', async ({ page, api }) => {
      api.json('/api/diagnosis/report', { ...data.ruleReport, rule_diagnoses_failing_verification: 2 })
      await page.goto('/labels')
      await expect(page.getByText('2 rule diagnoses fail their own verifier — a bug')).toBeVisible()
    })

    test('disagreements can be read, with the labeller’s note', async ({ page }) => {
      await page.goto('/labels')
      await page.getByText('1 disagreement', { exact: true }).click()
      await expect(page.getByText('Ne5 was coming anyway')).toBeVisible()
    })
  })
})
