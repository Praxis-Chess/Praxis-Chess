import { defineConfig, devices } from '@playwright/test'

/**
 * Praxis Chess — Playwright configuration.
 *
 * Two families of test, and the split is the most important decision here:
 *
 *   mocked (default)  Every /api/** call is intercepted in the browser. No
 *                     backend, no Postgres, no Ollama, no Stockfish. Runs on a
 *                     laptop with nothing else started, and in CI. Deterministic,
 *                     because the data is a fixture rather than whatever the
 *                     player happened to do last week.
 *
 *   live (@live)      Real backend on :8086. Excluded by default; run it
 *                     explicitly. It exists to pin the WIRE FORMAT — the thing a
 *                     mock can never catch, because a mock is written from the
 *                     same assumption the frontend already holds.
 *
 * Both are needed. The mocked suite proves the app behaves; the live suite proves
 * the app and the server still agree about what the JSON looks like.
 */
const PORT = 5173
const BASE_URL = process.env.E2E_BASE_URL ?? `http://localhost:${PORT}`

/**
 * The live project is registered ONLY when explicitly asked for.
 *
 * Otherwise a bare `npx playwright test` runs every project, and the live suite
 * fails with ECONNREFUSED on any machine where the backend happens to be
 * stopped — training everyone to ignore red, which is the worst outcome a test
 * suite can produce. Opting in by name (or E2E_LIVE=1) keeps the default run
 * meaningful.
 */
const argv = process.argv
const wantsLive =
  !!process.env.E2E_LIVE ||
  argv.some((a, i) => a === '--project=live' || (a === '--project' && argv[i + 1] === 'live'))

export default defineConfig({
  testDir: './e2e',

  // Compiles the Vite module graph once, before any worker starts asserting.
  globalSetup: './e2e/global-setup.ts',

  // Fail the build if a .only was committed.
  forbidOnly: !!process.env.CI,

  // One retry in CI absorbs genuine flake without hiding a real failure; zero
  // locally so a flaky test is visible while you are writing it.
  retries: process.env.CI ? 1 : 0,

  // The dev server is a single Vite process; hammering it from many workers
  // buys little and makes timings noisy.
  workers: process.env.CI ? 2 : 4,

  timeout: 30_000,
  expect: { timeout: 7_000 },

  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }], ['list']]
    : [['html', { open: 'never' }], ['list']],

  use: {
    baseURL: BASE_URL,

    /**
     * The single most valuable line in this file.
     *
     * Prax is a Three.js particle system driving a requestAnimationFrame loop
     * that never idles. Under test that means: no stable screenshot, wasted CPU
     * on every worker, and a permanent source of flake.
     *
     * The app already derives its render policy from prefers-reduced-motion —
     * deriveRenderPolicy() returns 'frozen' for it. So this flag doesn't just
     * slow the animation down, it stops the loop entirely, through a code path
     * that already exists and is already correct in production.
     */
    contextOptions: { reducedMotion: 'reduce' },

    // Artefacts only when something went wrong — cheap to keep, invaluable to read.
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',

    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      grepInvert: /@live/,
    },
    {
      // A narrow DESKTOP window, not a phone. Praxis runs on localhost — the
      // backend, Postgres, Ollama and Stockfish are all on this machine — so
      // nobody opens it on a phone. What does happen is a laptop window snapped
      // to half the screen: 683x768 is exactly half of a 1366x768 display, the
      // commonest laptop resolution.
      //
      // This replaced a Pixel 7 project. Phone emulation tested a device no user
      // has, and its quirks (touch, a layout viewport that widens to fit content)
      // cost CI failures that said nothing about the app people actually use.
      name: 'narrow',
      use: { ...devices['Desktop Chrome'], viewport: { width: 683, height: 768 } },
      grepInvert: /@live/,
      // axe results don't depend on the window width; one run is enough.
      testIgnore: /a11y\.spec\.ts/,
    },
    // Registered only on request — see wantsLive above.
    ...(wantsLive
      ? [{
          name: 'live',
          use: { ...devices['Desktop Chrome'] },
          grep: /@live/,
        }]
      : []),
  ],

  /**
   * Starts Vite if it isn't already up. reuseExistingServer keeps the dev server
   * you already have running — no port fight, and a much faster loop while
   * writing tests.
   */
  webServer: {
    command: 'npm run dev',
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
})
