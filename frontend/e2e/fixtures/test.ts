import { test as base, expect } from '@playwright/test'
import { ApiMock } from './ApiMock'

/**
 * The project's `test`. Import this everywhere instead of @playwright/test, so
 * every spec gets API mocking and console-error detection without opting in.
 */
export const test = base.extend<{
  api: ApiMock
  /** Console errors seen during the test. Empty unless a spec allows some. */
  consoleErrors: string[]
}>({
  /**
   * `auto: true` is load-bearing, not tidiness.
   *
   * A Playwright fixture is only constructed if a test destructures it. Without
   * auto, any test written as ({ page }) silently gets NO mocking — its requests
   * escape through Vite's proxy to a backend that may not be running, and it
   * fails for a reason that has nothing to do with what it is testing.
   *
   * Mocking is a property of the suite, so it must not be opt-in.
   */
  api: [
    async ({ page }, use) => {
      const mock = new ApiMock(page)
      mock.installDefaults()
      await mock.install()

      await use(mock)

      // A request the mock did not recognise means either a new endpoint nobody
      // added a fixture for, or a genuine bug. Both should stop the build.
      if (mock.unmatched.length > 0) {
        throw new Error(
          `Unmocked API calls (add a rule in ApiMock.installDefaults or the test):\n  ` +
            [...new Set(mock.unmatched)].join('\n  '),
        )
      }
    },
    { auto: true },
  ],

  /**
   * Uncaught React errors and failed renders surface here long before they
   * surface as a visible assertion failure — a test can "pass" against a page
   * that logged a thrown error in an effect.
   */
  consoleErrors: async ({ page }, use) => {
    const errors: string[] = []

    page.on('console', msg => {
      if (msg.type() !== 'error') return
      const text = msg.text()
      // React Router's v7 deprecation notices are warnings we do not control.
      if (text.includes('React Router Future Flag')) return
      // WebGL context loss on a headless GPU is environmental, not a defect.
      if (/WebGL|THREE\.WebGLRenderer/i.test(text)) return
      errors.push(text)
    })

    page.on('pageerror', err => errors.push(`[pageerror] ${err.message}`))

    await use(errors)
  },
})

export { expect }
