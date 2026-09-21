import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { useGameAnalysis } from '../hooks/useGameAnalysis'
import { MoveErrorCard } from '../components/MoveErrorCard'
import { ChessBoard } from '../components/ChessBoard'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { buildArrows } from '../components/moveArrows'
import type { MoveError } from '../api/types'
import { PraxAnchor } from '../prax/PraxHost'

export function GameAnalysis() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [selectedError, setSelectedError] = useState<MoveError | null>(null)

  const { data: game } = useQuery({
    queryKey: ['game', id],
    queryFn: () => api.games.get(id!),
    enabled: !!id,
  })

  const { data: errors, isLoading } = useGameAnalysis(id ?? null)

  if (isLoading) return <LoadingSpinner label="Loading analysis…" />

  const arrows = selectedError
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
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '380px 1fr', gap: 24, alignItems: 'start' }}>
        {/* Board */}
        <div className="card" style={{ padding: 12 }}>
          <ChessBoard
            fen={selectedError?.fen_position}
            playerColor={game?.player_color}
            arrows={arrows}
          />
          {selectedError && (
            <div style={{ marginTop: 12, fontSize: '0.78rem', color: 'var(--text-muted)' }}>
              Position before move {Math.ceil(selectedError.move_number / 2)}.{selectedError.move_played}
            </div>
          )}
          {selectedError && arrows.length > 0 && (
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
                  <MoveErrorCard
                    key={error.id}
                    error={error}
                    isSelected={selectedError?.id === error.id}
                    onClick={() => setSelectedError(selectedError?.id === error.id ? null : error)}
                  />
                ))}
            </>
          )}
        </div>
      </div>

      {/* Contract §4 — the PAGE decides where Prax belongs. Without this
          the registry falls back to a fixed 0.68/0.46, which on this
          layout is directly on top of the content. */}
      {/* Right of the mistake list, which runs the full height of the page. */}
      <PraxAnchor x={0.95} y={0.38} />
    </div>
  )
}
