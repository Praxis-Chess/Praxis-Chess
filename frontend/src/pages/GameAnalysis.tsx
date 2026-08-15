import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Chess } from 'chess.js'
import { api } from '../api/client'
import { useGameAnalysis } from '../hooks/useGameAnalysis'
import { MoveErrorCard } from '../components/MoveErrorCard'
import { ChessBoard } from '../components/ChessBoard'
import { LoadingSpinner } from '../components/LoadingSpinner'
import type { MoveError } from '../api/types'

const UCI_PATTERN = /^[a-h][1-8][a-h][1-8][qrbn]?$/

function moveToSquares(fen: string, notation: string): { from: string; to: string } | null {
  try {
    const chess = new Chess(fen)
    // Engine bestmoves are stored as UCI (e.g. "e2e4"); SAN for move_played
    const move = UCI_PATTERN.test(notation)
      ? chess.move({ from: notation.slice(0, 2), to: notation.slice(2, 4), promotion: notation[4] })
      : chess.move(notation)
    return move ? { from: move.from, to: move.to } : null
  } catch {
    return null
  }
}

function getArrows(error: MoveError) {
  if (!error.fen_position) return []
  const arrows: { from: string; to: string; color: string }[] = []

  const played = moveToSquares(error.fen_position, error.move_played)
  if (played) arrows.push({ ...played, color: 'rgba(226, 102, 74, 0.9)' })

  if (error.better_move) {
    const better = moveToSquares(error.fen_position, error.better_move)
    if (better) arrows.push({ ...better, color: 'rgba(185, 217, 108, 0.9)' })
  }

  return arrows
}

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

  const arrows = selectedError ? getArrows(selectedError) : []

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
    </div>
  )
}
