import { useMemo, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Chessboard } from 'react-chessboard'
import { Chess, type Square } from 'chess.js'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Card, AttemptRating, Session as SessionType } from '../api/types'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { praxBus, PraxAnchor } from '../prax/PraxHost'

type Stage = 'solving' | 'rating' | 'done'

const sevColor = (s: string | null) =>
  s === 'BLUNDER' ? 'var(--red)' : s === 'MISTAKE' ? 'var(--yellow)' : 'var(--text-muted)'

function RatingButton({ rating, label, sublabel, onClick }: {
  rating: AttemptRating; label: string; sublabel: string; onClick: () => void
}) {
  const colors: Record<AttemptRating, string> = {
    AGAIN: 'var(--red)', HARD: 'var(--yellow)', GOOD: 'var(--gain)', EASY: 'var(--orchid)',
  }
  return (
    <button
      onClick={onClick}
      style={{
        display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3,
        padding: '10px 16px', borderRadius: 8, border: `1.5px solid ${colors[rating]}`,
        background: 'transparent', color: colors[rating], cursor: 'pointer',
        minWidth: 80, transition: 'background 0.15s',
      }}
      onMouseEnter={e => (e.currentTarget.style.background = `${colors[rating]}18`)}
      onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
    >
      <span style={{ fontWeight: 700, fontSize: '0.9rem' }}>{label}</span>
      <span style={{ fontSize: '0.68rem', color: 'var(--text-muted)' }}>{sublabel}</span>
    </button>
  )
}

function CardView({ card, sessionId, onRated }: {
  card: Card; sessionId: string; onRated: (session: SessionType) => void
}) {
  const [boardFen, setBoardFen] = useState(card.fen_position)
  const [stage, setStage] = useState<Stage>('solving')
  const [wrong, setWrong] = useState(false)
  const [startMs] = useState(() => Date.now())
  const [firstResponseMs, setFirstResponseMs] = useState<number | null>(null)
  const [correct, setCorrect] = useState(false)
  const [selectedSquare, setSelectedSquare] = useState<Square | null>(null)

  const answerArrow = useMemo(() => {
    if (stage === 'solving' || !card.better_move) return []
    const from = card.better_move.slice(0, 2)
    const to   = card.better_move.slice(2, 4)
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return [[from, to, 'rgba(185, 217, 108, 0.9)']] as any[]
  }, [stage, card.better_move])

  // Highlight selected square + valid destination dots
  const squareStyles = useMemo(() => {
    if (!selectedSquare || stage !== 'solving') return {}
    const chess = new Chess(boardFen)
    const moves = chess.moves({ square: selectedSquare, verbose: true })
    const styles: Record<string, React.CSSProperties> = {
      [selectedSquare]: { backgroundColor: 'rgba(231,166,214,0.35)' },
    }
    for (const m of moves) {
      const isCapture = !!chess.get(m.to)
      styles[m.to] = isCapture
        ? { boxShadow: 'inset 0 0 0 3px rgba(231,166,214,0.75)' }
        : { background: 'radial-gradient(circle, rgba(231,166,214,0.6) 28%, transparent 30%)' }
    }
    return styles
  }, [selectedSquare, boardFen, stage])

  /** Shared move outcome logic for both drag and click */
  function handleMove(chess: InstanceType<typeof Chess>, move: { from: string; to: string; promotion?: string }) {
    const uci = move.from + move.to + (move.promotion ?? '')
    const ms = firstResponseMs === null ? Date.now() - startMs : firstResponseMs
    if (firstResponseMs === null) setFirstResponseMs(ms)
    if (uci === card.better_move) {
      setBoardFen(chess.fen())
      setCorrect(true)
      setStage('rating')
      praxBus.emit({ type: 'DRILL_CORRECT' })
    } else {
      setWrong(true)
      praxBus.emit({ type: 'DRILL_WRONG' })
      setTimeout(() => setWrong(false), 500)
    }
  }

  function onDrop(source: string, target: string): boolean {
    if (stage !== 'solving') return false
    setSelectedSquare(null)
    const chess = new Chess(card.fen_position)
    let move
    try { move = chess.move({ from: source, to: target, promotion: 'q' }) }
    catch { return false }
    if (!move) return false
    handleMove(chess, move)
    return false // always revert board; we control position via boardFen state
  }

  function onSquareClick(square: Square) {
    if (stage !== 'solving') return
    const chess = new Chess(boardFen)

    if (!selectedSquare) {
      const moves = chess.moves({ square, verbose: true })
      if (moves.length > 0) setSelectedSquare(square)
      return
    }

    if (selectedSquare === square) { setSelectedSquare(null); return }

    const hasMoveToSquare = chess.moves({ square: selectedSquare, verbose: true })
      .some(m => m.to === square)

    if (hasMoveToSquare) {
      setSelectedSquare(null)
      const chess2 = new Chess(boardFen)
      let move
      try { move = chess2.move({ from: selectedSquare, to: square, promotion: 'q' }) }
      catch { return }
      if (move) handleMove(chess2, move)
      return
    }

    // Maybe clicking another own piece
    const newMoves = chess.moves({ square, verbose: true })
    setSelectedSquare(newMoves.length > 0 ? square : null)
  }

  function reveal() {
    setSelectedSquare(null)
    setStage('rating')
    setCorrect(false)
  }

  const [rateError, setRateError] = useState<string | null>(null)

  const { mutate: record, isPending: recording } = useMutation({
    mutationFn: (rating: AttemptRating) =>
      api.sessions.recordAttempt(sessionId, {
        card_id: card.id,
        move_played: correct ? card.better_move : null,
        correct,
        rating,
        response_ms: firstResponseMs,
      }),
    onSuccess: onRated,
    onError: (err: Error) => setRateError(err.message ?? 'Failed to record rating'),
  })

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 24, padding: 24 }}>
      {/* The board is centred and this route has no nav, so Prax sits high and
          right — out of the way of the position, still present while you work. */}
      <PraxAnchor x={0.86} y={0.24} />

      {/* Header */}
      <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
        {card.severity && (
          <span style={{ fontSize: '0.72rem', fontWeight: 700, color: sevColor(card.severity),
                         padding: '2px 8px', borderRadius: 10, border: `1px solid ${sevColor(card.severity)}` }}>
            {card.severity}
          </span>
        )}
        {card.tactical_motif && (
          <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
            {card.tactical_motif.replace(/_/g, ' ').toLowerCase()}
          </span>
        )}
        {card.game_phase && (
          <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
            · {card.game_phase.toLowerCase()}
          </span>
        )}
      </div>

      {/* Board */}
      <div style={{
        borderRadius: 8, overflow: 'hidden',
        boxShadow: wrong ? '0 0 0 3px var(--red)' : correct && stage === 'rating' ? '0 0 0 3px var(--gain)' : 'none',
        transition: 'box-shadow 0.2s',
      }}>
        <Chessboard
          position={boardFen}
          boardOrientation={(card.player_color ?? 'white') as 'white' | 'black'}
          onPieceDrop={onDrop}
          onSquareClick={onSquareClick}
          arePiecesDraggable={stage === 'solving'}
          customArrows={answerArrow}
          customDarkSquareStyle={{ backgroundColor: '#4A4340' }}
          customLightSquareStyle={{ backgroundColor: '#E3DBD1' }}
          customBoardStyle={{ borderRadius: '2px', border: '1px solid rgba(255,255,255,0.08)' }}
          customDropSquareStyle={{ boxShadow: 'inset 0 0 0 2px rgba(231,166,214,0.7)' }}
          customSquareStyles={squareStyles}
          boardWidth={360}
        />
      </div>

      {/* Prompt */}
      <div style={{ textAlign: 'center', maxWidth: 380 }}>
        {stage === 'solving' && (
          <>
            <div style={{ fontWeight: 700, fontSize: '1rem', marginBottom: 8 }}>
              Find the best move for {card.player_color}
            </div>
            <div style={{ fontSize: '0.82rem', color: 'var(--text-muted)', marginBottom: 16 }}>
              You played <b style={{ color: 'var(--red)' }}>{card.move_played}</b>. There was something better.
            </div>
            <button className="secondary" style={{ fontSize: '0.8rem' }} onClick={reveal}>
              Show answer
            </button>
          </>
        )}

        {stage === 'rating' && (
          <>
            {correct ? (
              <div style={{ fontWeight: 700, color: 'var(--gain)', marginBottom: 8 }}>✓ Correct!</div>
            ) : (
              <div style={{ marginBottom: 8 }}>
                <span style={{ fontWeight: 700, color: 'var(--accent)' }}>Best: </span>
                <span style={{ fontFamily: 'monospace' }}>{card.better_move}</span>
              </div>
            )}
            {card.explanation && (
              <p style={{ fontSize: '0.82rem', color: 'var(--text-muted)', lineHeight: 1.6, marginBottom: 18 }}>
                {card.explanation}
              </p>
            )}
            <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginBottom: 10 }}>
              How well did you recall this?
            </div>
            {rateError && (
              <div style={{ fontSize: '0.78rem', color: 'var(--red)', marginBottom: 10 }}>
                {rateError}
              </div>
            )}
            <div style={{ display: 'flex', gap: 10, justifyContent: 'center', opacity: recording ? 0.5 : 1, pointerEvents: recording ? 'none' : 'auto' }}>
              <RatingButton rating="AGAIN"  label="Again"  sublabel="Forgot"    onClick={() => { setRateError(null); record('AGAIN') }} />
              <RatingButton rating="HARD"   label="Hard"   sublabel="Difficult" onClick={() => { setRateError(null); record('HARD') }}  />
              <RatingButton rating="GOOD"   label="Good"   sublabel="Recalled"  onClick={() => { setRateError(null); record('GOOD') }}  />
              <RatingButton rating="EASY"   label="Easy"   sublabel="Effortless" onClick={() => { setRateError(null); record('EASY') }} />
            </div>
          </>
        )}
      </div>
    </div>
  )
}

export function Session() {
  const { id: sessionId } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [cardKey, setCardKey] = useState(0) // force remount on next card

  const { data: session } = useQuery({
    queryKey: ['session', sessionId],
    queryFn: () => api.sessions.get(sessionId!),
    enabled: !!sessionId,
    staleTime: Infinity, // updated via setQueryData in onRated; no background refetches needed
  })

  const { data: card, isLoading: cardLoading, isError: cardError } = useQuery({
    queryKey: ['session-card', sessionId, cardKey],
    queryFn: () => sessionId ? api.sessions.nextCard(sessionId) : null,
    enabled: !!sessionId && !!session && !session.completed,
    staleTime: 0,
    retry: 1,
  })

  function onRated(updated: import('../api/types').Session) {
    queryClient.setQueryData(['session', sessionId], updated)
    if (updated.completed) return
    setCardKey(k => k + 1)
  }

  if (!session) return <LoadingSpinner label="Loading session…" />

  if (session.completed) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center',
                    justifyContent: 'center', minHeight: '60vh', gap: 20 }}>
        <div style={{ fontSize: '3rem' }}>🎉</div>
        <h2 style={{ fontSize: '1.4rem', fontWeight: 700 }}>Session complete</h2>
        <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
          {session.cards_completed} card{session.cards_completed !== 1 ? 's' : ''} reviewed.
        </p>
        <button onClick={() => navigate('/')}>Back to Today</button>
      </div>
    )
  }

  const progress = session.cards_total > 0
    ? Math.round(session.cards_completed / session.cards_total * 100) : 0

  return (
    <div style={{ maxWidth: 520, margin: '0 auto' }}>
      {/* Slim progress bar */}
      <div style={{ height: 3, background: 'var(--surface-2)', borderRadius: 2, marginBottom: 20, overflow: 'hidden' }}>
        <div style={{ height: '100%', width: `${progress}%`, background: 'var(--orchid)',
                      transition: 'width 0.4s ease', borderRadius: 2 }} />
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.75rem',
                    color: 'var(--text-muted)', marginBottom: 16 }}>
        <span>{session.cards_completed} / {session.cards_total}</span>
        <button className="secondary" style={{ fontSize: '0.75rem', padding: '2px 8px' }}
                onClick={() => navigate('/')}>Exit</button>
      </div>

      {cardError ? (
        <div style={{ padding: 40, textAlign: 'center' }}>
          <div style={{ fontSize: '1.5rem', marginBottom: 12 }}>⚠️</div>
          <div style={{ fontWeight: 600, marginBottom: 8 }}>Failed to load card</div>
          <div style={{ fontSize: '0.82rem', color: 'var(--text-muted)', marginBottom: 20 }}>
            Something went wrong fetching this card. Please try again.
          </div>
          <button onClick={() => setCardKey(k => k + 1)}>Retry</button>
        </div>
      ) : cardLoading ? (
        <LoadingSpinner label="Loading next card…" />
      ) : card ? (
        <div className="card" style={{ padding: 0 }}>
          <CardView key={`${sessionId}-${cardKey}`} card={card} sessionId={sessionId!} onRated={onRated} />
        </div>
      ) : (
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-muted)' }}>
          No cards due. Come back tomorrow!
        </div>
      )}
    </div>
  )
}
