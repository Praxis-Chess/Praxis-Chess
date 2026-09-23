import { test, expect } from '../fixtures/test'
import * as data from '../fixtures/data'

/**
 * The evidence lab.
 *
 * The contract worth protecting is that a claim is always shown next to the
 * evidence it came from, and that the threat probe's answer is stated in the
 * direction it actually points. Those two things are the entire reason this page
 * exists: an explanation you cannot check is exactly what the baseline measured
 * and found wanting.
 */
test.describe('Evidence lab', () => {
  const GAME = '11111111-1111-1111-1111-111111111111'

  test.beforeEach(async ({ api }) => {
    api.json(/^\/api\/evidence\//, data.evidenceReport)
  })

  test('runs nothing until asked', async ({ page, api }) => {
    await page.goto(`/evidence/${GAME}`)

    await expect(page.getByRole('heading', { name: 'Evidence lab', level: 1 })).toBeVisible()
    // An engine search per mistake is expensive; landing on the page must not
    // start one.
    expect(api.requestsMatching(/\/api\/evidence\//)).toHaveLength(0)

    await page.getByRole('button', { name: 'Run' }).click()
    await expect.poll(() => api.requestsMatching(/\/api\/evidence\//).length).toBe(1)
  })

  /**
   * move_number is a ply. "31. Bd2" would put White's 16th move on move 31 and
   * give Black's moves White's notation.
   */
  test('numbers moves as moves, not plies', async ({ page }) => {
    await page.goto(`/evidence/${GAME}`)
    await page.getByRole('button', { name: 'Run' }).click()

    await expect(page.getByRole('region', { name: '16. Bd2' })).toBeVisible()
    await expect(page.getByRole('region', { name: '3... Nf6' })).toBeVisible()
  })

  test('shows each answer with the block it came from', async ({ page }) => {
    await page.goto(`/evidence/${GAME}`)
    await page.getByRole('button', { name: 'Run' }).click()

    const first = page.getByRole('region', { name: '16. Bd2' })
    await expect(first).toContainText('leaves the knight on c3 undefended')

    // The evidence is collapsed but present, so the answer can be checked
    // against it rather than taken on trust.
    await first.getByText('The evidence it was given').click()
    await expect(first).toContainText('Reply: Qxc3')
  })

  /**
   * The probe decides which lesson applies. A free move always has a best
   * move, so the page must not call every probe result a threat — the first
   * version did exactly that on every mistake of a real game.
   */
  test.describe('the threat probe, all three ways', () => {
    test('nothing worth a pawn was threatened: the move created it', async ({ page }) => {
      await page.goto(`/evidence/${GAME}`)
      await page.getByRole('button', { name: 'Run' }).click()

      const m = page.getByRole('region', { name: '16. Bd2' })
      await expect(m).toContainText('Nothing serious was threatened before this move')
      await expect(m).toContainText('so the move created the problem')
      await expect(m).not.toContainText('already')
    })

    test('the refutation was already on: the move ignored it', async ({ page }) => {
      await page.goto(`/evidence/${GAME}`)
      await page.getByRole('button', { name: 'Run' }).click()

      await expect(page.getByRole('region', { name: '3... Nf6' }))
        .toContainText('The refutation h5f7 was already threatened before this move')
    })

    test('a different threat existed: the move still created this one', async ({ page }) => {
      await page.goto(`/evidence/${GAME}`)
      await page.getByRole('button', { name: 'Run' }).click()

      const m = page.getByRole('region', { name: '21. Rd1' })
      await expect(m).toContainText('already had a threat, e8e1')
      await expect(m).toContainText('the refutation a8b8 was worth only 1.1 before this move')
    })
  })

  test('reports the engine cost the evidence adds', async ({ page }) => {
    await page.goto(`/evidence/${GAME}`)
    await page.getByRole('button', { name: 'Run' }).click()

    const cost = page.getByRole('region', { name: 'Cost' })
    await expect(cost).toContainText('225 ms')
    await expect(cost).toContainText('576 ms')
    await expect(cost).toContainText('3 of 7 flagged moves')
  })

  test('a failure is reported, not rendered as an empty answer', async ({ page, api }) => {
    api.fail(/^\/api\/evidence\//, 500, 'stockfish unavailable')
    await page.goto(`/evidence/${GAME}`)
    await page.getByRole('button', { name: 'Run' }).click()

    await expect(page.getByRole('alert')).toContainText('500')
  })
})
