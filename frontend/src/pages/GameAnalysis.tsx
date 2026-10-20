import { useEffect, useRef, useState } from 'react'
import { scrollBehavior } from '../lib/motion'
import { useParams, useNavigate, useSearchParams, Link } from 'react-router-dom'
import { uciToSan } from '../components/san'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { useGameAnalysis } from '../hooks/useGameAnalysis'
import { MoveErrorCard } from '../components/MoveErrorCard'
import { ChessBoard } from '../components/ChessBoard'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { buildArrows } from '../components/moveArrows'
import type { MoveError, ReviewMove } from '../api/types'
import { EvalGraph } from '../components/analysis/EvalGraph'
import { AnalysisMoveList } from '../components/analysis/AnalysisMoveList'
import { BoardSplit } from '../components/BoardSplit'
import { WhyPanel, type WhyBoardView } from '../components/WhyPanel'

export function GameAnalysis() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [selectedError, setSelectedError] = useState<MoveError | null>(null)
  // A step of the "Why?" panel, shown on the board in place of the mistake's position.
  const [whyBoard, setWhyBoard] = useState<WhyBoardView | null>(null)

  // Any move of the game on the board, from the move list or the graph (U5).
  const [plyView, setPlyView] = useState<ReviewMove | null>(null)

  const select = (error: MoveError | null) => {
    setSelectedError(error)
    setWhyBoard(null)
    setPlyView(null)
  }

  const { data: game } = useQuery({
    queryKey: ['game', id],
    queryFn: () => api.games.get(id!),
    enabled: !!id,
  })

  const { data: errors, isLoading } = useGameAnalysis(id ?? null)
  // The whole game: its moves and, when stored, the eval curve (U5).
  const { data: review } = useQuery({
    queryKey: ['game-review', id],
    queryFn: () => api.games.review(id!),
    enabled: !!id,
  })
  const [requeued, setRequeued] = useState(false)
  /** A move of the game: a flagged one selects its mistake; any other shows its position. */
  const pick = (move: ReviewMove) => {
    const hit = errors?.find(e => e.move_number === move.ply)
    if (hit) { select(hit); return }
    setWhyBoard(null)
    setPlyView(move)
  }

  // Opened at one move (?ply=47), e.g. from Insights' "Thrown-Away Wins": select
  // that mistake once its list arrives and bring it into view, so the link lands
  // on the move it names rather than at the top of a long list.
  const [params] = useSearchParams()
  const targetPly = params.get('ply') ? Number(params.get('ply')) : null
  const fromThrown = params.get('from') === 'thrown'
  const opened = useRef(false)
  useEffect(() => {
    if (opened.current || targetPly == null || !errors?.length) return
    const hit = errors.find(e => e.move_number === targetPly)
    if (!hit) return
    opened.current = true
    select(hit)
    requestAnimationFrame(() =>
      document.getElementById(`mistake-${hit.move_number}`)?.scrollIntoView({ block: 'center', behavior: scrollBehavior() }))
  }, [errors, targetPly])
  const target = targetPly != null ? errors?.find(e => e.move_number === targetPly) : undefined

  // U4: with no move named, open on the costliest mistake (the most winning
  // chance lost; failing that, the first blunder, then the first mistake), not
  // on the starting position.
  useEffect(() => {
    if (opened.current || targetPly != null || !errors?.length) return
    const rank = (e: MoveError) => (e.severity === 'BLUNDER' ? 3 : e.severity === 'MISTAKE' ? 2 : 1)
    const worst = [...errors].sort((a, b) =>
      (b.win_pct_drop ?? -1) - (a.win_pct_drop ?? -1) || rank(b) - rank(a) || a.move_number - b.move_number)[0]
    opened.current = true
    select(worst)
  }, [errors, targetPly])

  if (isLoading) return <LoadingSpinner label="Loading analysis…" />

  const arrows = whyBoard
    ? whyBoard.arrows
    : plyView ? []
    : selectedError
      ? buildArrows(selectedError.fen_position, selectedError.move_played, selectedError.better_move)
      : []

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20 }}>
        <button className="secondary" onClick={() => navigate(-1)} style={{ padding: '6px 12px' }}>
          ← Back
        </button>
        <h1 style={{ fontSize: 'var(--fs-h1)', fontWeight: 700 }}>
          Game Analysis
          {game && (
            <span style={{ fontWeight: 400, color: 'var(--text-muted)', fontSize: '0.9rem', marginLeft: 10 }}>
              {game.opening_eco} · {game.player_color} · {game.result}
            </span>
          )}
        </h1>
        {/* Dev only: the Phase 2 evidence lab re-explains this game from a
            backend-rendered block. It is a measurement surface, not a feature,
            so it stays out of the shipped UI. */}
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
            fen={whyBoard?.fen ?? plyView?.fen_after ?? selectedError?.fen_position}
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
          {plyView && !whyBoard && (
            <div aria-label="Board caption" style={{ marginTop: 12, fontSize: '0.78rem', color: 'var(--text-muted)', display: 'flex', gap: 10, alignItems: 'center' }}>
              <span>After {plyView.move_number}{plyView.color === 'white' ? '.' : '...'}{plyView.san}</span>
              {selectedError && (
                <button className="secondary" onClick={() => setPlyView(null)} style={{ marginLeft: 'auto', padding: '2px 8px', fontSize: '0.72rem' }}>
                  Back to the mistake
                </button>
              )}
            </div>
          )}
          {selectedError && !whyBoard && !plyView && (
            <div style={{ marginTop: 12, fontSize: '0.78rem', color: 'var(--text-muted)' }}>
              Position before move {Math.ceil(selectedError.move_number / 2)}.{selectedError.move_played}
            </div>
          )}
          {selectedError && !whyBoard && !plyView && arrows.length > 0 && (
            <div style={{ marginTop: 6, fontSize: '0.72rem', display: 'flex', gap: 14 }}>
              <span className="stat-value" style={{ color: 'var(--loss)', fontWeight: 500 }}>▶ {selectedError.move_played}</span>
              {selectedError.better_move && (
                <span className="stat-value" style={{ color: 'var(--gain)', fontWeight: 500 }}>✓ {uciToSan(selectedError.fen_position, selectedError.better_move)}</span>
              )}
            </div>
          )}
          {!selectedError && !plyView && (
            <div style={{ marginTop: 12, fontSize: '0.78rem', color: 'var(--text-muted)', textAlign: 'center' }}>
              Select a mistake to see the position
            </div>
          )}

        </div>

        {/* Mistakes list */}
        <div>
          {/* U5: the game as one line, then every move. */}
          {review && (
            <div className="card no-mark" style={{ padding: 14, marginBottom: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <span className="micro-label">Your winning chance, move by move</span>
                {review.evals
                  ? <EvalGraph evals={review.evals} moves={review.moves} playerColour={review.player_color}
                               selectedPly={plyView?.ply ?? selectedError?.move_number ?? null}
                               onPick={ply => { const m = review.moves.find(x => x.ply === ply); if (m) pick(m) }} />
                  : (
                    // Kept since U5; this game was analysed before.
                    <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
                      No curve yet: this game was analysed before curves were kept.
                      {id && (requeued
                        ? <span>Re-analysis queued</span>
                        : <button className="secondary" style={{ padding: '2px 8px', fontSize: '0.72rem' }}
                                  onClick={() => { api.games.reanalyze(id).then(() => setRequeued(true)) }}>
                            Re-analyse this game
                          </button>)}
                    </span>
                  )}
              </div>
              <AnalysisMoveList moves={review.moves} activePly={plyView?.ply ?? selectedError?.move_number ?? null} onPick={pick} />
            </div>
          )}
          {!errors?.length && (
            <p style={{ color: 'var(--text-muted)' }}>No mistakes identified for this game.</p>
          )}
          {errors && errors.length > 0 && (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
                <h2 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-muted)' }}>
                  {errors.length} mistake{errors.length !== 1 ? 's' : ''} identified
                </h2>

              </div>
              {fromThrown && target && (
                <p role="status" style={{
                  margin: '0 0 12px', padding: '8px 12px', borderLeft: '2px solid var(--red)',
                  background: 'var(--surface)', fontSize: '0.8rem', color: 'var(--text-muted)',
                }}>
                  You were winning in this game. The win slipped at{' '}
                  <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--red)' }}>
                    {Math.ceil(target.move_number / 2)}{target.move_number % 2 === 1 ? '.' : '...'}{target.move_played}
                  </span>
                  , selected below.
                </p>
              )}
              {errors
                .sort((a, b) => a.move_number - b.move_number)
                .map((error) => (
                  <div key={error.id} id={`mistake-${error.move_number}`} style={{ scrollMarginTop: 80 }}>
                    <MoveErrorCard
                      error={error}
                      isSelected={selectedError?.id === error.id}
                      onClick={() => select(selectedError?.id === error.id ? null : error)}
                    />
                    {/* Under the selected card only: one mistake's "why" at a time,
                        and its steps drive the board beside it. */}
                    {id && selectedError?.id === error.id && (
                      <>
                        {/* Reached from a report, this is not a dead end: drill exactly this position (F7). */}
                        {error.better_move && (
                          <Link to={`/drills?positions=${id}:${error.move_number}`}
                                style={{ display: 'inline-block', margin: '6px 0 10px', fontSize: '0.8rem' }}>
                            Drill this position →
                          </Link>
                        )}
                        <WhyPanel gameId={id} ply={error.move_number} recordedBest={error.better_move}
                          onShowBoard={setWhyBoard} />
                      </>
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
    </div>
  )
}
