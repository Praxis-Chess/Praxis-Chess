import type { Locator, Page } from '@playwright/test'

export type NavLabel = 'Today' | 'Progress' | 'Library' | 'Play' | 'Ask Prax' | 'Insights'

/**
 * Shared shell: the nav bar, the sync toolbar and the Prax canvas exist on every
 * page inside <Layout>.
 *
 * Selectors here are role-based rather than data-testid. The nav is a real <nav>
 * containing real <a> elements, so getByRole reads the same tree a screen reader
 * does — a test that breaks because the accessible name changed is a test doing
 * its job. testids are added only where the DOM is genuinely ambiguous.
 */
export class AppPage {
  constructor(readonly page: Page) {}

  get nav(): Locator {
    return this.page.getByRole('navigation')
  }

  navLink(label: NavLabel): Locator {
    return this.nav.getByRole('link', { name: label, exact: true })
  }

  get main(): Locator {
    return this.page.getByRole('main')
  }

  get heading(): Locator {
    return this.main.getByRole('heading').first()
  }

  /** Prax's WebGL surface. */
  get praxCanvas(): Locator {
    return this.page.locator('canvas')
  }

  /**
   * The wrapper PraxHost puts around the canvas. aria-hidden lives here rather
   * than on the <canvas> itself, so assert against this element.
   */
  get praxContainer(): Locator {
    return this.page.locator('canvas').locator('..')
  }

  get syncNowButton(): Locator {
    return this.page.getByRole('button', { name: /sync now/i })
  }

  async goto(path = '/'): Promise<void> {
    await this.page.goto(path)
    await this.main.waitFor()
  }

  async navigateTo(label: NavLabel): Promise<void> {
    await this.navLink(label).click()
  }

  /** The active tab, read from the same attribute React Router sets. */
  async activeNavLabel(): Promise<string | null> {
    return this.nav.locator('a[aria-current="page"]').first().textContent()
  }
}
