import { test, expect } from '../fixtures/test'
import * as data from '../fixtures/data'
import type { Request } from '@playwright/test'

/**
 * First run. With the Docker install nobody edits a config file, so the app
 * itself has to say what is missing (the setup checklist) and take the
 * Chess.com username (Settings → Player). A working install must see neither
 * nag.
 */

test.describe('Setup checklist', () => {
  test('a working install shows no checklist on Today', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'Today' })).toBeVisible()
    await expect(page.getByRole('heading', { name: /setting up|Downloading|Almost ready/ })).toHaveCount(0)
  })

  test('a fresh install names what is missing and shows download progress', async ({ page, api }) => {
    api.json('/api/setup/status', data.setupFresh)
    await page.goto('/')

    const card = page.getByRole('region', { name: 'Finish setting up Praxis' })
    await expect(card).toBeVisible()
    await expect(card.getByText('Chess.com username', { exact: true })).toBeVisible()
    // What blocks comes first; what is ready, and the model downloads, are one tap away.
    await expect(card.getByText('Database', { exact: true })).toHaveCount(0)
    await card.getByRole('button', { name: /^3 ready/ }).click()
    await expect(card.getByText('Database', { exact: true })).toBeVisible()
    await card.getByRole('button', { name: /^Models \(3\)/ }).click()
    await expect(card.getByText('2.4 / 4.7 GB')).toBeVisible()
    await expect(card.getByRole('progressbar', { name: 'qwen2.5:7b download' })).toHaveAttribute('aria-valuenow', '51')
    await expect(card.getByText('queued')).toHaveCount(2)
    // Engine analysis does not wait for the models; the card says so.
    await expect(card.getByText(/engine analysis works now/i)).toBeVisible()
    // "!" is a chess annotation, never a UI marker.
    await expect(card.getByLabel('needs attention')).toHaveText('…')

    await card.getByRole('link', { name: /Enter it in Settings/ }).click()
    await expect(page).toHaveURL(/\/settings#player$/)
    await expect(page.getByRole('region', { name: 'Player' })).toBeVisible()
  })

  test('warnings alone put no card on Today', async ({ page, api }) => {
    api.json('/api/setup/status', {
      ...data.setupReady,
      checks: data.setupReady.checks.map(c => c.key === 'voice'
        ? { ...c, state: 'WARN', detail: 'Voice service not reachable', fix: 'cd tts-service && python main.py' } : c),
    })
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'Today' })).toBeVisible()
    await expect(page.getByRole('heading', { name: /setting up|Downloading|Almost ready/ })).toHaveCount(0)
  })

  test('Settings folds every check into one line under Sync Now, expanded on click', async ({ page }) => {
    await page.goto('/settings')
    const library = page.getByRole('region', { name: 'Library' })
    const toggle = library.getByRole('button', { name: /Setup/ })
    await expect(toggle).toHaveText(/Setup · 5 ready · 2 off/)
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(page.getByRole('region', { name: 'Setup' })).toHaveCount(0)

    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const card = page.getByRole('region', { name: 'Setup' })
    await expect(card.getByText('Web research', { exact: true })).toBeVisible()
    await expect(card.getByText('Stockfish 19').first()).toBeVisible()
    await expect(card.getByText('ready', { exact: true })).toHaveCount(3)
    await expect(card.getByText('Backups', { exact: true })).toBeVisible()
  })
})

test.describe('Settings → Player', () => {
  test('saving a username sends it and confirms', async ({ page, api }) => {
    const sent: string[] = []
    api.set('/api/settings/player', (req: Request) => {
      if (req.method() !== 'PUT') return { status: 200, body: data.playerNone }
      sent.push((req.postDataJSON() as { username: string }).username)
      return { status: 200, body: { player: { username: 'hikaru', source: 'settings' }, warning: null } }
    })
    await page.goto('/settings')

    const card = page.getByRole('region', { name: 'Player' })
    await card.getByLabel('Chess.com username').fill('hikaru')
    await card.getByRole('button', { name: 'Save' }).click()

    await expect(card.getByRole('status')).toContainText('Saved')
    expect(sent).toEqual(['hikaru'])
  })

  test('a name Chess.com does not know is shown as an error, not saved', async ({ page, api }) => {
    api.set('/api/settings/player', (req: Request) => req.method() !== 'PUT'
      ? { status: 200, body: data.playerNone }
      : { status: 400, body: { errors: { username: 'Chess.com has no player called "nobody-xyz".' } } })
    await page.goto('/settings')

    const card = page.getByRole('region', { name: 'Player' })
    await card.getByLabel('Chess.com username').fill('nobody-xyz')
    await card.getByRole('button', { name: 'Save' }).click()

    await expect(card.getByRole('alert')).toContainText('no player called')
    await expect(card.getByLabel('Chess.com username')).toHaveAttribute('aria-invalid', 'true')
  })

  test('Save stays disabled until the name changes', async ({ page }) => {
    await page.goto('/settings')
    const card = page.getByRole('region', { name: 'Player' })
    await expect(card.getByLabel('Chess.com username')).toHaveValue('player')
    await expect(card.getByRole('button', { name: 'Save' })).toBeDisabled()
  })
})
