import { useCallback, useEffect, useRef, useState } from 'react'
import { TeachingEmpty } from '../components/TeachingEmpty'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { ThemedBoard } from '../components/ThemedBoard'
import { Chess, type Square } from 'chess.js'
import { api } from '../api/client'
import type {
  GameAnalysisProgress, AnalysisStage, ImprovementReport, MoveResult,
  OpponentProfile, PlaySession,
} from '../api/types'
import { PracticeSoFar } from '../components/PracticePatterns'
import { Disclose, drillLink, linkStyle, muted, sectionLabel } from '../components/report/ReportParts'
import { Page } from '../components/Page'
import { praxBus, praxInteract } from '../prax/PraxHost'
import { BoardSplit } from '../components/BoardSplit'

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'

/**
 * How long to keep asking for a report before giving up on the analysis.
 *
 * 100s was enough back when the server called a game "analysed" as soon as a
 * row existed for it — that check returned almost immediately and the budget
 * never mattered. Now the report waits for the engine to actually finish, so
 * this is the real ceiling on a Stockfish pass over a full game. Five minutes,
 * with the bar moving the whole time.
 */
const REPORT_POLL_MS = 2500
const REPORT_MAX_TRIES = 120

/** What each pipeline stage is actually doing, in the player's terms. */
const STAGE_LABEL: Record<AnalysisStage, string> = {
  SWEEPING: 'reading position',
  ENRICHING: 'studying mistake',
  EXPLAINING: 'writing up mistake',
}

/**
 * SAN string to full-move rows. The server stores the game as one space-separated
 * string, so the pairing happens here rather than being carried on the wire.
 */
function toMoveRows(san: string): { n: number; white?: string; black?: string }[] {
  const moves = san.trim().split(/\s+/).filter(Boolean)
  const rows: { n: number; white?: string; black?: string }[] = []
  for (let i = 0; i < moves.length; i += 2) {
    rows.push({ n: i / 2 + 1, white: moves[i], black: moves[i + 1] })
  }
  return rows
}

function MoveList({ san, playerColor }: { san: string; playerColor: string }) {
  const rows = toMoveRows(san)
  const total = san.trim().split(/\s+/).filter(Boolean).length
  const playerIsWhite = playerColor !== 'black'

  // Which half-move was the most recent, so the eye lands on it.
  const lastIsWhite = total % 2 === 1
  const lastRow = Math.ceil(total / 2)

  function cell(move: string | undefined, isWhite: boolean, n: number) {
    if (!move) return <span style={{ minWidth: 68 }} />
    const mine = isWhite === playerIsWhite
    const isLast = n === lastRow && isWhite === lastIsWhite
    return (
      <span
        style={{
          minWidth: 68,
          padding: '1px 6px',
          borderRadius: 3,
          color: mine ? 'var(--text)' : 'var(--text-tertiary)',
          fontWeight: mine ? 600 : 400,
          background: isLast ? 'var(--accent-wash)' : 'transparent',
        }}
      >
        {move}
      </span>
    )
  }

  return (
    <div style={{
      display: 'grid',
      gridTemplateColumns: 'repeat(auto-fill, minmax(168px, 1fr))',
      gap: '2px 14px',
      fontFamily: 'var(--font-mono, monospace)',
      fontSize: '0.76rem',
      maxHeight: 220,
      overflowY: 'auto',
    }}>
      {rows.map(r => (
        <div key={r.n} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <span style={{
            minWidth: 24, textAlign: 'right', color: 'var(--text-tertiary)', opacity: 0.7,
          }}>
            {r.n}.
          </span>
          {cell(r.white, true, r.n)}
          {cell(r.black, false, r.n)}
        </div>
      ))}
    </div>
  )
}

const PIECE_VALUE: Record<string, number> = { p: 1, n: 3, b: 3, r: 5, q: 9, k: 0 }

/** "even", "you +2" or "you −1": the material balance in a position, from your side. */
function materialText(fen: string, you: string) {
  let white = 0, black = 0
  for (const ch of fen.split(' ')[0]) {
    const v = PIECE_VALUE[ch.toLowerCase()]
    if (v == null) continue
    if (ch === ch.toUpperCase()) white += v
    else black += v
  }
  const d = you === 'black' ? black - white : white - black
  return d === 0 ? 'even' : `you ${d > 0 ? '+' : '−'}${Math.abs(d)}`
}

function Panel({ title, label, children }: { title?: string; label?: string; children: React.ReactNode }) {
  return (
    <div className="card" style={{ padding: '18px 20px' }}>
      {title && (
        <div style={{ fontSize: '0.8rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: 10 }}>
          {title}
        </div>
      )}
      {/* A micro-label, as the pre-game sections use. */}
      {label && <div className="micro-label" style={{ marginBottom: 10 }}>{label}</div>}
      {children}
    </div>
  )
}

const label: React.CSSProperties = { paddingTop: 2 }
const value: React.CSSProperties = { margin: 0, fontSize: '0.82rem', lineHeight: 1.5 }
const rows: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: 'minmax(0, 8rem) minmax(0, 1fr)', gap: '8px 12px', margin: 0,
}

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)
const pct1 = (n: number | null | undefined) => (n == null ? '–' : `${Math.round(n * 10) / 10}%`)

/** The colour a game will be played as: the chosen one, or the weaker. */
function resolvedColour(choice: 'weak' | 'white' | 'black', profile?: OpponentProfile) {
  return choice === 'weak' ? profile?.colour?.weaker ?? 'white' : choice
}

/** Why that colour, with the figures that decided it (finding 2). */
function colourFigure(profile?: OpponentProfile): string | null {
  const c = profile?.colour
  if (!c) return null
  if (c.basis === 'too few games') {
    if (c.white.games === 0 && c.black.games === 0) return null
    return `White: too few games to compare (${c.min_games} as each)`
  }
  const weak = c[c.weaker], other = c[c.weaker === 'white' ? 'black' : 'white']
  const otherName = c.weaker === 'white' ? 'Black' : 'White'
  return c.basis === 'accuracy'
    ? `${cap(c.weaker)} · accuracy ${pct1(weak.accuracy)} vs ${pct1(other.accuracy)} as ${otherName} · ${weak.games} and ${other.games} games`
    : `${cap(c.weaker)} · wins ${pct1(weak.win_pct)} vs ${pct1(other.win_pct)} as ${otherName} · ${weak.games} and ${other.games} games`
}

/** The strength, with its scale and how it was set: a cap is said to be a cap (finding 7). */
function strengthText(profile: OpponentProfile): string {
  const k = profile.skill
  if (!k) return `engine skill ${profile.skill_level} of 20`
  const head = `${k.level} of ${k.scale}`
  if (k.requested) {
    return k.calibrated != null && k.accuracy != null
      ? `${head} · your choice; your ${Math.round(k.accuracy)}% average maps to ${Math.min(k.max, Math.max(k.min, k.calibrated))}`
      : `${head} · your choice`
  }
  if (k.accuracy == null || k.calibrated == null) return `${head} · a default`
  if (k.capped) {
    return `${head} · the ${k.calibrated > k.level ? 'cap' : 'lowest it goes'}; your ${Math.round(k.accuracy)}% average maps to ${k.calibrated}`
  }
  return `${head} · from your ${Math.round(k.accuracy)}% average accuracy`
}

/** The page's one line: the game you are about to play. */
function takeaway(profile: OpponentProfile, colour: string): string {
  const opening = profile.opening?.name ?? profile.target_opening
  const what = !profile.personalised ? 'default opponent' : opening ?? 'any opening'
  const level = profile.skill?.level ?? profile.skill_level
  return `Next game: ${what} as ${cap(colour)} · engine skill ${level} of ${profile.skill?.scale ?? 20}`
}

/**
 * What the opponent DOES: the opening it steers toward and its strength. The
 * phase and cause are not here, because the opponent does not aim at them
 * (PLAY_PLAN.md, finding 15); they are under Watch for.
 */
function ThisGame({ profile }: { profile: OpponentProfile }) {
  // A server from before the Play redesign sends only "Label: facts" lines.
  if (profile.skill == null) {
    return (
      <section aria-label="This game" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <span className="micro-label">This game</span>
        <dl style={rows} className="stack-on-phone">
          {profile.rationale.map((line, i) => {
            const at = line.indexOf(': ')
            const l = at > 0 && at < 30 ? line.slice(0, at) : null
            return (
              <div key={i} style={{ display: 'contents' }}>
                <dt className="micro-label" style={label}>{l ?? ''}</dt>
                <dd style={value}>{l ? line.slice(at + 2) : line}</dd>
              </div>
            )
          })}
        </dl>
      </section>
    )
  }
  const o = profile.opening
  if (!profile.personalised) {
    return (
      // U7: a new player's opponent says what it will become, and from what.
      <TeachingEmpty compact title="Shaped by your analysed games" label="Opponent"
                     note="No analysed games yet" link={{ to: '/library', text: 'Your games →' }} />
    )
  }
  return (
    <section aria-label="This game" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <span className="micro-label">This game</span>
      <dl style={rows} className="stack-on-phone">
        <dt className="micro-label" style={label}>Opening</dt>
        <dd style={value}>
          {o ? <>
                 {o.name} · <span className="mono">{o.serious_per_game.toFixed(1)}</span> vs your{' '}
                 <span className="mono">{o.average.toFixed(1)}</span> serious mistakes per game ·{' '}
                 <span className="mono">{o.games}</span> games
               </>
             : 'Left open: no opening is far enough above your average'}
          {o && (
            <div style={{ marginTop: 4 }}>
              <Disclose summary="Why">
                The opening most above your own average of serious mistakes per game in your Pattern Report,
                with at least 5 games and 10 serious mistakes behind it. The opponent plays its first moves from
                a book; after that the game is yours, so every game is aimed at this opening, not always played in it.
              </Disclose>
            </div>
          )}
        </dd>
        <dt className="micro-label" style={label}>Strength</dt>
        <dd style={value}>{strengthText(profile)}</dd>
      </dl>
    </section>
  )
}

/** What the Pattern Report found, from the same stored report: the opponent does not aim at these. */
function WatchForPanel({ profile }: { profile: OpponentProfile }) {
  const w = profile.watch_for
  if (!w || (!w.phase && !w.cause && !w.tactic)) return null
  return (
    <details data-disclose className="disclose" aria-label="Watch for">
      <summary>Watch for</summary>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, paddingTop: 8 }}>
      <p style={sectionLabel}>
        From your Pattern Report; the opponent doesn't aim at these.{' '}
        <Link to="/insights/patterns" style={{ ...linkStyle, fontSize: '0.78rem' }}>Pattern Report →</Link>
      </p>
      <dl style={rows} className="stack-on-phone">
        {w.phase && <>
          <dt className="micro-label" style={label}>Most often</dt>
          <dd style={value}>
            {w.phase.label} · <span className="mono">{w.phase.per_hundred.toFixed(1)}</span> vs{' '}
            <span className="mono">{w.phase.overall.toFixed(1)}</span> per 100 moves
          </dd>
        </>}
        {w.cause && <>
          <dt className="micro-label" style={label}>Costliest</dt>
          <dd style={value}>{w.cause.title} · <span className="mono">{w.cause.count}</span> of <span className="mono">{w.cause.base}</span> ({w.cause.pct}%)</dd>
        </>}
        {w.tactic && <>
          <dt className="micro-label" style={label}>Tactic</dt>
          <dd style={value}>{w.tactic.title} · <span className="mono">{w.tactic.count}</span> of <span className="mono">{w.tactic.base}</span> ({w.tactic.pct}%)</dd>
        </>}
      </dl>
      </div>
    </details>
  )
}

function directionColor(d: string) {
  return d === 'BETTER' ? 'var(--gain)' : d === 'WORSE' ? 'var(--loss, #E2664A)' : 'var(--text-muted)'
}

function ReportPanel({ report }: { report: ImprovementReport }) {
  const navigate = useNavigate()

  // `analysed` is the only field that means the engine measured this game.
  // game_id is set at archive time, well before there is anything to read, so
  // linking on the id alone would offer a review of an empty analysis.
  const canReview = report.analysed && report.game_id !== null

  return (
    <Panel title="How that went">
      <p style={{ margin: '0 0 4px', fontSize: '0.95rem', fontWeight: 600 }}>{report.verdict}</p>

      {report.caveat && (
        // Sample-size and takeback caveats are shown, never buried: a comparison
        // the data cannot support must say so where the claim is made.
        <p style={{ margin: '0 0 14px', fontSize: '0.75rem', color: 'var(--text-muted)' }}>
          {report.caveat}
        </p>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {report.comparisons.map(c => (
          <div key={c.label} style={{ borderTop: '1px solid var(--hairline)', paddingTop: 8 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
              <span style={{ fontSize: '0.8rem' }}>{c.label}</span>
              <span style={{ fontSize: '0.8rem', fontVariantNumeric: 'tabular-nums' }}>
                <strong style={{ color: directionColor(c.direction) }}>{c.value}</strong>
                <span style={{ color: 'var(--text-muted)' }}> vs {c.baseline}</span>
              </span>
            </div>
            <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 2 }}>{c.note}</div>
          </div>
        ))}
      </div>

      {report.improved.length > 0 && (
        <p style={{ marginTop: 14, marginBottom: 0, fontSize: '0.8rem' }}>
          <span style={{ color: 'var(--gain)' }}>Better: </span>{report.improved.join(', ')}
        </p>
      )}
      {report.still_to_work.length > 0 && (
        <p style={{ marginTop: 6, marginBottom: 0, fontSize: '0.8rem' }}>
          <span style={{ color: 'var(--loss, #E2664A)' }}>Still to work on: </span>
          {report.still_to_work.join(', ')}
        </p>
      )}

      <div style={{ display: 'flex', gap: 14, alignItems: 'center', flexWrap: 'wrap', marginTop: 16 }}>
        {canReview && (
          <button onClick={() => navigate(`/play/review/${report.game_id}`)} style={secondaryBtn}>
            Show full analysis
          </button>
        )}
        {report.analysed && report.game_id && (report.drill_plies?.length ?? 0) > 0 && (
          <Link to={drillLink(report.drill_plies!.map(ply => ({ game_id: report.game_id!, ply })))}
                style={{ ...linkStyle, fontSize: '0.82rem' }}>
            Drill this game's mistakes ({report.drill_plies!.length})
          </Link>
        )}
      </div>
    </Panel>
  )
}

export function PlayImprove() {
  const { data: status } = useQuery({
    queryKey: ['play-status'],
    queryFn: () => api.play.status(),
    staleTime: 5 * 60_000,
  })

  const [session, setSession] = useState<PlaySession | null>(null)
  const [fen, setFen] = useState(START_FEN)
  const [sanMoves, setSanMoves] = useState('')
  const [thinking, setThinking] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [report, setReport] = useState<ImprovementReport | null>(null)
  const [analysing, setAnalysing] = useState(false)
  /**
   * How long the post-game analysis has been running.
   *
   * "Analysing…" with nothing moving is indistinguishable from "hung", and this
   * step runs Stockfish over every move — it genuinely takes a minute.
   *
   * Still NOT /api/analysis/progress: that tracks the LIBRARY run, and practice
   * games go through practiceExecutor without registering with
   * AnalysisProgressTracker, precisely so a post-game report does not queue
   * behind a hundred-game Re-analyse All. Reading it here showed 0/0 in the
   * ordinary case and "47 / 101 games" — a real number about a different job —
   * whenever a library analysis happened to be running.
   *
   * The counts below come from the report response itself, which is already
   * being polled, and are the practice game's own.
   */
  const [elapsed, setElapsed] = useState<number | null>(null)
  const [progress, setProgress] = useState<GameAnalysisProgress | null>(null)
  const [colour, setColour] = useState<'weak' | 'white' | 'black'>('weak')
  /** I1: a strength the player chose; undefined means the recommended one. */
  const [skill, setSkill] = useState<number | undefined>()
  const { data: profile } = useQuery({
    queryKey: ['play-preview', skill],
    queryFn: () => api.play.preview(skill),
    staleTime: 60_000,
  })

  const pollRef = useRef<number | null>(null)

  const finished = session?.status === 'FINISHED'
  const orientation = session?.player_color ?? 'white'

  const { data: history, refetch: refetchHistory } = useQuery({
    queryKey: ['play-history'],
    queryFn: () => api.play.history(),
    staleTime: 30_000,
  })

  const stopPolling = useCallback(() => {
    if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null }
  }, [])

  useEffect(() => stopPolling, [stopPolling])

  /**
   * The report only exists once the backend has finished analysing, which takes
   * a while — so poll rather than pretending it is instant, and stop after a
   * bounded number of tries instead of hammering forever.
   */
  const pollReport = useCallback((id: string) => {
    stopPolling()
    setAnalysing(true)
    let tries = 0
    const startedAt = Date.now()
    setElapsed(0)
    pollRef.current = window.setInterval(async () => {
      tries += 1
      setElapsed(Math.round((Date.now() - startedAt) / 1000))
      try {
        const r = await api.play.report(id)
        if (r.analysed) {
          setReport(r)
          setAnalysing(false)
          setElapsed(null)
          setProgress(null)
          stopPolling()
          refetchHistory()
          praxBus.emit({ type: 'INSIGHT_FOUND', insightId: `play-${id}`, confidence: 1, importance: 'medium' })
          return
        }
        setProgress(r.progress)
      } catch { /* keep trying — analysis may not have started yet */ }
      if (tries >= REPORT_MAX_TRIES) {
        setAnalysing(false)
        setElapsed(null)
        stopPolling()
        // Not a loss: the game is stored and queued, so the report turns up in
        // the library later. Say that, rather than leaving it sounding failed.
        setErr('The game was saved and is still queued for analysis — the report will be there once the engine catches up.')
      }
    }, REPORT_POLL_MS)
  }, [stopPolling])

  async function newGame() {
    setErr(null); setReport(null); stopPolling(); setAnalysing(false)
    praxInteract('PRIMARY_ACTION')
    try {
      const s = await api.play.start({ color: colour === 'weak' ? undefined : colour, skill })
      setSession(s); setFen(s.fen); setSanMoves(s.san_moves ?? '')
      praxBus.emit({ type: 'PRACTICE_GAME_STARTED' })
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not start a game')
    }
  }

  function applyResult(r: MoveResult) {
    setFen(r.fen)
    setSanMoves(r.san_moves ?? '')
    setSession(prev => prev && ({ ...prev, status: r.status, result: r.result, end_reason: r.end_reason }))
    if (r.status === 'FINISHED' && session) {
      praxBus.emit({ type: 'PRACTICE_GAME_FINISHED' })
      pollReport(session.session_id)
    }
  }

  function onDrop(from: Square, to: Square): boolean {
    if (!session || finished || thinking) return false

    // chess.js validates locally so the piece snaps instantly. The server
    // re-validates and is the authority — this is only for feel.
    const chess = new Chess(fen)
    let uci = `${from}${to}`
    try {
      const move = chess.move({ from, to, promotion: 'q' })
      if (!move) return false
      if (move.promotion) uci += move.promotion
    } catch { return false }

    setFen(chess.fen())
    setThinking(true)
    praxBus.emit({ type: 'QUERY_STARTED' })

    api.play.move(session.session_id, uci)
      .then(applyResult)
      .catch((e: unknown) => {
        // Server refused it — snap back rather than leaving the board lying.
        setFen(fen)
        setErr(e instanceof Error ? e.message : 'Move rejected')
      })
      .finally(() => { setThinking(false); praxBus.emit({ type: 'QUERY_FINISHED' }) })

    return true
  }

  async function doUndo() {
    if (!session || finished) return
    praxInteract('SECONDARY_ACTION')
    const r = await api.play.undo(session.session_id)
    setFen(r.fen); setSanMoves(r.san_moves ?? '')
    setSession(prev => prev && ({ ...prev, rated: false }))
  }

  async function doResign() {
    if (!session || finished) return
    praxInteract('SECONDARY_ACTION')
    applyResult(await api.play.resign(session.session_id))
  }

  if (status && !status.available) {
    return (
      <Page width="standard">
      <div style={{ maxWidth: 620 }}>
        <h1 className="sr-only">Play &amp; Improve</h1>
        <Panel>
          <p style={{ margin: 0, fontSize: '0.85rem' }}>
            The chess engine is not running, so there is no opponent to play.
            Set <code>praxis-chess.stockfish.path</code> and restart.
          </p>
        </Panel>
      </div>
      </Page>
    )
  }

  return (
    // Standard before a game; wide during one, for the board and its panel (D1).
    <Page width={session ? 'wide' : 'standard'}>
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {/* The nav names this page; the heading stays for screen readers and tests, not the eye. */}
        <h1 className="sr-only">Play &amp; Improve</h1>
        {/* With the heading gone from sight, the next game is the page's lead line. */}
        {!session && profile && (
          <p data-reading="takeaway" style={{ fontSize: 'var(--fs-section)', fontWeight: 600, margin: 0 }}>
            {takeaway(profile, resolvedColour(colour, profile))}
          </p>
        )}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          {/* The page's one solid button, before anything else (principle 10). */}
          <button className={!session || finished ? 'solid' : 'secondary'} onClick={newGame}
                  style={{ padding: '7px 16px', fontSize: '0.82rem' }}>
            {session ? 'New game' : 'Start a game'}
          </button>

          {!session && (
            <>
              <select
                // Without a name this is announced as an unlabelled combobox.
                aria-label="Which colour to play"
                value={colour}
                onChange={e => setColour(e.target.value as 'weak' | 'white' | 'black')}
                style={selectStyle}
              >
                <option value="weak">
                  {profile?.colour ? `Weaker colour: ${cap(profile.colour.weaker)}` : 'Your weaker colour'}
                </option>
                <option value="white">White</option>
                <option value="black">Black</option>
              </select>
              {/* I1: choose the strength; the recommended one is preselected. */}
              <select
                aria-label="Engine strength"
                value={skill ?? ''}
                onChange={e => setSkill(e.target.value === '' ? undefined : Number(e.target.value))}
                style={selectStyle}
              >
                <option value="">
                  Strength: recommended{profile && skill == null ? ` (${profile.skill?.level ?? profile.skill_level})` : ''}
                </option>
                {Array.from({ length: (profile?.skill?.max ?? 14) - (profile?.skill?.min ?? 1) + 1 },
                            (_, i) => (profile?.skill?.min ?? 1) + i).map(n => (
                  <option key={n} value={n}>Strength: {n} of {profile?.skill?.scale ?? 20}</option>
                ))}
              </select>
            </>
          )}

          {session && !finished && (
            <>
              <button onClick={doUndo} style={secondaryBtn}>Undo</button>
              <button onClick={doResign} style={secondaryBtn}>Resign</button>
            </>
          )}

          {session && !session.rated && (
            <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
              Takeback used: this game will not count toward your progress.
            </span>
          )}
        </div>
        {!session && colour === 'weak' && colourFigure(profile) && (
          <p style={{ ...muted, margin: 0, fontSize: '0.75rem' }}>{colourFigure(profile)}</p>
        )}
      </div>

      {!session && profile && <ThisGame profile={profile} />}
      {!session && profile && <WatchForPanel profile={profile} />}
      {/*
        Also shown once a game is over, not only before one starts. If the report
        poll gives up at five minutes the analysis still completes server-side,
        and without this the finished report would be unreachable: the page
        holds no session across a reload.
      */}
      {(!session || finished) && history && <PracticeSoFar games={history} />}

      {err && <p style={{ fontSize: '0.8rem', color: 'var(--loss, #E2664A)', margin: 0 }}>{err}</p>}

      {session && (
        <BoardSplit boardBasis={320} boardMax={460} gap={18}>
          <div>
            <ThemedBoard
              position={fen}
              boardOrientation={orientation as 'white' | 'black'}
              onPieceDrop={onDrop}
              arePiecesDraggable={!finished && !thinking}
            />
            <div style={{ marginTop: 8, fontSize: '0.74rem', color: 'var(--text-muted)', minHeight: 18 }}>
              {finished
                ? `${resultText(session)} · ${session.end_reason?.toLowerCase().replace('_', ' ')}`
                : thinking ? 'Opponent thinking…' : 'Your move.'}
            </div>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {/* N6: what the game is, the habit you are working on, and the material, while you play. */}
            <Panel label="This game">
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <p style={{ margin: 0, fontSize: '0.82rem' }}>
                  {session.target_opening ?? 'Any opening'}
                  <span style={{ color: 'var(--text-muted)' }}> · engine skill {session.skill_level} of 20</span>
                </p>
                {profile?.watch_for?.cause && (
                  <p style={{ margin: 0, fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                    <span className="micro-label" style={{ marginRight: 6 }}>Watch for</span>
                    {profile.watch_for.cause.title}{profile.watch_for.tactic ? ` · ${profile.watch_for.tactic.title}` : ''}
                  </p>
                )}
                <p style={{ margin: 0, fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                  <span className="micro-label" style={{ marginRight: 6 }}>Material</span>
                  <span className="mono">{materialText(fen, orientation)}</span>
                </p>
              </div>
            </Panel>

            {sanMoves && (
              <Panel title="Moves">
                <MoveList san={sanMoves} playerColor={orientation} />
              </Panel>
            )}

            {analysing && (
              <Panel title="Analysing">
                <p style={{ margin: 0, fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
                  Same engine and thresholds as your Chess.com games.
                </p>

                {/*
                  Determinate ONLY when the server sends counts it actually took.
                  `progress` is null while the game waits its turn on the executor,
                  and there is nothing to count then — so it falls back to the
                  sweeping fill rather than showing a 0% that is really "not
                  started". A bar is allowed to be honest about not knowing.
                */}
                <div
                  role="progressbar"
                  aria-label="Analysing the game"
                  aria-busy="true"
                  aria-valuenow={progress ? progress.done : undefined}
                  aria-valuemin={progress ? 0 : undefined}
                  aria-valuemax={progress ? progress.total : undefined}
                  style={{
                    height: 4, borderRadius: 2, marginTop: 14, marginBottom: 10,
                    background: 'var(--surface-2, #1B1920)',
                    overflow: 'hidden', position: 'relative',
                  }}
                >
                  {progress && progress.total > 0 ? (
                    <div style={{
                      position: 'absolute', top: 0, left: 0, bottom: 0,
                      width: `${Math.min(100, (progress.done / progress.total) * 100)}%`,
                      background: 'var(--orchid, #E7A6D6)',
                      transition: 'width var(--dur-fill) var(--ease-out)',
                    }} />
                  ) : (
                    <div style={{
                      position: 'absolute', inset: 0,
                      background: 'linear-gradient(90deg, transparent, var(--orchid, #E7A6D6), transparent)',
                      animation: 'prax-fill-sweep 1400ms cubic-bezier(0.4, 0, 0.2, 1) infinite',
                    }} />
                  )}
                </div>

                <div style={{
                  display: 'flex', justifyContent: 'space-between', gap: 12,
                  fontSize: '0.74rem',
                  fontFamily: 'var(--font-mono, monospace)',
                  color: 'var(--text-tertiary, #625C6D)',
                }}>
                  <span>
                    {progress
                      ? `${STAGE_LABEL[progress.stage]} ${progress.done} / ${progress.total}`
                      : 'waiting for the engine'}
                  </span>
                  {/* Elapsed, not an ETA: the stages have different unit costs,
                      so counts within a stage do not extrapolate to a finish time. */}
                  <span>{elapsed ?? 0}s</span>
                </div>
              </Panel>
            )}

            {report && <ReportPanel report={report} />}
          </div>
        </BoardSplit>
      )}

      {/* 0.28 put Prax's body over the sync toolbar. The board is tall, so the
          anchor sits at the same mid-height every other page uses. */}
    </div>
    </Page>
  )
}

const selectStyle: React.CSSProperties = {
  background: 'var(--surface-2)', color: 'var(--text)', border: '1px solid var(--hairline)',
  borderRadius: 4, padding: '6px 9px', fontSize: '0.78rem',
}

const secondaryBtn: React.CSSProperties = {
  background: 'transparent',
  border: '1px solid var(--hairline)',
  color: 'var(--text-secondary)',
  borderRadius: 4,
  padding: '6px 12px',
  fontSize: '0.78rem',
  cursor: 'pointer',
}

function resultText(s: PlaySession): string {
  if (s.result === 'win') return 'You won'
  if (s.result === 'loss') return 'You lost'
  return 'Drawn'
}

