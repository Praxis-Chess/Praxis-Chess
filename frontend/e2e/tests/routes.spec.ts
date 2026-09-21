import { test, expect } from '../fixtures/test'

/**
 * Every route the router knows about, rendered.
 *
 * The primary tabs have real behavioural specs elsewhere. These are the
 * secondary routes — still reachable by URL, still shipped — and they had no
 * coverage at all, which meant a rename in `types.ts` or a null in a payload
 * could white-screen one of them and nothing would say so until a user hit it.
 *
 * Deliberately shallow. The assertion is "it rendered and the console is
 * clean", not a snapshot of the content; a shallow test that runs is worth more
 * than a deep one nobody maintains.
 */

const ROUTES = [
  { path: '/dashboard', name: 'Dashboard' },
  { path: '/games', name: 'Library (legacy)' },
  { path: '/games/7c3c9a2e-1111-4a22-8b33-445566778899', name: 'Game analysis' },
  { path: '/drills', name: 'Drills' },
  { path: '/patterns', name: 'Pattern report' },
  { path: '/training', name: 'Training plan' },
]

for (const route of ROUTES) {
  test(`${route.name} renders without crashing`, async ({ page, api }) => {
    const errors: string[] = []
    page.on('pageerror', e => errors.push(e.message))

    // /games/:id reads the per-game move errors. An analysed game with no
    // mistakes is the interesting shallow case: the page must render the
    // "nothing found" state rather than assume a non-empty list.
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [])

    await page.goto(route.path)

    // <main> present and not empty: a thrown render leaves an empty root.
    const main = page.getByRole('main')
    await expect(main).toBeVisible()
    await expect.poll(async () => (await main.innerText()).trim().length).toBeGreaterThan(0)

    expect(errors, `${route.path} threw during render`).toEqual([])

    // Nothing may 501 — that is ApiMock saying the page called an endpoint no
    // fixture covers, which usually means the page is silently degraded.
    expect(api.unmatched).toEqual([])
  })
}

test('a route that does not exist redirects home instead of white-screening', async ({ page }) => {
  await page.goto('/no-such-page')
  await expect(page).toHaveURL(/\/$/)
})
