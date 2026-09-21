import { test, expect } from '../fixtures/test'
import { PlayPage } from '../pages/PlayPage'
import * as data from '../fixtures/data'

/** Anything that is not the literal string "undefined" and looks like a UUID. */
const UUID_IN_PATH = /\/api\/play\/session\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\//

test.describe('Play & Improve', () => {
  test('shows the opponent rationale before a game starts', async ({ page }) => {
    const play = new PlayPage(page)
    await play.open()

    await expect(play.opponentPanel).toBeVisible()
    // The feature's whole claim is that the opponent comes from measured history,
    // so the counts must actually reach the screen.
    await expect(play.main).toContainText('Nimzovich-Larsen Attack')
    await expect(play.main).toContainText('97 of them')
    await expect(play.startButton).toBeEnabled()
  })

  test('starts a game and shows the board', async ({ page }) => {
    const play = new PlayPage(page)
    await play.open()

    await play.startButton.click()

    await expect(play.square('e2')).toBeVisible()
    await expect(play.boardStatus).toBeVisible()
    await expect(play.resignButton).toBeVisible()
  })

  /**
   * REGRESSION — the /session/undefined/move bug.
   *
   * Jackson's SNAKE_CASE naming strategy does not apply to Map keys, so the
   * controller shipped `sessionId` while PlaySession reads `session_id`. The id
   * came back undefined and every subsequent call went to
   * /api/play/session/undefined/{move,undo,resign}, which the server rejected
   * with 400 "Invalid UUID string: undefined".
   *
   * Resign is used rather than a drag because it exercises the identical code
   * path — session.session_id interpolated into the URL — through a plain button
   * click, with none of the flake of HTML5 drag-and-drop.
   */
  test('sends a real session id, never the string "undefined"', async ({ page, api }) => {
    const play = new PlayPage(page)
    await play.open()

    await play.startButton.click()
    await expect(play.resignButton).toBeVisible()

    await play.resignButton.click()

    const resignCalls = api.requestsMatching(/\/resign$/)
    expect(resignCalls, 'the resign request never fired').toHaveLength(1)

    expect(
      resignCalls[0],
      'session id was lost between the API response and the request URL — ' +
        'check that the server serialises session_id, not sessionId',
    ).not.toContain('undefined')

    expect(resignCalls[0]).toMatch(UUID_IN_PATH)
    expect(resignCalls[0]).toBe(`/api/play/session/${data.SESSION_ID}/resign`)
  })

  test('undo also carries the session id, and warns the game is now unrated', async ({ page, api }) => {
    const play = new PlayPage(page)
    await play.open()

    await play.startButton.click()
    await expect(play.undoButton).toBeVisible()

    await play.undoButton.click()

    const undoCalls = api.requestsMatching(/\/undo$/)
    expect(undoCalls).toHaveLength(1)
    expect(undoCalls[0]).not.toContain('undefined')
    expect(undoCalls[0]).toBe(`/api/play/session/${data.SESSION_ID}/undo`)

    // A takeback must say it costs the game its rating — counting it silently
    // would corrupt every later comparison.
    await expect(play.takebackNotice).toBeVisible()
  })

  test('resigning moves into analysis and starts polling for the report', async ({ page, api }) => {
    const play = new PlayPage(page)
    await play.open()

    await play.startButton.click()
    await play.resignButton.click()

    await expect(play.analysingNotice).toBeVisible()

    // The report is not ready yet; the UI must wait rather than invent one.
    await expect.poll(
      () => api.requestsMatching(/^\/api\/play\/report\//).length,
      { timeout: 10_000 },
    ).toBeGreaterThan(0)
  })

  test('hides the feature when the engine is not running', async ({ page, api }) => {
    api.json('/api/play/status', { available: false })

    const play = new PlayPage(page)
    await play.open()

    await expect(play.engineUnavailableNotice).toBeVisible()
    await expect(play.startButton).toHaveCount(0)
  })

  test('reports a failure to start rather than silently doing nothing', async ({ page, api }) => {
    api.fail('/api/play/session', 503, 'Chess engine is not running.')

    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()

    await expect(play.main.getByText(/API error 503/)).toBeVisible()
    // The board must not appear for a game that was never created.
    await expect(play.square('e2')).toHaveCount(0)
  })
})

/**
 * After resigning, the app polls for a report that takes ~a minute to appear.
 *
 * A static "Analysing…" is indistinguishable from a hang — which is exactly the
 * complaint this covers. What must hold is that something VISIBLY moves, and
 * that nothing on screen claims a percentage the engine never reported.
 */
test.describe('analysis progress', () => {
  test('shows a moving bar and a running clock while the engine works', async ({ page, api }) => {
    // A library analysis running at the same time. The practice panel must
    // ignore it completely: practice games go through practiceExecutor and are
    // not part of that run, so "47 / 101" would be a real number about an
    // entirely different job.
    api.json('/api/analysis/progress', data.analysisRunning)

    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    await expect(play.resignButton).toBeVisible()
    await play.resignButton.click()

    const bar = page.getByRole('progressbar', { name: /Analysing/i })
    await expect(bar).toBeVisible({ timeout: 15_000 })
    await expect(bar).toHaveAttribute('aria-busy', 'true')

    // Indeterminate: no aria-valuenow, because there is no value to report.
    await expect(bar).not.toHaveAttribute('aria-valuenow', /.*/)

    // The fill is actually animating, not a static coloured strip.
    const anim = await bar.locator('div').first()
      .evaluate(el => getComputedStyle(el).animationName)
    expect(anim).toBe('prax-fill-sweep')

    // Nothing from the unrelated library run leaked in.
    await expect(play.main).not.toContainText('47')
    await expect(play.main).not.toContainText('101')
    await expect(play.main).not.toContainText('%')
  })

  test('the clock advances, so a hang is distinguishable from work', async ({ page, api }) => {
    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    await expect(play.resignButton).toBeVisible()
    await play.resignButton.click()

    const clock = play.main.getByText(/^\d+s$/)
    await expect(clock).toBeVisible({ timeout: 15_000 })
    const first = parseInt((await clock.textContent()) ?? '0', 10)

    await expect
      .poll(async () => parseInt((await clock.textContent()) ?? '0', 10), { timeout: 15_000 })
      .toBeGreaterThan(first)
  })
})
