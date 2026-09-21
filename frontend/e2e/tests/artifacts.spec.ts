import { test, expect } from '../fixtures/test'
import { AppPage } from '../pages/AppPage'
import * as data from '../fixtures/data'

/**
 * The Prax Artifact Protocol, from the wire to the screen.
 *
 * The board is the whole point of the feature, and a board that renders wrongly
 * is worse than none: the player has no way to tell. These assert what actually
 * reaches the DOM.
 */

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'

function answerWith(artifacts: unknown[]) {
  return {
    answer: 'You most often miss threats against pieces that are already attacked.',
    findings: [],
    evidence: [],
    steps: [{ tool: 'analyze_position', sample_size: 0 }],
    partial: false,
    sources: [],
    lane: 'PLAYER',
    grounding: 'GROUNDED',
    artifacts,
  }
}

const position = {
  type: 'CHESS_POSITION',
  id: 'pos-1',
  title: 'The position when it went wrong',
  fen: START_FEN,
  orientation: 'white',
  highlights: ['e2', 'e4', 'a2', 'a3'],
  arrows: [
    { from: 'a2', to: 'a3', role: 'PLAYED' },
    { from: 'e2', to: 'e4', role: 'BEST' },
  ],
  caption: 'Played a3; engine prefers e4.',
}

/**
 * Opens the Prax card and asks something.
 *
 * Opened through the dev-only `window.__prax.ask` handle rather than by clicking
 * Prax: the hit target tracks the particle body's PROJECTED screen position,
 * which does not advance while the render loop is frozen under reduced-motion.
 * These tests are about what the card CONTAINS, and should not also be a test of
 * WebGL projection geometry.
 */
async function ask(page: import('@playwright/test').Page, question = "What's my weakness?") {
  await page.waitForFunction(() => !!(window as never as Record<string, never>).__prax)
  await page.evaluate(() => {
    const prax = (window as unknown as Record<string, { ask: { open(): void } }>).__prax
    prax.ask.open()
  })
  await page.getByPlaceholder(/Ask about your games/i).fill(question)
  await page.getByRole('button', { name: /^Ask$/ }).click()
}

test.describe('Prax artifacts', () => {
  test('renders a chessboard for a CHESS_POSITION artifact', async ({ page, api }) => {
    api.json('/api/prax/ask', answerWith([position]))

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    // react-chessboard renders one element per square.
    await expect(page.locator('[data-square="e2"]').first()).toBeVisible()
    await expect(page.getByText('The position when it went wrong')).toBeVisible()
    await expect(page.getByText('Played a3; engine prefers e4.')).toBeVisible()
  })

  test('shows a key naming what each arrow means', async ({ page, api }) => {
    api.json('/api/prax/ask', answerWith([position]))

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    // An arrow with no key is decoration. These say which is which.
    await expect(page.getByText('You played')).toBeVisible()
    await expect(page.getByText('Engine', { exact: true })).toBeVisible()
  })

  test('advertises only the arrow roles actually drawn', async ({ page, api }) => {
    api.json('/api/prax/ask', answerWith([
      { ...position, arrows: [{ from: 'e2', to: 'e4', role: 'BEST' }] },
    ]))

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    await expect(page.getByText('Engine', { exact: true })).toBeVisible()
    await expect(page.getByText('You played')).toHaveCount(0)
  })

  /**
   * The version-skew rule. A backend shipping a new artifact type must never
   * white-screen an older frontend — the worst outcome is a missing diagram.
   */
  test('ignores an unknown artifact type without breaking the answer', async ({ page, api }) => {
    api.json('/api/prax/ask', answerWith([
      { type: 'HOLOGRAM', id: 'x1', title: 'From the future' },
      position,
    ]))

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    // The prose survives, the known artifact still renders, the unknown one
    // simply is not there.
    await expect(page.getByText(/miss threats against pieces/)).toBeVisible()
    await expect(page.locator('[data-square="e2"]').first()).toBeVisible()
    await expect(page.getByText('From the future')).toHaveCount(0)
  })

  test('renders a move comparison with the cost beside the board', async ({ page, api }) => {
    api.json('/api/prax/ask', answerWith([{
      type: 'MOVE_COMPARISON',
      id: 'cmp-1',
      title: 'The position when it went wrong',
      fen: START_FEN,
      orientation: 'white',
      highlights: ['e2', 'e4'],
      arrows: [{ from: 'e2', to: 'e4', role: 'BEST' }],
      played_move: 'g6',
      best_move: 'Nb4',
      eval_before: 5.28,
      eval_after: -3.2,
      loss_pawns: 8.48,
      caption: null,
    }]))

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    await expect(page.locator('[data-square="e2"]').first()).toBeVisible()
    await expect(page.getByText('Your move')).toBeVisible()
    await expect(page.getByText('g6', { exact: true })).toBeVisible()
    await expect(page.getByText('8.48 pawns')).toBeVisible()
  })

  test('omits the cost when it was not measured', async ({ page, api }) => {
    api.json('/api/prax/ask', answerWith([{
      type: 'MOVE_COMPARISON', id: 'cmp-2', title: 'Position',
      fen: START_FEN, orientation: 'white', highlights: [], arrows: [],
      played_move: 'g6', best_move: 'Nb4',
      eval_before: null, eval_after: null, loss_pawns: null, caption: null,
    }]))

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    // Null means NOT MEASURED. Showing "0.00 pawns" would say the move cost
    // nothing, which is the opposite of unknown.
    await expect(page.getByText('Your move')).toBeVisible()
    await expect(page.getByText(/pawns/)).toHaveCount(0)
  })

  test('renders a chart as labelled bars', async ({ page, api }) => {
    api.json('/api/prax/ask', answerWith([{
      type: 'CHART', id: 'chart-1', title: 'Where your mistakes come from',
      chart_id: 'MISTAKES_BY_MOTIF', unit: 'mistakes',
      bars: [{ label: 'Positional', value: 154 }, { label: 'Hanging piece', value: 63 }],
      caption: null,
    }]))

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    await expect(page.getByText('Where your mistakes come from')).toBeVisible()
    await expect(page.getByText('Positional')).toBeVisible()
    await expect(page.getByText('154 mistakes')).toBeVisible()
  })

  test('renders a table with a header row', async ({ page, api }) => {
    api.json('/api/prax/ask', answerWith([{
      type: 'TABLE', id: 'table-1', title: 'Your openings',
      columns: ['Opening', 'Games', 'Win rate'],
      align: ['LEFT', 'RIGHT', 'RIGHT'],
      rows: [['Sicilian Defense', '21', '52%'], ['Italian Game', '5', '40%']],
      caption: null,
    }]))

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    await expect(page.getByRole('columnheader', { name: 'Opening' })).toBeVisible()
    await expect(page.getByRole('cell', { name: 'Sicilian Defense' })).toBeVisible()
    await expect(page.getByRole('cell', { name: '52%' })).toBeVisible()
  })

  test('can flip the board', async ({ page, api }) => {
    api.json('/api/prax/ask', answerWith([position]))

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    const board = page.locator('[data-square="a1"]').first()
    await board.waitFor()
    const before = await board.boundingBox()

    await page.getByRole('button', { name: 'Flip the board' }).click()

    // Re-orienting a diagram in your head is exactly the work a diagram should
    // save, so a1 must actually move to the other corner.
    await expect.poll(async () => {
      const after = await board.boundingBox()
      return after && before ? Math.abs(after.x - before.x) > 50 : false
    }).toBe(true)
  })

  test('renders the answer normally when there are no artifacts', async ({ page, api }) => {
    api.json('/api/prax/ask', answerWith([]))

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    await expect(page.getByText(/miss threats against pieces/)).toBeVisible()
    await expect(page.locator('[data-square]')).toHaveCount(0)
  })

  test('keeps the answer readable when artifacts are absent from the payload', async ({ page, api }) => {
    // An older backend that does not know about artifacts at all.
    const { artifacts, ...withoutField } = answerWith([])
    void artifacts
    api.json('/api/prax/ask', withoutField)

    const app = new AppPage(page)
    await app.goto('/')
    await ask(page)

    await expect(page.getByText(/miss threats against pieces/)).toBeVisible()
  })
})

// Referenced so the fixture import is not flagged as unused when the suite is
// trimmed; the default mock world is what makes the page render at all.
void data.PLAYER
