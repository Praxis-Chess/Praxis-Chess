import type { Locator } from '@playwright/test'
import { AppPage } from './AppPage'

export class PlayPage extends AppPage {
  async open(): Promise<void> {
    await this.goto('/play')
  }

  get startButton(): Locator {
    // Same control, relabelled once a game exists.
    return this.page.getByRole('button', { name: /^(Start a game|New game)$/ })
  }

  get colourSelect(): Locator {
    return this.page.getByRole('combobox')
  }

  get undoButton(): Locator {
    return this.page.getByRole('button', { name: 'Undo' })
  }

  get resignButton(): Locator {
    return this.page.getByRole('button', { name: 'Resign' })
  }

  get opponentPanel(): Locator {
    return this.main.getByText('Your opponent')
  }

  /** react-chessboard renders squares carrying data-square="e2" etc. */
  get board(): Locator {
    return this.page.locator('[data-square]').first()
  }

  square(name: string): Locator {
    return this.page.locator(`[data-square="${name}"]`)
  }

  get boardStatus(): Locator {
    return this.main.getByText(/Your move\.|Opponent thinking|You won|You lost|Drawn/)
  }

  get movesPanel(): Locator {
    return this.main.getByText('Moves')
  }

  get takebackNotice(): Locator {
    return this.main.getByText(/will not count toward your progress/i)
  }

  get analysingNotice(): Locator {
    // The panel is titled "Analysing" and carries the live counts underneath.
    return this.main.getByText(/Same engine and thresholds/i)
  }

  get engineUnavailableNotice(): Locator {
    return this.main.getByText(/chess engine is not running/i)
  }

  get historyPanel(): Locator {
    return this.main.getByText('Earlier practice games')
  }

  get reviewButtons(): Locator {
    return this.main.getByRole('button', { name: 'Review' })
  }

  get analysisProgressBar(): Locator {
    return this.main.getByRole('progressbar', { name: 'Analysing the game' })
  }
}
