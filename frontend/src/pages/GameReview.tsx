import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ChessBoard } from '../components/ChessBoard'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { MoveErrorCard, SEVERITY_COLOR, SEVERITY_SYMBOL } from '../components/MoveErrorCard'
import { ARROW_NEUTRAL, ARROW_PLAYED_BAD, buildArrows } from '../components/moveArrows'
import { useGameReview } from '../hooks/useGameReview'
import type { ReviewMove, Severity } from '../api/types'
import { PraxAnchor } from '../prax/PraxHost'
import { BoardSplit } from '../components/BoardSplit'

const SEVERITIES: Severity[] = ['BLUNDER', 'MISTAKE', 'INACCURACY']

/** Full moves, each holding the white and black half-move where they exist. */
function pairUp(moves: ReviewMove[]) {
  const rows = new Map<number, { white?: ReviewMove; black?: ReviewMove }>()
  for (const m of moves) {
    const row = rows.get(m.move_number) ?? {}
    if (m.color === 'white') row.white = m
    else row.black = m
    rows.set(m.move_number, row)
  }
  return [...rows.entries()].sort((a, b) => a[0] - b[0])
}

function MoveChip({ move, active, onSelect }: {
  move: ReviewMove
  active: boolean
  onSelect: () => void
}) {
  const severity = move.mistake?.severity
  const colour = severity ? SEVERITY_COLOR[severity] : 'var(--text-secondary)'

  return (
    <button
      onClick={onSelect}
      aria-current={active ? 'true' : undefined}
      style={{
        border: `1px solid ${active ? 'var(--hairline-lit)' : 'transparent'}`,
        background: active ? 'var(--accent-wash)' : 'transparent',
        color: colour,
        fontFamily: 'var(--font-mono, monospace)',
        fontSize: '0.78rem',
        fontWeight: severity ? 600 : 400,
        padding: '2px 6px',
        borderRadius: 3,
        cursor: 'pointer',
        textAlign: 'left',
        minWidth: 62,
      }}
    >
      {move.san}{severity ? SEVERITY_SYMBOL[severity] : ''}
    </button>
  )
}

export function GameReview() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { data: review, isLoading } = useGameReview(id ?? null)
  const [searchParams] = useSearchParams()
  const [cursor, setCursor] = useState(0)

  const moves = useMemo(() => review?.moves ?? [], [review])

  /**
   * Land on the move a caller pointed at — pattern evidence links in as
   * `?ply=23`. Applied once the moves arrive, and only when the ply resolves to
   * a real move, so a stale or hand-edited link opens the game rather than
   * failing. Deliberately not re-applied on later renders: it seeds the cursor,
   * it does not pin it, or the arrow keys would fight the URL.
   */
  const requestedPly = searchParams.get('ply')
  const [seeded, setSeeded] = useState(false)
  useEffect(() => {
    if (seeded || moves.length === 0) return
    setSeeded(true)
    if (requestedPly === null) return
    const index = moves.findIndex(m => m.ply === Number(requestedPly))
    if (index >= 0) setCursor(index)
  }, [moves, requestedPly, seeded])

  const step = useCallback((delta: number) => {
    setCursor(c => Math.min(Math.max(c + delta, 0), Math.max(moves.length - 1, 0)))
  }, [moves.length])

  // Arrow keys are how anyone reads a game; without them this is a click-only
  // review and nobody steps through 40 moves twice.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'ArrowLeft') { e.preventDefault(); step(-1) }
      if (e.key === 'ArrowRight') { e.preventDefault(); step(1) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [step])

  if (isLoading) {
    return (
      <>
        <LoadingSpinner label="Reading the game…" />
        <PraxAnchor x={0.9} y={0.45} />
      </>
    )
  }

  if (!review || moves.length === 0) {
    return (
      <div style={{ maxWidth: 620 }}>
        <h2 style={{ fontSize: '1.2rem', fontWeight: 700, margin: '0 0 10px' }}>Full analysis</h2>
        <div className="card">
          <p style={{ margin: 0, fontSize: '0.85rem' }}>
            There are no moves recorded for this game.
          </p>
        </div>
        <PraxAnchor x={0.9} y={0.45} />
      </div>
    )
  }

  const current = moves[cursor]
  const mistake = current.mistake
  const arrows = buildArrows(
    current.fen_before,
    current.san,
    mistake?.better_move,
    // Red is a verdict. An unflagged move did not earn one.
    mistake ? ARROW_PLAYED_BAD : ARROW_NEUTRAL,
  )

  const playerMoves = moves.filter(m => m.by_player)
  const counts = SEVERITIES.map(s => ({
    severity: s,
    n: playerMoves.filter(m => m.mistake?.severity === s).length,
  })).filter(c => c.n > 0)

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 6 }}>
        <button className="secondary" onClick={() => navigate(-1)} style={{ padding: '6px 12px' }}>
          ← Back
        </button>
        <h1 style={{ fontSize: '1.2rem', fontWeight: 700 }}>
          Full analysis
          <span style={{ fontWeight: 400, color: 'var(--text-muted)', fontSize: '0.9rem', marginLeft: 10 }}>
            {review.opening_name || review.opening_eco || 'Practice game'} · you played {review.player_color}
          </span>
        </h1>
      </div>

      <p style={{ fontSize: '0.78rem', color: 'var(--text-muted)', margin: '0 0 18px' }}>
        {playerMoves.length} of your moves, in order.{' '}
        {counts.length > 0
          ? counts.map(c => `${c.n} ${c.severity.toLowerCase()}${c.n === 1 ? '' : 's'}`).join(' · ')
          : 'Nothing was flagged.'}
        {review.accuracy != null && (
          <span className="mono"> · {Math.round(review.accuracy * 10) / 10}% accuracy</span>
        )}
      </p>

      <BoardSplit boardBasis={380} gap={24} stickyBoard>
        {/* Sticky is applied by BoardSplit, and only while side by side. */}
        <div>
          <div className="card" style={{ padding: 12 }}>
            <ChessBoard
              fen={current.fen_before}
              playerColor={review.player_color}
              arrows={arrows}
            />
            <div style={{ marginTop: 12, fontSize: '0.78rem', color: 'var(--text-muted)' }}>
              Position before {current.move_number}.{current.color === 'black' ? '..' : ''} {current.san}
              {!current.by_player && ' · opponent'}
            </div>
            {mistake && (
              <div style={{ marginTop: 6, fontSize: '0.72rem', display: 'flex', gap: 14 }}>
                <span className="stat-value" style={{ color: 'var(--loss)', fontWeight: 500 }}>
                  ▶ {current.san}
                </span>
                {mistake.better_move && (
                  <span className="stat-value" style={{ color: 'var(--gain)', fontWeight: 500 }}>
                    ✓ {mistake.better_move}
                  </span>
                )}
              </div>
            )}
          </div>

          <div style={{ display: 'flex', gap: 8, marginTop: 10, alignItems: 'center' }}>
            <button className="secondary" onClick={() => step(-1)} disabled={cursor === 0}
                    style={{ padding: '5px 12px' }} aria-label="Previous move">←</button>
            <button className="secondary" onClick={() => step(1)} disabled={cursor >= moves.length - 1}
                    style={{ padding: '5px 12px' }} aria-label="Next move">→</button>
            <span className="mono" style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
              {cursor + 1} / {moves.length}
            </span>
          </div>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {mistake ? (
            <MoveErrorCard error={mistake} isSelected />
          ) : (
            <div className="card" style={{ padding: '14px 16px' }}>
              <p style={{ margin: 0, fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
                {current.by_player
                  ? 'The engine found nothing worth flagging here — this move cost less than an inaccuracy.'
                  : 'Your opponent’s move. Only your side was analysed.'}
              </p>
            </div>
          )}

          <div className="card" style={{ padding: '14px 16px' }}>
            <div style={{ fontSize: '0.8rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: 10 }}>
              Every move
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              {pairUp(moves).map(([number, row]) => (
                <div key={number} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span className="mono" style={{
                    fontSize: '0.72rem', color: 'var(--text-tertiary)', minWidth: 26, textAlign: 'right',
                  }}>
                    {number}.
                  </span>
                  {row.white
                    ? <MoveChip move={row.white} active={row.white === current}
                                onSelect={() => setCursor(moves.indexOf(row.white!))} />
                    : <span style={{ minWidth: 62 }} />}
                  {row.black
                    ? <MoveChip move={row.black} active={row.black === current}
                                onSelect={() => setCursor(moves.indexOf(row.black!))} />
                    : null}
                </div>
              ))}
            </div>
          </div>
        </div>
      </BoardSplit>

      <PraxAnchor x={0.95} y={0.38} />
    </div>
  )
}
