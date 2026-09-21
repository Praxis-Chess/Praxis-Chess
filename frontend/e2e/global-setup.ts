import { chromium, type FullConfig } from '@playwright/test'

/**
 * Warms the Vite dev server before any test runs.
 *
 * Vite compiles modules on demand. On a cold server the first request for a
 * route pays for transforming the whole graph it touches — Three.js, Recharts,
 * chess.js and react-chessboard among them. With several workers starting at
 * once, that cost lands inside the first assertions of several tests
 * simultaneously and they time out for reasons that have nothing to do with the
 * application.
 *
 * App.tsx imports every page eagerly (no React.lazy), so a single visit to '/'
 * compiles essentially the entire client. One request here removes a whole class
 * of false failure, and is far better than papering over it with longer timeouts.
 */
export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0]?.use?.baseURL ?? 'http://localhost:5173'

  const browser = await chromium.launch()
  try {
    const page = await browser.newPage()
    // The backend is not required: the app renders its loading and empty states
    // regardless, and compiling the modules is the only goal here.
    await page.goto(baseURL, { waitUntil: 'load', timeout: 120_000 })
    await page.waitForTimeout(1_500)
  } finally {
    await browser.close()
  }
}
