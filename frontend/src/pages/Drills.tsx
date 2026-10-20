import { useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ThemedBoard } from '../components/ThemedBoard'
import { Chess } from 'chess.js'
import { api } from '../api/client'
import type { Drill } from '../api/types'
import { drillLink } from '../components/report/ReportParts'
import { uciToSan } from '../components/san'

const sevColor = (s: string) => (s === 'BLUNDER' ? 'var(--red)' : s === 'MISTAKE' ? 'var(--yellow)' : 'var(--text-muted)')
const motifLabel = (m: string) => m.split('_').map(w => w.charAt(0) + w.slice(1).toLowerCase()).join(' ')

function Badge({ children, color }: { children: React.ReactNode; color?: string }) {
  return (
    <span style={{
      fontSize: '0.68rem', fontWeight: 600, padding: '2px 8px', borderRadius: 10,
      background: 'var(--surface-2)', color: color ?? 'var(--text-muted)',
    }}>{children}</span>
  )
}

function sideToMove(fen: string): 'white' | 'black' {
  return fen.split(' ')[1] === 'b' ? 'black' : 'white'
}

export interface DrillAnswer { correct: boolean; wrongTries: number; revealed: boolean; responseMs: number }

/** One drill position. Keyed by drill.id so state resets between drills. */
function DrillBoard({ drill, onSolved, onNext, onAnswer }: {
  drill: Drill; onSolved: () => void; onNext: () => void
  /** Called once per position, when it is solved or the answer is shown. */
  onAnswer?: (a: DrillAnswer) => void
}) {
  const [boardFen, setBoardFen] = useState(drill.fen)
  const [solved, setSolved] = useState(false)
  const [revealed, setRevealed] = useState(false)
  const [attempts, setAttempts] = useState(0)
  const [wrongFlash, setWrongFlash] = useState(false)
  const shownAt = useRef(Date.now())
  const answered = useRef(false)
  const answer = (correct: boolean, wasRevealed: boolean) => {
    if (answered.current) return
    answered.current = true
    onAnswer?.({ correct, wrongTries: attempts, revealed: wasRevealed, responseMs: Date.now() - shownAt.current })
  }

  const toMove = sideToMove(drill.fen)
  const done = solved || revealed

  const answerArrow = useMemo(() => {
    if (!done) return []
    const from = drill.best_move.slice(0, 2)
    const to = drill.best_move.slice(2, 4)
    // react-chessboard arrow tuple: [from, to, color]
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return [[from, to, 'rgba(46, 160, 67, 0.9)']] as any[]
  }, [done, drill.best_move])

  function onDrop(source: string, target: string): boolean {
    if (done) return false
    const chess = new Chess(drill.fen)
    let move
    try {
      move = chess.move({ from: source, to: target, promotion: 'q' })
    } catch {
      return false
    }
    if (!move) return false
    const uci = move.from + move.to + (move.promotion ?? '')
    if (uci === drill.best_move) {
      setBoardFen(chess.fen())
      setSolved(true)
      onSolved()
      answer(true, false)
      return true
    }
    setAttempts(a => a + 1)
    setWrongFlash(true)
    setTimeout(() => setWrongFlash(false), 500)
    return false
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '360px 1fr', gap: 24, alignItems: 'start' }}>
      <div style={{
        borderRadius: 8, overflow: 'hidden',
        boxShadow: wrongFlash ? '0 0 0 3px var(--red)' : solved ? '0 0 0 3px var(--green)' : 'none',
        transition: 'box-shadow var(--dur-slow) var(--ease-out)',
      }}>
        <ThemedBoard
          position={boardFen}
          boardOrientation={toMove}
          onPieceDrop={onDrop}
          arePiecesDraggable={!done}
          boardWidth={360}
          customArrows={answerArrow}
        />
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <Badge color={sevColor(drill.severity)}>{drill.severity}</Badge>
          {drill.tactical_motif && <Badge>{motifLabel(drill.tactical_motif)}</Badge>}
          {drill.game_phase && <Badge>{motifLabel(drill.game_phase)}</Badge>}
        </div>

        <div style={{ fontSize: '1.05rem', fontWeight: 700 }}>
          {toMove === 'white' ? 'White' : 'Black'} to move: find the best move
        </div>
        <div style={{ fontSize: '0.82rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>
          You played <b style={{ color: 'var(--red)' }}>{drill.move_played}</b>. Drag the better move.
        </div>

        {!done && attempts > 0 && (
          <div style={{ fontSize: '0.82rem', color: 'var(--red)' }}>
            Not the best move. Attempts: {attempts}
          </div>
        )}

        {solved && (
          <div className="card" style={{ borderLeft: '3px solid var(--green)', padding: '12px 14px' }}>
            <div style={{ fontWeight: 700, color: 'var(--green)', marginBottom: 6 }}>✓ Correct!</div>
            {drill.explanation && <div style={{ fontSize: '0.82rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>{drill.explanation}</div>}
          </div>
        )}

        {revealed && !solved && (
          <div className="card" style={{ borderLeft: '3px solid var(--accent)', padding: '12px 14px' }}>
            <div style={{ fontWeight: 700, color: 'var(--accent)', marginBottom: 6 }}>
              Best move: {uciToSan(drill.fen, drill.best_move)}
            </div>
            {drill.explanation && <div style={{ fontSize: '0.82rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>{drill.explanation}</div>}
          </div>
        )}

        <div style={{ display: 'flex', gap: 10, marginTop: 4 }}>
          {!done && (
            <button className="secondary" style={{ fontSize: '0.8rem' }} onClick={() => { setRevealed(true); answer(false, true) }}>
              Show answer
            </button>
          )}
          {done && (
            <button style={{ fontSize: '0.85rem' }} onClick={onNext}>
              Next drill →
            </button>
          )}
          {drill.game_id && (
            <Link to={`/games/${drill.game_id}`} className="secondary"
                  style={{ fontSize: '0.8rem', padding: '6px 12px', borderRadius: 6, textDecoration: 'none', display: 'inline-flex', alignItems: 'center' }}>
              View game
            </Link>
          )}
        </div>
      </div>
    </div>
  )
}

export function Drills() {
  // A Training Plan session passes its positions; otherwise a random set.
  const [params] = useSearchParams()
  const positions = params.get('positions')?.split(',').filter(Boolean) ?? []
  const session = params.get('session')
  const planId = params.get('plan')
  const qc = useQueryClient()
  const { data: drills, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['drills', positions.join(',')],
    queryFn: () => positions.length ? api.drills.positions(positions) : api.drills.list(20),
    staleTime: 0,
  })
  const [index, setIndex] = useState(0)
  const [solvedCount, setSolvedCount] = useState(0)
  const [done, setDone] = useState(false)
  const navigate = useNavigate()
  // A plan session hands off to the plan's next session when it ends.
  const { data: plan } = useQuery({ queryKey: ['training-plan'], queryFn: api.trainingPlan.latest, enabled: !!planId })

  if (isLoading) return <p style={{ color: 'var(--text-muted)' }}>Loading drills…</p>
  if (error)     return <p style={{ color: 'var(--red)' }}>Failed to load drills.</p>
  if (!drills || drills.length === 0) {
    return (
      <div>
        <h2 style={{ fontSize: 'var(--fs-h1)', fontWeight: 700 }}>Drills</h2>
        <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>
          No drills yet: each analysed blunder becomes one.
        </p>
        <Link to="/library" style={{ fontSize: '0.85rem' }}>Your games →</Link>

        {/* The empty state needs the anchor as much as the loaded one —
            Prax is on the page either way, and a branch without it falls
            back to the registry default. */}
      </div>
    )
  }

  const current = drills[index]
  const atEnd = index >= drills.length - 1

  function next() {
    if (atEnd && session) {
      setDone(true)
      // The plan remembers the session (and the score).
      if (planId) {
        api.trainingPlan.sessionDone(planId, Number(session), solvedCount, drills!.length)
          .then(() => qc.invalidateQueries({ queryKey: ['training-plan'] }))
          .catch(() => { /* the session still counts for the player; the plan just won't show it */ })
      }
    } else if (atEnd) {
      setIndex(0)
      setSolvedCount(0)
      refetch()
    } else {
      setIndex(i => i + 1)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h2 style={{ fontSize: '1.2rem', fontWeight: 700, margin: 0 }}>{session ? `Session ${session}` : 'Drills'}</h2>
          <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 4 }}>
            {session
              ? <>From your <Link to="/insights/training">Training Plan</Link>: your own positions, in order.</>
              : 'Re-solve your own mistakes. The best way to stop repeating them.'}
          </p>
        </div>
        <div style={{ display: 'flex', gap: 16, alignItems: 'center', fontSize: '0.82rem', color: 'var(--text-muted)' }}>
          <span>Drill <b style={{ color: 'var(--text)' }}>{index + 1}</b> / {drills.length}</span>
          <span>Solved <b style={{ color: 'var(--green)' }}>{solvedCount}</b></span>
          {!session && (
            <button className="secondary" style={{ fontSize: '0.78rem' }} onClick={() => refetch()} disabled={isFetching}>
              {isFetching ? 'Shuffling…' : '⟳ New set'}
            </button>
          )}
        </div>
      </div>

      {done ? (
        <div className="card" role="status" style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <strong>Session {session} done: {solvedCount} of {drills.length} solved.</strong>
          <NextAfterSession plan={plan?.id === planId ? plan : null} session={Number(session)}
                            onGo={to => navigate(to)} />
        </div>
      ) : (
      <div className="card" style={{ padding: 20 }}>
        <DrillBoard
          key={current.id}
          drill={current}
          onSolved={() => setSolvedCount(c => c + 1)}
          onNext={next}
          onAnswer={planId && session && current.game_id && current.ply != null ? a => {
            // Kept per position (F12): the history a "solved before / now" needs only exists from here on.
            api.trainingPlan.answer(planId, Number(session), {
              game_id: current.game_id!, ply: current.ply!, move_error_id: current.id, correct: a.correct,
              wrong_tries: a.wrongTries, revealed: a.revealed, response_ms: a.responseMs,
            }).catch(() => { /* the drill still counts for the player; only the history misses it */ })
          } : undefined}
        />
      </div>
      )}

      {/* Contract §4 — the PAGE decides where Prax belongs. Without this
          the registry falls back to a fixed 0.68/0.46, which on this
          layout is directly on top of the content. */}
      {/* Right of the drill board. */}
    </div>
  )
}

/**
 * The end of a plan session is not a dead end: the next undone session, or,
 * when the week is done, whether it is working (Progress).
 */
function NextAfterSession({ plan, session, onGo }: {
  plan: import('../api/types').TrainingPlan | null; session: number; onGo: (to: string) => void
}) {
  const sessions = plan?.plan?.sessions ?? []
  const progress = plan?.progress ?? {}
  const next = sessions.find(s => s.number !== session && !progress[String(s.number)])
  return (
    <div style={{ display: 'flex', gap: 14, alignItems: 'center', flexWrap: 'wrap' }}>
      {next
        ? <button className="solid" onClick={() => onGo(drillLink(next.positions, next.number, plan!.id))}>
            Start session {next.number}
          </button>
        : <button className="solid" onClick={() => onGo('/progress')}>See progress</button>}
      <Link to="/insights/training" style={{ fontSize: '0.85rem' }}>Back to the plan</Link>
    </div>
  )
}
