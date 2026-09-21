import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Chessboard } from 'react-chessboard'
import { Chess, type Square } from 'chess.js'
import { api } from '../api/client'
import type {
  GameAnalysisProgress, AnalysisStage, ImprovementReport, MoveResult,
  PlaySession, PracticeGameSummary,
} from '../api/types'
import { PracticePatterns } from '../components/PracticePatterns'
import { PraxAnchor, praxBus, praxInteract } from '../prax/PraxHost'

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

const RESULT_COLOR: Record<string, string> = {
  win: 'var(--gain)',
  loss: 'var(--loss, #E2664A)',
  draw: 'var(--text-secondary)',
}

/**
 * Games you actually finished.
 *
 * Abandoned and still-IN_PROGRESS rows are filtered out rather than listed as
 * failures: a session row is created the moment a game starts, so a closed tab
 * or a double-clicked Start leaves rows behind that were never games. They are
 * not history, and showing a dozen of them would bury the games that are.
 */
function HistoryPanel({ games }: { games: PracticeGameSummary[] }) {
  const navigate = useNavigate()
  const played = games.filter(g => g.status === 'FINISHED')

  if (played.length === 0) return null

  return (
    <Panel title="Earlier practice games">
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {played.slice(0, 10).map(g => {
          const reviewable = g.analysed && g.game_id !== null
          return (
            <div
              key={g.id}
              style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '7px 0',
                borderTop: '1px solid var(--hairline)',
                fontSize: '0.78rem',
              }}
            >
              <span style={{ color: RESULT_COLOR[g.result ?? 'draw'], minWidth: 42, fontWeight: 600 }}>
                {g.result === 'win' ? 'Won' : g.result === 'loss' ? 'Lost' : 'Drew'}
              </span>
              <span className="mono" style={{ color: 'var(--text-tertiary)', minWidth: 66 }}>
                {new Date(g.started_at).toLocaleDateString()}
              </span>
              <span style={{ color: 'var(--text-secondary)', flex: 1, minWidth: 0, overflow: 'hidden',
                             textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {g.target_opening ?? '—'}
              </span>
              {!g.rated && (
                <span style={{ fontSize: '0.68rem', color: 'var(--text-tertiary)' }}>unrated</span>
              )}
              {reviewable ? (
                <button
                  onClick={() => navigate(`/play/review/${g.game_id}`)}
                  style={{
                    background: 'transparent', border: '1px solid var(--hairline)',
                    color: 'var(--text-secondary)', borderRadius: 3,
                    padding: '3px 9px', fontSize: '0.72rem', cursor: 'pointer',
                  }}
                >
                  Review
                </button>
              ) : (
                // Says which kind of absent this is. "Not analysed" and "analysis
                // failed" look identical from here, but neither is "no mistakes".
                <span style={{ fontSize: '0.68rem', color: 'var(--text-tertiary)' }}>
                  not analysed
                </span>
              )}
            </div>
          )
        })}
      </div>
    </Panel>
  )
}

function Panel({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <div className="card" style={{ padding: '18px 20px' }}>
      {title && (
        <div style={{ fontSize: '0.8rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: 10 }}>
          {title}
        </div>
      )}
      {children}
    </div>
  )
}

/**
 * The opponent's reasons, shown BEFORE the game starts.
 *
 * This is the difference between this and a chess bot: every line comes from a
 * count in your own history. When there is not enough history the profile says
 * so rather than inventing a justification.
 */
function OpponentCard({ skill }: { skill?: number }) {
  const { data } = useQuery({
    queryKey: ['play-preview', skill],
    queryFn: () => api.play.preview(skill),
    staleTime: 60_000,
  })
  if (!data) return null

  return (
    <Panel title="Your opponent">
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        {data.rationale.map((line, i) => (
          <p key={i} style={{ margin: 0, fontSize: '0.82rem', lineHeight: 1.5,
                              color: i === 0 ? 'var(--text)' : 'var(--text-secondary)' }}>
            {line}
          </p>
        ))}
      </div>
      {!data.personalised && (
        <p style={{ marginTop: 10, marginBottom: 0, fontSize: '0.74rem', color: 'var(--text-muted)' }}>
          Analyse some games and this opponent will be built from them.
        </p>
      )}
    </Panel>
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

      {canReview && (
        <button
          onClick={() => navigate(`/play/review/${report.game_id}`)}
          style={{ ...secondaryBtn, marginTop: 16 }}
        >
          Show full analysis
        </button>
      )}
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
   * behind a hundred-game Re-analyze All. Reading it here showed 0/0 in the
   * ordinary case and "47 / 101 games" — a real number about a different job —
   * whenever a library analysis happened to be running.
   *
   * The counts below come from the report response itself, which is already
   * being polled, and are the practice game's own.
   */
  const [elapsed, setElapsed] = useState<number | null>(null)
  const [progress, setProgress] = useState<GameAnalysisProgress | null>(null)
  const [colour, setColour] = useState<'weak' | 'white' | 'black'>('weak')

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
      const s = await api.play.start({ color: colour === 'weak' ? undefined : colour })
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
      <div style={{ maxWidth: 620 }}>
        <h2 style={{ fontSize: '1.2rem', fontWeight: 700, margin: '0 0 6px' }}>Play &amp; Improve</h2>
        <Panel>
          <p style={{ margin: 0, fontSize: '0.85rem' }}>
            The chess engine is not running, so there is no opponent to play.
            Set <code>praxis-chess.stockfish.path</code> and restart.
          </p>
        </Panel>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18, maxWidth: 'min(100%, 900px)' }}>
      <div>
        <h2 style={{ fontSize: '1.2rem', fontWeight: 700, margin: 0 }}>Play &amp; Improve</h2>
        <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 4 }}>
          An opponent built from your own games — then the same measurements, to see if you played better.
        </p>
      </div>

      {!session && <OpponentCard />}
      {/*
        Also shown once a game is over, not only before one starts. If the report
        poll gives up at five minutes the analysis still completes server-side,
        and without this the finished report would be unreachable — the page
        holds no session across a reload.
      */}
      {(!session || finished) && history && <HistoryPanel games={history} />}
      {(!session || finished) && <PracticePatterns />}

      <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        <button className="btn-primary" onClick={newGame} style={{ padding: '7px 16px', fontSize: '0.82rem' }}>
          {session ? 'New game' : 'Start a game'}
        </button>

        {!session && (
          <select
            // Without a name this is announced as an unlabelled combobox — the
            // options alone do not say what is being chosen.
            aria-label="Which colour to play"
            value={colour}
            onChange={e => setColour(e.target.value as 'weak' | 'white' | 'black')}
            style={{
              background: 'var(--surface-2, #1B1920)', color: 'var(--text)',
              border: '1px solid var(--hairline)', borderRadius: 4,
              padding: '6px 9px', fontSize: '0.78rem',
            }}
          >
            <option value="weak">Your weaker colour</option>
            <option value="white">Play White</option>
            <option value="black">Play Black</option>
          </select>
        )}

        {session && !finished && (
          <>
            <button onClick={doUndo} style={secondaryBtn}>Undo</button>
            <button onClick={doResign} style={secondaryBtn}>Resign</button>
          </>
        )}

        {session && !session.rated && (
          <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
            Takeback used — this game will not count toward your progress.
          </span>
        )}
      </div>

      {err && <p style={{ fontSize: '0.8rem', color: 'var(--loss, #E2664A)', margin: 0 }}>{err}</p>}

      {session && (
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(320px, 460px) 1fr', gap: 18, alignItems: 'start' }}>
          <div>
            <Chessboard
              position={fen}
              boardOrientation={orientation as 'white' | 'black'}
              onPieceDrop={onDrop}
              arePiecesDraggable={!finished && !thinking}
              customBoardStyle={{ borderRadius: 6 }}
            />
            <div style={{ marginTop: 8, fontSize: '0.74rem', color: 'var(--text-muted)', minHeight: 18 }}>
              {finished
                ? `${resultText(session)} · ${session.end_reason?.toLowerCase().replace('_', ' ')}`
                : thinking ? 'Opponent thinking…' : 'Your move.'}
            </div>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {session.target_opening && (
              <Panel title="This game is pressing">
                <p style={{ margin: 0, fontSize: '0.82rem' }}>
                  {session.target_opening}
                  <span style={{ color: 'var(--text-muted)' }}> · engine skill {session.skill_level}/20</span>
                </p>
              </Panel>
            )}

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
                      transition: 'width 300ms linear',
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
        </div>
      )}

      {/* 0.28 put Prax's body over the sync toolbar. The board is tall, so the
          anchor sits at the same mid-height every other page uses. */}
      <PraxAnchor x={0.86} y={0.45} />
    </div>
  )
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

