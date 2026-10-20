import { test, expect } from '../fixtures/test'
import { PlayPage } from '../pages/PlayPage'
import * as data from '../fixtures/data'

/** Anything that is not the literal string "undefined" and looks like a UUID. */
const UUID_IN_PATH = /\/api\/play\/session\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\//

test.describe('Play & Improve', () => {
  test('shows the opponent rationale before a game starts', async ({ page }) => {
    const play = new PlayPage(page)
    await play.open()

    // The feature's whole claim is that the opponent comes from measured history,
    // so the figures must reach the screen, each next to its baseline.
    await expect(play.takeaway).toHaveText('Next game: Center Game as Black · engine skill 9 of 20')
    await expect(play.opponentPanel).toContainText(/Center Game · 6\.0 vs your 4\.7 serious mistakes per game · 5 games/)
    await expect(play.opponentPanel).toContainText(/9 of 20 · from your 68% average accuracy/)
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

  test('the clock advances, so a hang is distinguishable from work', async ({ page }) => {
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

/** The Play redesign (docs/design/PLAY_PLAN.md): each test names the finding or principle it holds. */
test.describe('Play & Improve: the next game first', () => {
  test('principle 10: Start is the only solid button, above the fold', async ({ page }) => {
    const play = new PlayPage(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await play.open()
    await expect(play.main.locator('button.solid')).toHaveCount(1)
    await expect(play.startButton).toHaveClass(/solid/)
    await expect(play.startButton).toBeInViewport()
  })

  test('finding 2: the weaker colour is named, with the figures that decided it', async ({ page }) => {
    const play = new PlayPage(page)
    await play.open()
    await expect(play.colourSelect).toContainText('Weaker colour: Black')
    await expect(play.main).toContainText('Black · accuracy 64.9% vs 70.1% as White · 43 and 58 games')
  })

  test('finding 7: a strength at the cap says it is the cap', async ({ page, api }) => {
    api.json(/^\/api\/play\/preview/, {
      ...data.opponentProfile, skill_level: 14,
      skill: { level: 14, scale: 20, min: 1, max: 14, calibrated: 15, capped: true, requested: false, accuracy: 83 },
    })
    const play = new PlayPage(page)
    await play.open()
    await expect(play.opponentPanel).toContainText('14 of 20 · the cap; your 83% average maps to 15')
  })

  test('finding 15: watch for is the report\'s, labelled as not targeted, one tap away', async ({ page }) => {
    const play = new PlayPage(page)
    await play.open()
    const watch = play.main.getByRole('group', { name: 'Watch for' })
    await expect(watch.getByText(/17\.6 vs 13\.6 per 100 moves/)).toBeHidden()
    await watch.getByText('Watch for').click()
    await expect(watch).toContainText(/middlegame · 17\.6 vs 13\.6 per 100 moves/)
    await expect(watch).toContainText("Ignoring your opponent's threat · 32 of 145 (22%)")
    await expect(watch).toContainText(/doesn't aim at these/)
    await expect(watch.getByRole('link', { name: /Pattern Report/ })).toHaveAttribute('href', '/insights/patterns')
  })

  test('I1: a chosen strength is sent with the game, the recommended one preselected', async ({ page, api }) => {
    const starts: unknown[] = []
    api.set('/api/play/session', req => { starts.push(req.postDataJSON()); return { status: 200, body: data.playSession } })
    const play = new PlayPage(page)
    await play.open()
    await expect(play.strengthSelect).toHaveValue('')
    await expect(play.strengthSelect).toContainText('Strength: recommended (9)')
    await play.strengthSelect.selectOption('12')
    await play.startButton.click()
    await expect.poll(() => starts.length).toBe(1)
    expect(starts[0]).toMatchObject({ skill: 12 })
  })

  test('I2: after a game, drill exactly that game\'s mistakes', async ({ page, api }) => {
    api.json(/^\/api\/play\/report\//, data.analysedReport)
    const play = new PlayPage(page)
    await play.open()
    await play.startButton.click()
    await play.resignButton.click()
    const drill = play.main.getByRole('link', { name: /Drill this game's mistakes \(1\)/ })
    await expect(drill).toHaveAttribute('href', `/drills?positions=${encodeURIComponent(`${data.PRACTICE_GAME_ID}:4`)}`)
  })

  test('a new player sees what will shape the opponent, and where their games are', async ({ page, api }) => {
    api.json(/^\/api\/play\/preview/, {
      ...data.opponentProfile, personalised: false, opening: null, watch_for: null, skill_level: 5,
      skill: { level: 5, scale: 20, min: 1, max: 14, calibrated: null, capped: false, requested: false, accuracy: null },
      colour: { weaker: 'white', basis: 'too few games', min_games: 5,
                white: { games: 0, accuracy: null, win_pct: 0 }, black: { games: 0, accuracy: null, win_pct: 0 } },
    })
    const play = new PlayPage(page)
    await play.open()
    await expect(play.takeaway).toHaveText('Next game: default opponent as White · engine skill 5 of 20')
    await expect(play.main.getByRole('link', { name: 'Your games →' })).toHaveAttribute('href', '/library')
    await expect(play.opponentPanel).toHaveCount(0)
  })

  test.describe('on a phone', () => {
    test.use({ viewport: { width: 390, height: 844 }, hasTouch: true })
    test('principle 10: Start is above the fold at 390px', async ({ page }) => {
      const play = new PlayPage(page)
      await play.open()
      await expect(play.startButton).toBeInViewport()
    })
  })
})
