import type { Locator } from '@playwright/test'
import { AppPage } from './AppPage'

export class GameReviewPage extends AppPage {
  async open(gameId: string): Promise<void> {
    await this.goto(`/play/review/${gameId}`)
  }

  get heading(): Locator {
    return this.main.getByRole('heading', { name: /Full analysis/ })
  }

  get summary(): Locator {
    return this.main.getByText(/of your moves, in order/)
  }

  /** One chip per half-move, keyed by its SAN as rendered (mistakes carry ?/??/?!). */
  move(san: string): Locator {
    return this.main.getByRole('button', { name: san, exact: true })
  }

  get nextButton(): Locator {
    return this.main.getByRole('button', { name: 'Next move' })
  }

  get previousButton(): Locator {
    return this.main.getByRole('button', { name: 'Previous move' })
  }

  get unflaggedNotice(): Locator {
    return this.main.getByText(/found nothing worth flagging/i)
  }

  get opponentMoveNotice(): Locator {
    return this.main.getByText(/Only your side was analysed/i)
  }
}
