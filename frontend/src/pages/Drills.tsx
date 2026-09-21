import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Chessboard } from 'react-chessboard'
import { Chess } from 'chess.js'
import { api } from '../api/client'
import type { Drill } from '../api/types'
import { PraxAnchor } from '../prax/PraxHost'

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

/** One drill position. Keyed by drill.id so state resets between drills. */
function DrillBoard({ drill, onSolved, onNext }: { drill: Drill; onSolved: () => void; onNext: () => void }) {
  const [boardFen, setBoardFen] = useState(drill.fen)
  const [solved, setSolved] = useState(false)
  const [revealed, setRevealed] = useState(false)
  const [attempts, setAttempts] = useState(0)
  const [wrongFlash, setWrongFlash] = useState(false)

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
        transition: 'box-shadow 0.2s',
      }}>
        <Chessboard
          position={boardFen}
          boardOrientation={toMove}
          onPieceDrop={onDrop}
          arePiecesDraggable={!done}
          customDarkSquareStyle={{ backgroundColor: '#3d5a80' }}
          customLightSquareStyle={{ backgroundColor: '#98c1d9' }}
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
          {toMove === 'white' ? 'White' : 'Black'} to move — find the best move
        </div>
        <div style={{ fontSize: '0.82rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>
          In this position you played <b style={{ color: 'var(--red)' }}>{drill.move_played}</b>.
          There was something better — drag a piece to play the engine's move.
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
              Best move: {drill.best_move}
            </div>
            {drill.explanation && <div style={{ fontSize: '0.82rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>{drill.explanation}</div>}
          </div>
        )}

        <div style={{ display: 'flex', gap: 10, marginTop: 4 }}>
          {!done && (
            <button className="secondary" style={{ fontSize: '0.8rem' }} onClick={() => setRevealed(true)}>
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
  const { data: drills, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['drills'],
    queryFn: () => api.drills.list(20),
    staleTime: 0,
  })
  const [index, setIndex] = useState(0)
  const [solvedCount, setSolvedCount] = useState(0)

  if (isLoading) return <p style={{ color: 'var(--text-muted)' }}>Loading drills…</p>
  if (error)     return <p style={{ color: 'var(--red)' }}>Failed to load drills.</p>
  if (!drills || drills.length === 0) {
    return (
      <div>
        <h2 style={{ fontSize: '1.2rem', fontWeight: 700 }}>Drills</h2>
        <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>
          No drills yet. Analyze some games — every blunder with an engine best move becomes a puzzle here.
        </p>

        {/* The empty state needs the anchor as much as the loaded one —
            Prax is on the page either way, and a branch without it falls
            back to the registry default. */}
        <PraxAnchor x={0.9} y={0.45} />
      </div>
    )
  }

  const current = drills[index]
  const atEnd = index >= drills.length - 1

  function next() {
    if (atEnd) {
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
          <h2 style={{ fontSize: '1.2rem', fontWeight: 700, margin: 0 }}>Drills</h2>
          <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 4 }}>
            Re-solve your own mistakes. The best way to stop repeating them.
          </p>
        </div>
        <div style={{ display: 'flex', gap: 16, alignItems: 'center', fontSize: '0.82rem', color: 'var(--text-muted)' }}>
          <span>Drill <b style={{ color: 'var(--text)' }}>{index + 1}</b> / {drills.length}</span>
          <span>Solved <b style={{ color: 'var(--green)' }}>{solvedCount}</b></span>
          <button className="secondary" style={{ fontSize: '0.78rem' }} onClick={() => refetch()} disabled={isFetching}>
            {isFetching ? 'Shuffling…' : '⟳ New set'}
          </button>
        </div>
      </div>

      <div className="card" style={{ padding: 20 }}>
        <DrillBoard
          key={current.id}
          drill={current}
          onSolved={() => setSolvedCount(c => c + 1)}
          onNext={next}
        />
      </div>

      {/* Contract §4 — the PAGE decides where Prax belongs. Without this
          the registry falls back to a fixed 0.68/0.46, which on this
          layout is directly on top of the content. */}
      {/* Right of the drill board. */}
      <PraxAnchor x={0.9} y={0.45} />
    </div>
  )
}
