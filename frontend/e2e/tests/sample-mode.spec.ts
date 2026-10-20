import * as fs from 'fs'
import * as path from 'path'
import { test, expect } from '../fixtures/test'

/**
 * Explore with sample games (POLISH_PLAN.md U7): the app on the captured
 * sample library, read-only, with a way out. Expectations come from the
 * library itself, so a re-capture does not break them.
 */
const file = path.join(__dirname, '../../src/sample/library.json')
const lib = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) as {
  games: number; asks: Record<string, string>; responses: Record<string, unknown>
} : null

test.describe('Sample mode', () => {
  test.skip(!lib, 'No sample library captured in this checkout')

  test('an empty report offers the sample, and the sample is the whole app, said so on every page', async ({ page, api }) => {
    api.json('/api/patterns', null)
    await page.goto('/insights/patterns')
    await page.getByRole('button', { name: 'Explore with sample games →' }).click()

    await expect(page).toHaveURL(/\/$/)
    const banner = page.getByRole('status', { name: 'Sample games' })
    await expect(banner).toContainText('Nothing here is yours')
    await page.goto('/library')
    await expect(banner).toBeVisible()
    const games = lib!.responses['/api/games'] as { opening_name: string | null }[]
    await expect(page.getByText(games[0].opening_name ?? 'Unknown opening').first()).toBeVisible()
  })

  test('a write is refused, saying why', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('praxis.sample', '1'))
    await page.goto('/')
    await page.getByRole('button', { name: /Start session/ }).first().click()
    await expect(page.getByRole('alert')).toContainText('Sample games are read-only')
  })

  test('Ask Prax replays a question asked at capture', async ({ page }) => {
    const [question] = Object.keys(lib!.asks)
    test.skip(!question, 'No question recorded')
    await page.addInitScript(() => localStorage.setItem('praxis.sample', '1'))
    await page.goto('/ask')
    await page.getByRole('region', { name: 'From your report' }).getByRole('button', { name: question }).click()
    const run = lib!.responses[`/api/prax/run/${lib!.asks[question]}`] as { answer: { answer: string } }
    await expect(page.getByText(run.answer.answer.slice(0, 40), { exact: false })).toBeVisible()
  })

  test('Exit sample returns to your own games', async ({ page }) => {
    await page.addInitScript(() => { if (!sessionStorage.getItem('seeded')) { localStorage.setItem('praxis.sample', '1'); sessionStorage.setItem('seeded', '1') } })
    await page.goto('/')
    await page.getByRole('button', { name: 'Exit sample' }).click()
    await expect(page.getByRole('status', { name: 'Sample games' })).toHaveCount(0)
    expect(await page.evaluate(() => localStorage.getItem('praxis.sample'))).toBeNull()
  })
})
