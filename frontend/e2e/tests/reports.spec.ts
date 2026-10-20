import { test, expect } from '../fixtures/test'
import * as reports from '../fixtures/reports'
import { drill } from '../fixtures/parity/states'
import * as data from '../fixtures/data'

/**
 * Phase 12: the Pattern Report and Training Plan, computed in code, as tabs
 * inside Insights. Each test names the design principle it holds the page to.
 */

test.describe('Pattern Report', () => {
  test('the old route opens the Insights tab', async ({ page }) => {
    await page.goto('/patterns')
    await expect(page).toHaveURL(/\/insights\/patterns$/)
    await expect(page.getByRole('link', { name: 'Pattern Report' })).toHaveAttribute('aria-current', 'page')
  })

  // These assert meaning (the region, its values, the visible caveat), not sentences:
  // a copy edit should not break a principle test (READABILITY_PLAN.md, D3).

  test('truthful labels: "costliest" is winning chances lost, and the rate is about all mistakes', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')

    const summary = page.getByRole('region', { name: 'Summary' })
    await expect(summary).toContainText(/costliest habit: ignoring your opponent's threat/i)
    // Where mistakes cluster, with its baseline, as a label rather than a sentence.
    await expect(summary).toContainText(/middlegame · 17\.6 vs 13\.6 per 100 moves/)
    // Said once: cause 1's stat line is not repeated in the summary.
    await expect(summary).not.toContainText('32 of 145')
    await expect(page.getByRole('region', { name: 'What goes wrong' })).toContainText(/ranked by winning chances lost/i)
    await expect(page.getByText('checked')).toHaveCount(0)
  })

  test('completeness: every cause and tactic is accounted for', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')

    const causes = page.getByRole('tabpanel', { name: 'By cause' })
    await expect(causes.getByRole('list', { name: 'The rest' })).toContainText('unclear 17')
    // The biggest bucket can be "no cause": called out, not buried in a footnote.
    await expect(causes).toContainText(/no cause found for 40 of 145 \(28%\)/i)

    // Tactics are the same mistakes grouped another way: a second view, not a second list.
    await page.getByRole('tab', { name: /tactic your opponent got/i }).click()
    await expect(causes).toBeHidden()
    const tactics = page.getByRole('tabpanel', { name: /tactic your opponent got/i })
    await expect(tactics.getByRole('list', { name: 'The rest' })).toContainText('pins 5')
    // What was found leads, and how much stays visible: it changes how the counts read.
    await expect(tactics).toContainText(/a tactic proven in 49 of 145/i)
    // One mistake can be both: the overlap is stated, one tap away, not left to look like a duplicate.
    const forks = tactics.getByRole('listitem', { name: 'Forks' })
    await forks.getByRole('button', { name: 'Details' }).click()
    await expect(forks).toContainText(/1 of 14 also under.*ignoring your opponent's threat/i)
  })

  /**
   * Hierarchy: an item has three levels. The title with one headline number,
   * the instruction, then three positions and one action. The share, blunders
   * and every other position are in Details.
   */
  test('an item reads in three levels; the rest is in its details', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')

    const threat = page.getByRole('listitem', { name: "Ignoring your opponent's threat" })
    await expect(threat).toContainText('−27% each')
    await expect(threat).toContainText('Name the threat before each move')
    await expect(threat.getByRole('button', { name: /show the position/ })).toHaveCount(3)
    await expect(threat).not.toContainText('32 of 145')
    await threat.getByRole('button', { name: 'Details' }).click()
    await expect(threat).toContainText(/32 of 145 \(22%\) · 19 blunders/)
  })

  /** Pink is the next action and the active state; links are neutral, so the button stands out. */
  test('accent discipline: the only pink is the next action and the active tabs', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')
    await expect(page.getByRole('tab', { name: 'By cause' })).toBeVisible()
    const pink = await page.evaluate(() => {
      const probe = document.createElement('div'); probe.style.color = 'var(--orchid)'; document.body.appendChild(probe)
      const rgb = getComputedStyle(probe).color; probe.remove()
      return Array.from(document.querySelectorAll<HTMLElement>('main *'))
        .filter(e => e.children.length === 0 && e.textContent?.trim())
        .filter(e => getComputedStyle(e).color === rgb)
        .map(e => e.textContent!.trim())
    })
    expect(pink.sort()).toEqual(['By cause', 'Pattern Report'])
  })

  test('where they happen leads with the finding, and only that bar is bright', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')

    const where = page.getByRole('region', { name: 'Where they happen' })
    await expect(where).toContainText('Most in the middlegame, moves 11-20')
    await expect(where.locator('[data-highlight]')).toHaveText([/middlegame/, /moves 11-20/])
  })

  /** Five games clear the threshold to be listed; whether the gap is more than chance is said too. */
  test('an opening whose gap could be chance is marked low sample', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')

    await expect(page.getByRole('row', { name: /Center Game/ })).toContainText(/low sample/i)
  })

  test('recognition: a position shows its move and loss, and its context one tap away', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')

    const threat = page.getByRole('listitem', { name: "Ignoring your opponent's threat" })
    const chip = threat.getByRole('button', { name: /^23\.Qxc6, −34%/ })
    await expect(chip).toHaveAttribute('aria-expanded', 'false')
    await chip.click()
    const panel = page.getByRole('dialog', { name: /23\.Qxc6/ })
    await expect(panel).toContainText('White to move')
    await expect(panel).toContainText(/Scandinavian Defense · 6 Sep · −34%/)
    await expect(panel.getByRole('link', { name: 'Open game' })).toHaveAttribute('href', /\/games\/7c3c9a2e-[^?]+\?ply=45$/)
    await expect(panel.getByRole('link', { name: 'Drill this' })).toHaveAttribute('href', /\/drills\?positions=/)
    // Non-modal: Escape closes it and gives focus back to the chip.
    await page.keyboard.press('Escape')
    await expect(panel).toHaveCount(0)
    await expect(chip).toBeFocused()

    await threat.getByRole('button', { name: 'Details' }).click()
    await threat.getByRole('button', { name: 'Show the other 2 positions' }).click()
    await expect(threat.getByRole('button', { name: /show the position/ })).toHaveCount(5)
  })

  test('compared to what: rates against your own rate, one threshold for too few', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')

    const where = page.getByRole('region', { name: 'Where they happen' })
    await expect(where).toContainText(/your rate, 13\.6/)
    await expect(where).toContainText('6.0 · 9 of 149 · few')         // one decimal everywhere; too few marked
    // The caveat stays visible: too little clock data to say anything about time trouble.
    await expect(where).toContainText(/time trouble: clock data on 4 of 145, fewer than 10/i)
    await expect(page.getByText(/your average: 4\.7 per game/i)).toBeVisible()
  })

  test('method sits behind one disclosure per section, closed by default', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')

    const where = page.getByRole('region', { name: 'Where they happen' })
    const method = where.getByText('How this is measured')
    await expect(where.getByText(/never called the worst/)).toBeHidden()
    await method.click()
    await expect(where.getByText(/never called the worst/)).toBeVisible()

    // Drills: short by default, the full sentence in the item's details.
    const threat = page.getByRole('listitem', { name: "Ignoring your opponent's threat" })
    await expect(threat).toContainText('Name the threat before each move')
    await expect(threat).not.toContainText("name what your opponent's last move threatens")
    await threat.getByRole('button', { name: 'Details' }).click()
    await expect(threat).toContainText("name what your opponent's last move threatens")
  })

  test('next action: one solid button, which depends on whether a plan exists', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')
    await expect(page.locator('main button.solid')).toHaveCount(1)
    await expect(page.locator('main button.solid')).toHaveText('Make my plan')

    api.json('/api/training-plan', reports.trainingPlan)
    await page.reload()
    await expect(page.locator('main button.solid')).toHaveText('Train this week →')
    await page.locator('main button.solid').click()
    await expect(page).toHaveURL(/\/insights\/training$/)
  })

  test('each cause hands off to a drill of exactly its positions', async ({ page, api }) => {
    api.json('/api/patterns', reports.pattern)
    await page.goto('/insights/patterns')

    const drill = page.getByRole('listitem', { name: "Ignoring your opponent's threat" }).getByRole('link', { name: 'Drill 5 →' })
    const href = await drill.getAttribute('href')
    expect(decodeURIComponent(href!.split('positions=')[1]).split(',')).toHaveLength(5)
  })

  test.describe('on a phone', () => {
    test.use({ viewport: { width: 390, height: 844 }, hasTouch: true })

    test('a tap opens the position, docked to the bottom of the screen', async ({ page, api }) => {
      api.json('/api/patterns', reports.pattern)
      await page.goto('/insights/patterns')

      await page.getByRole('button', { name: /^23\.Qxc6, −34%/ }).first().tap()
      const panel = page.getByRole('dialog', { name: /23\.Qxc6/ })
      await expect(panel).toContainText('White to move')
      const box = (await panel.boundingBox())!
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(390)
      expect(box.y + box.height).toBeLessThanOrEqual(844)
      // A tap elsewhere closes it.
      await page.getByRole('heading', { name: 'What goes wrong' }).tap()
      await expect(panel).toHaveCount(0)
    })
  })

  test('with no report yet, it offers to build one', async ({ page, api }) => {
    let built = false
    api.set('/api/patterns/recompute', () => {
      built = true
      return { status: 200, body: reports.pattern }
    })
    await page.goto('/insights/patterns')

    await page.getByRole('button', { name: 'Rebuild' }).click()
    await expect.poll(() => built).toBe(true)
  })
})

test.describe('Training Plan', () => {
  test('feedback and state: dates, what is done, and what is next', async ({ page, api }) => {
    api.json('/api/training-plan', reports.trainingPlan)
    await page.goto('/training')
    await expect(page).toHaveURL(/\/insights\/training$/)

    const week = page.getByRole('region', { name: 'This week' })
    await expect(week).toContainText(/16 Oct to 22 Oct.*2 sessions.*20 minutes/)
    await expect(week).toContainText(/1 of 2 done.*next: session 2/i)
    const first = page.getByRole('listitem', { name: 'Session 1' })
    await expect(first).toContainText(/done 17 oct · 2 of 3 solved/i)
    await expect(first.getByRole('button', { name: 'Do it again' })).toBeVisible()
    // What "Make a new plan" does to this one is said next to the button.
    await expect(page.getByText(/keeps this plan in your history/i)).toBeVisible()
    // The next session holds the only solid button.
    await expect(page.locator('main button.solid')).toHaveCount(1)
    await expect(page.locator('main button.solid')).toHaveText('Start session 2')
  })

  test('a session has a goal and its focus as chips, and starts on exactly its positions', async ({ page, api }) => {
    api.json('/api/training-plan', reports.trainingPlan)
    let finished: unknown = null
    api.set(/^\/api\/training-plan\/t1\/sessions\/2$/, req => {
      finished = req.postDataJSON()
      return { status: 200, body: reports.trainingPlan }
    })
    await page.goto('/insights/training')

    const second = page.getByRole('listitem', { name: 'Session 2' })
    await expect(second.getByRole('heading', { name: 'Find your own chances first' })).toBeVisible()
    await second.getByRole('button', { name: 'Start session 2' }).click()
    await expect(page).toHaveURL(/\/drills\?positions=.*&session=2&plan=t1$/)
    expect(api.requestsMatching(/\/api\/drills\?positions=/).length).toBeGreaterThan(0)
    expect(finished).toBeNull()
  })

  test('one format for one fact; why a focus was chosen is one tap away, not a second report', async ({ page, api }) => {
    api.json('/api/training-plan', reports.trainingPlan)
    await page.goto('/insights/training')

    // The priority order survives as one line, and the tactic is labelled in words, not "+".
    const week = page.getByRole('region', { name: 'This week' })
    await expect(week).toContainText(/focus.*ignoring your opponent's threat, then missing your own chances · tactic: forks/i)
    await expect(page.getByRole('heading', { name: 'What the sessions work on' })).toHaveCount(0)

    const chip = page.getByRole('listitem', { name: 'Session 1' }).getByRole('button', { name: /^Ignoring your opponent's threat: why/ })
    await chip.click()
    const panel = page.getByRole('dialog', { name: "Ignoring your opponent's threat" })
    await expect(panel).toContainText('Priority 1 of 2')
    await expect(panel).toContainText('32 of 145 (22%) · 19 blunders · −27% winning chance each')
    await expect(panel).toContainText("name what your opponent's last move threatens")
    await expect(panel.getByRole('link', { name: /see in pattern report/i })).toHaveAttribute('href', '/insights/patterns')
    await page.keyboard.press('Escape')

    await page.getByRole('button', { name: /^Forks: why/ }).click()
    await expect(page.getByRole('dialog', { name: 'Forks' })).toContainText(/tactic your opponent got/i)
    await expect(page.getByRole('dialog', { name: 'Forks' })).not.toContainText('+')
  })

  test('the end of a plan session hands off to the next one, then to Progress (F5)', async ({ page, api }) => {
    const GAME = '7c3c9a2e-1111-4a22-8b33-445566778899'
    api.json('/api/training-plan', reports.trainingPlan)
    api.json(/^\/api\/drills/, [drill('d1', 'Rxf8', 36)])
    api.set(/^\/api\/training-plan\/t1\/sessions\/\d+(\/answers)?$/, { status: 204 })
    // Session 1 is done in the fixture: finishing it again leaves session 2 next.
    await page.goto(`/drills?positions=${GAME}:36&session=1&plan=t1`)
    await page.getByRole('button', { name: 'Show answer' }).click()
    await page.getByRole('button', { name: /next drill/i }).click()
    const status = page.getByRole('status')
    await expect(status).toContainText(/session 1 done: 0 of 1 solved/i)
    await expect(status.getByRole('button', { name: 'Start session 2' })).toHaveClass(/solid/)
    await expect(status.getByRole('link', { name: 'Back to the plan' })).toBeVisible()

    // With nothing left this week, the next question is whether it is working.
    await page.goto(`/drills?positions=${GAME}:36&session=2&plan=t1`)
    await page.getByRole('button', { name: 'Show answer' }).click()
    await page.getByRole('button', { name: /next drill/i }).click()
    await page.getByRole('status').getByRole('button', { name: 'See progress' }).click()
    await expect(page).toHaveURL(/\/progress$/)
  })

  test('a plan session keeps every answer per position, not only its total (F12)', async ({ page, api }) => {
    const GAME = '7c3c9a2e-1111-4a22-8b33-445566778899'
    api.json(/^\/api\/drills/, [drill('d1', 'Rxf8', 36)])
    const answers: unknown[] = []
    api.set(/^\/api\/training-plan\/t1\/sessions\/2\/answers$/, req => { answers.push(req.postDataJSON()); return { status: 204 } })
    await page.goto(`/drills?positions=${GAME}:36&session=2&plan=t1`)

    await page.getByRole('button', { name: 'Show answer' }).click()
    await expect.poll(() => answers.length).toBe(1)
    expect(answers[0]).toMatchObject({ game_id: GAME, ply: 36, move_error_id: 'd1', correct: false, revealed: true, wrong_tries: 0 })
  })

  test('with nothing to plan from, it says so', async ({ page, api }) => {
    api.set('/api/training-plan/generate', { status: 409, body: { error: 'No analysed mistakes for player yet.' } })
    await page.goto('/insights/training')

    await page.getByRole('button', { name: 'Make my plan' }).click()
    await expect(page.getByRole('alert')).toContainText('nothing to plan from yet')
  })
})

test.describe('Insights Overview', () => {
  test('truthful labels: a mistake count says what it counts, and links to the rate', async ({ page }) => {
    await page.goto('/insights')
    const phase = page.locator('.card', { has: page.getByRole('heading', { name: 'Mistakes by Phase' }) })
    await expect(phase).toContainText(/all flagged/i)
    await expect(phase).toContainText(/74\.8 \(36%\)/)
    await expect(phase.getByRole('link', { name: /per 100 moves/ })).toHaveAttribute('href', '/insights/patterns#where-they-happen')
  })

  test('verified or silent: "positional" is counted, never shown as a tactic', async ({ page }) => {
    await page.goto('/insights')
    const tactics = page.locator('.card', { has: page.getByRole('heading', { name: 'Missed Tactics' }) })
    await expect(tactics).toContainText('Hanging Piece')
    await expect(tactics).toContainText(/the rest: positional 154 \(no tactic proven\)/i)
  })

  test('caveats stay visible: an unanalysed speed and the first points of the average', async ({ page }) => {
    await page.goto('/insights')
    await expect(page.getByRole('group', { name: 'Time control' })).toContainText(/bullet · 64 · results only/i)
    await expect(page.getByText(/first 9 points: fewer games/i)).toBeVisible()
    await expect(page.getByText(/dates are to scale/i)).toBeHidden()
    await page.getByText('How this is measured').first().click()
    await expect(page.getByText(/dates are to scale/i)).toBeVisible()
  })

  test('pink only for what can be clicked: no data drawn in the accent', async ({ page }) => {
    await page.goto('/insights')
    const pinkData = await page.evaluate(() => {
      const accent = getComputedStyle(document.documentElement).getPropertyValue('--orchid').trim()
      const probe = document.createElement('div'); probe.style.color = accent; document.body.appendChild(probe)
      const rgb = getComputedStyle(probe).color; probe.remove()
      return Array.from(document.querySelectorAll<HTMLElement>('main .card *'))
        .filter(e => !e.closest('a, button') && e.children.length === 0)
        .filter(e => getComputedStyle(e).color === rgb || getComputedStyle(e).backgroundColor === rgb)
        .map(e => e.textContent?.trim() || e.outerHTML.slice(0, 60))
    })
    expect(pinkData).toEqual([])
  })

  // U6, N8: the trend chart's story, in one line, before the figures.
  test('a takeaway says where the Elo went and whether the play went with it', async ({ page, api }) => {
    const trend = Array.from({ length: 30 }, (_, i) => ({
      date: `2026-07-${String(i + 1).padStart(2, '0')}`,
      accuracy: i < 15 ? 86 : 80, moving_avg: i < 15 ? 86 : 80,
      rating: i === 4 ? 880 : i < 15 ? 860 : 700,
    }))
    api.json('/api/insights', { ...data.insights, accuracy_trend: trend })
    await page.goto('/insights')
    const line = page.getByLabel('Takeaway')
    await expect(line).toHaveText(/Your Elo has fallen from 880 on 5 Jul to 700, and your play with it: accuracy fell from \d+% to 80%\./)
    await expect(line.getByRole('link', { name: 'What goes wrong →' })).toHaveAttribute('href', '/insights/patterns')
  })

  test('with too few games for a story, there is no takeaway', async ({ page }) => {
    await page.goto('/insights')
    await expect(page.getByRole('heading', { name: 'Time of Day' })).toBeVisible()
    await expect(page.getByLabel('Takeaway')).toHaveCount(0)
  })

  // U6, N9: 44% beside your own 47% is your normal, not an alarm.
  test('win rates are read against your own rate, not against 50%', async ({ page }) => {
    await page.goto('/insights')
    // 37 wins in 79 games across the day.
    await expect(page.getByText('Win % · ±50 bands · line: your 46.8%').first()).toBeVisible()
    const sicilian = page.getByRole('row', { name: /Sicilian Defense/ }).getByRole('cell', { name: '52.4%' })
    const nimzo = page.getByRole('row', { name: /Nimzovich-Larsen/ }).getByRole('cell', { name: '33.3%' })
    const colour = (l: typeof sicilian) => l.evaluate(e => getComputedStyle(e).color)
    const neutral = await page.evaluate(() => getComputedStyle(document.body).color)
    expect(await colour(sicilian)).toBe(neutral)
    expect(await colour(nimzo)).not.toBe(neutral)
  })

  // N14: the Drill tile was a link dressed as a figure; the drill is now the thrown-away positions.
  test('no link dressed as a stat tile: the drill is of the positions where wins slipped', async ({ page }) => {
    await page.goto('/insights')
    await expect(page.getByText('♟ Drill →')).toHaveCount(0)
    await expect(page.getByText('Win % after a loss')).toBeVisible()
    await expect(page.getByRole('link', { name: 'Drill 1 position →' }))
      .toHaveAttribute('href', '/drills?positions=a1000000-0000-4000-8000-000000000002%3A47')
  })

  test('no voids: no card holds more than its padding of empty space', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 })
    await page.goto('/insights')
    await expect(page.locator('.recharts-surface').first()).toBeVisible()
    const voids = await page.evaluate(() => Array.from(document.querySelectorAll<HTMLElement>('main .card')).map(c => {
      const box = c.getBoundingClientRect()
      let ink = box.top
      c.querySelectorAll('*').forEach(e => {
        const b = e.getBoundingClientRect()
        if (e.children.length === 0 && b.height > 0) ink = Math.max(ink, b.bottom)
      })
      return { card: c.textContent?.slice(0, 24), gap: Math.round(box.bottom - ink) }
    }).filter(v => v.gap > 40))
    expect(voids).toEqual([])
  })
})

test.describe('Today', () => {
  test('one solid button: start the session', async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('main button.solid')).toHaveCount(1)
    await expect(page.locator('main button.solid')).toHaveText(/start session/i)
  })

  test('evidence is a disclosure, and hands off to where the claim is measured (F3)', async ({ page }) => {
    await page.goto('/')
    const toggle = page.getByRole('button', { name: /show evidence/i })
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await toggle.click()
    await expect(page.getByText('97 of 154')).toBeVisible()
    await page.getByRole('link', { name: /where your mistakes happen/i }).click()
    await expect(page).toHaveURL(/\/insights\/patterns#where-they-happen$/)
  })

  test('the week names its dates, so no day needs a hover', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByText('17 Aug – 23 Aug')).toBeVisible()
    await expect(page.getByRole('img', { name: '2026-08-21 — examined' })).toBeVisible()
  })
})

test.describe('Flow: every screen hands off', () => {
  const SESSION = '9c1f0b7a-3d4e-4a21-8f60-1b2c3d4e5f60'

  test('the end of a drill session reports what was solved and goes on to Progress (F4)', async ({ page, api }) => {
    api.json(`/api/sessions/${SESSION}`, { id: SESSION, cards_total: 2, cards_completed: 2, budget_minutes: 12,
      completed: true, started_at: '2026-08-22T09:00:00Z', completed_at: '2026-08-22T09:10:00Z', cards_solved: 1 })
    await page.goto(`/session/${SESSION}`)
    await expect(page.getByRole('status')).toHaveText('Session complete')
    await expect(page.getByText(/2 cards reviewed · 1 solved first time/)).toBeVisible()
    await expect(page.locator('button.solid')).toHaveText('See progress')
    await page.locator('button.solid').click()
    await expect(page).toHaveURL(/\/progress$/)
  })

  test('a drill card links to its game, and names the answer in SAN (F8)', async ({ page, api }) => {
    api.json(`/api/sessions/${SESSION}`, { id: SESSION, cards_total: 2, cards_completed: 0, budget_minutes: 12,
      completed: false, started_at: '2026-08-22T09:00:00Z', completed_at: null })
    api.json(`/api/sessions/${SESSION}/next`, {
      id: 'c1', fen_position: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4',
      move_played: 'Ng5', better_move: 'd2d3', severity: 'MISTAKE', tactical_motif: 'FORK', game_phase: 'OPENING',
      player_color: 'white', explanation: null, status: 'REVIEW', interval_days: 1, due_date: '2026-08-22',
      review_count: 2, lapse_count: 0, game_id: '7c3c9a2e-1111-4a22-8b33-445566778899',
    })
    await page.goto(`/session/${SESSION}`)
    await page.getByRole('button', { name: 'Show answer' }).click()
    await expect(page.getByText('d3', { exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: /open the game/i })).toHaveAttribute('href', '/games/7c3c9a2e-1111-4a22-8b33-445566778899')
  })

  test('a game opened at one move offers a drill of exactly that position (F7)', async ({ page, api }) => {
    const GAME = '7c3c9a2e-1111-4a22-8b33-445566778899'
    api.json(/^\/api\/analysis\/[0-9a-f-]+$/, [(await import('../fixtures/data')).scholarsMistakeVerified])
    await page.goto(`/games/${GAME}?ply=6`)
    await expect(page.getByRole('link', { name: /drill this position/i })).toHaveAttribute('href', `/drills?positions=${GAME}:6`)
    await expect(page.getByText('Engine best: g6')).toBeVisible()
  })
})

test.describe('Closing the loop: since this plan', () => {
  test('verified or silent: with too little play since the plan, it says so instead of a change (F10)', async ({ page, api }) => {
    api.json('/api/training-plan', reports.trainingPlan)
    await page.goto('/insights/training')
    await page.getByRole('listitem', { name: 'Session 1' }).getByRole('button', { name: /^Ignoring your opponent's threat: why/ }).click()
    const panel = page.getByRole('dialog', { name: "Ignoring your opponent's threat" })
    await expect(panel).toContainText(/since 16 oct.*too little play to compare: 4 of 10 serious mistakes/i)
    await expect(panel).not.toContainText('1.4')
  })

  test('with enough play, the focus rate since the plan against before, per 100 moves (F10)', async ({ page, api }) => {
    api.json('/api/training-plan', reports.trainingPlan)
    api.json(/^\/api\/training-plan\/[^/]+\/since$/, reports.sinceEnough)
    await page.goto('/insights/training')
    await page.getByRole('listitem', { name: 'Session 1' }).getByRole('button', { name: /^Ignoring your opponent's threat: why/ }).click()
    await expect(page.getByRole('dialog', { name: "Ignoring your opponent's threat" }))
      .toContainText(/since 16 oct.*1\.3 per 100 moves, from 3\.0 · 6 games/i)
  })

  test('recall on a session\'s positions counts only those in the review deck (F11)', async ({ page, api }) => {
    api.json('/api/training-plan', reports.trainingPlan)
    await page.goto('/insights/training')
    await expect(page.getByRole('listitem', { name: 'Session 1' })).toContainText(/reviews since 16 oct: 3 of 4 correct · 2 of 3 positions in your deck/i)
    // None tracked reads as "not tracked", never as "nothing recalled".
    await expect(page.getByRole('listitem', { name: 'Session 2' })).toContainText('Not in your review deck')
  })
})
