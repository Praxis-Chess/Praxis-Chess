import { useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { useGameAnalysis } from '../hooks/useGameAnalysis'
import { MoveErrorCard } from '../components/MoveErrorCard'
import { ChessBoard } from '../components/ChessBoard'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { buildArrows } from '../components/moveArrows'
import type { MoveError } from '../api/types'
import { PraxAnchor } from '../prax/PraxHost'
import { BoardSplit } from '../components/BoardSplit'
import { WhyPanel, type WhyBoardView } from '../components/WhyPanel'

export function GameAnalysis() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [selectedError, setSelectedError] = useState<MoveError | null>(null)
  // A step of the "Why?" panel, shown on the board in place of the mistake's position.
  const [whyBoard, setWhyBoard] = useState<WhyBoardView | null>(null)

  const select = (error: MoveError | null) => {
    setSelectedError(error)
    setWhyBoard(null)
  }

  const { data: game } = useQuery({
    queryKey: ['game', id],
    queryFn: () => api.games.get(id!),
    enabled: !!id,
  })

  const { data: errors, isLoading } = useGameAnalysis(id ?? null)

  if (isLoading) return <LoadingSpinner label="Loading analysis…" />

  const arrows = whyBoard
    ? whyBoard.arrows
    : selectedError
      ? buildArrows(selectedError.fen_position, selectedError.move_played, selectedError.better_move)
      : []

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20 }}>
        <button className="secondary" onClick={() => navigate(-1)} style={{ padding: '6px 12px' }}>
          ← Back
        </button>
        <h1 style={{ fontSize: '1.2rem', fontWeight: 700 }}>
          Game Analysis
          {game && (
            <span style={{ fontWeight: 400, color: 'var(--text-muted)', fontSize: '0.9rem', marginLeft: 10 }}>
              {game.opening_eco} · {game.player_color} · {game.result}
            </span>
          )}
        </h1>
        {/* Dev only: the Phase 2 evidence lab re-explains this game from a
            backend-rendered block. It is a measurement surface, not a feature,
            so it stays out of the shipped UI the way PraxDebugPanel does. */}
        {import.meta.env.DEV && id && (
          <Link
            to={`/evidence/${id}`}
            className="secondary"
            style={{ marginLeft: 'auto', padding: '6px 12px', fontSize: '0.8rem' }}
          >
            Evidence lab
          </Link>
        )}
      </div>

      <BoardSplit boardBasis={380} gap={24}>
        {/* Board */}
        <div className="card" style={{ padding: 12 }}>
          <ChessBoard
            fen={whyBoard?.fen ?? selectedError?.fen_position}
            playerColor={game?.player_color}
            arrows={arrows}
          />
          {whyBoard && (
            <div
              aria-label="Board caption"
              style={{ marginTop: 12, fontSize: '0.78rem', color: 'var(--text-muted)', display: 'flex', gap: 10, alignItems: 'center' }}
            >
              <span>{whyBoard.title}</span>
              <button
                className="secondary"
                onClick={() => setWhyBoard(null)}
                style={{ marginLeft: 'auto', padding: '2px 8px', fontSize: '0.72rem' }}
              >
                Back to the position
              </button>
            </div>
          )}
          {selectedError && !whyBoard && (
            <div style={{ marginTop: 12, fontSize: '0.78rem', color: 'var(--text-muted)' }}>
              Position before move {Math.ceil(selectedError.move_number / 2)}.{selectedError.move_played}
            </div>
          )}
          {selectedError && !whyBoard && arrows.length > 0 && (
            <div style={{ marginTop: 6, fontSize: '0.72rem', display: 'flex', gap: 14 }}>
              <span className="stat-value" style={{ color: 'var(--loss)', fontWeight: 500 }}>▶ {selectedError.move_played}</span>
              {selectedError.better_move && (
                <span className="stat-value" style={{ color: 'var(--gain)', fontWeight: 500 }}>✓ {selectedError.better_move}</span>
              )}
            </div>
          )}
          {!selectedError && (
            <div style={{ marginTop: 12, fontSize: '0.78rem', color: 'var(--text-muted)', textAlign: 'center' }}>
              Select a mistake to see the position
            </div>
          )}
        </div>

        {/* Mistakes list */}
        <div>
          {!errors?.length && (
            <p style={{ color: 'var(--text-muted)' }}>No mistakes identified for this game.</p>
          )}
          {errors && errors.length > 0 && (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
                <h2 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-muted)' }}>
                  {errors.length} mistake{errors.length !== 1 ? 's' : ''} identified
                </h2>
                <span style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>· Click to see position</span>
              </div>
              {errors
                .sort((a, b) => a.move_number - b.move_number)
                .map((error) => (
                  <div key={error.id}>
                    <MoveErrorCard
                      error={error}
                      isSelected={selectedError?.id === error.id}
                      onClick={() => select(selectedError?.id === error.id ? null : error)}
                    />
                    {/* Under the selected card only: one mistake's "why" at a time,
                        and its steps drive the board beside it. */}
                    {id && selectedError?.id === error.id && (
                      <WhyPanel gameId={id} ply={error.move_number} recordedBest={error.better_move}
                        onShowBoard={setWhyBoard} />
                    )}
                  </div>
                ))}
            </>
          )}
        </div>
      </BoardSplit>

      {/* Contract §4 — the PAGE decides where Prax belongs. Without this
          the registry falls back to a fixed 0.68/0.46, which on this
          layout is directly on top of the content. */}
      {/* Right of the mistake list, which runs the full height of the page. */}
      <PraxAnchor x={0.95} y={0.38} />
    </div>
  )
}
