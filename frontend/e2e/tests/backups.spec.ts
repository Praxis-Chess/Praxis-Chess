import { test, expect } from '../fixtures/test'
import * as data from '../fixtures/data'
import type { Request } from '@playwright/test'

/**
 * Backups. The backup service takes them; the app shows what it has done, asks
 * for one now, and takes one before a re-analysis, which deletes analyses and
 * drill history.
 */

test.describe('Settings → Backups', () => {
  test('shows the last backup, every snapshot and the restore commands', async ({ page }) => {
    await page.goto('/settings')
    const card = page.getByRole('region', { name: 'Backups' })

    await expect(card).toContainText('Last backup 3 h ago')
    await expect(card).toContainText('3 snapshots')
    await expect(card).toContainText('4.5 MB')
    await expect(card).toContainText('test restore passed 2 days ago')
    await expect(card.getByRole('row')).toHaveCount(4)
    await expect(card.getByRole('cell', { name: 'before re-analysis' })).toBeVisible()
    await expect(card.getByText('✓ passed')).toHaveCount(1)

    await card.getByText('How to restore').click()
    await expect(card.getByText('docker/backup/restore.sh --dev praxis-20261014-090000.sql.gz', { exact: true }))
      .toBeVisible()
    await expect(card.getByText('docker/backup/restore.sh --dev --side praxis-20261014-090000.sql.gz', { exact: true }))
      .toBeVisible()
  })

  test('Back up now reports the new file, or that nothing changed', async ({ page, api }) => {
    let unchanged = false
    api.set('/api/backups', (req: Request) => req.method() === 'POST'
      ? { status: 200, body: { ok: true, file: unchanged ? 'praxis-20261014-090000.sql.gz' : 'praxis-20261015-120000.sql.gz', unchanged, error: null } }
      : { status: 200, body: data.backups })
    await page.goto('/settings')
    const card = page.getByRole('region', { name: 'Backups' })

    await card.getByRole('button', { name: 'Back up now' }).click()
    await expect(card.getByRole('status').filter({ hasText: 'Saved' })).toHaveText('Saved praxis-20261015-120000.sql.gz.')

    unchanged = true
    await card.getByRole('button', { name: 'Back up now' }).click()
    await expect(card.getByText(/Nothing has changed since praxis-20261014-090000\.sql\.gz/)).toBeVisible()
    expect(api.requestsMatching(/^\/api\/backups$/).length).toBeGreaterThanOrEqual(3)
  })

  test('says how to start the backup service when it is not running', async ({ page, api }) => {
    api.json('/api/backups', data.backupsNotRunning)
    await page.goto('/settings')
    const card = page.getByRole('region', { name: 'Backups' })

    await expect(card).toContainText('No backups yet')
    await expect(card.getByText('docker compose up -d backup')).toBeVisible()
    await expect(card.getByRole('button', { name: 'Back up now' })).toBeDisabled()
  })
})

test.describe('Re-analysis takes a backup first', () => {
  test('when none can be taken, nothing happens until the player chooses to go on', async ({ page, api }) => {
    const sent: string[] = []
    api.set(/^\/api\/analysis\/reanalyze/, (req: Request) => {
      const url = new URL(req.url())
      sent.push(url.search)
      return url.searchParams.get('skip_backup') === 'true'
        ? { status: 200, body: { message: 'Reanalysis queued', games_queued: 12 } }
        : { status: 409, body: { error: 'No backup could be taken first. The backup service is not running.', backup_failed: true } }
    })
    await page.goto('/settings')
    const library = page.getByRole('region', { name: 'Library' })

    await library.getByRole('button', { name: /Re-analyze All/ }).click()
    await library.getByRole('button', { name: 'Yes, re-analyze all' }).click()

    const notice = library.getByRole('alert')
    await expect(notice).toContainText('The backup service is not running.')
    await expect(notice).toContainText('Nothing has been changed.')
    expect(sent).toEqual([''])

    await notice.getByRole('button', { name: 'Re-analyse without a backup' }).click()
    await expect.poll(() => sent).toEqual(['', '?skip_backup=true'])
    await expect(library.getByRole('alert')).toHaveCount(0)
  })
})
