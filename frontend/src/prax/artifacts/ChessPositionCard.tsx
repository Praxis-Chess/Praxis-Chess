import { useState } from 'react'
import { Chessboard } from 'react-chessboard'
import type { Square } from 'react-chessboard/dist/chessboard/types'
import type { ArrowRole, ChessPositionArtifact, MoveComparisonArtifact } from './types'

/**
 * Arrow colours.
 *
 * Deliberately literal hex rather than CSS custom properties: react-chessboard
 * passes these straight into an SVG `stroke` ATTRIBUTE, and var() resolves only
 * in CSS properties, not in XML attributes. A `var(--gain)` here renders as no
 * stroke at all — an invisible arrow, which is worse than a wrong colour
 * because nothing looks broken.
 *
 * Chosen to survive both themes: these sit on the board's own light/dark squares,
 * not on the app background, so they do not need to change with the theme.
 */
const ARROW_COLOR: Record<ArrowRole, string> = {
  BEST: '#4FA97B',    // what you should have played
  PLAYED: '#E2664A',  // what you did play
  THREAT: '#D9A441',  // what was coming
}

const ROLE_LABEL: Record<ArrowRole, string> = {
  BEST: 'Engine',
  PLAYED: 'You played',
  THREAT: 'Threat',
}

/**
 * The board, with the mistake drawn on it.
 *
 * This is the feature: Prax already knew the position, the move, the engine's
 * preference and the cost. It rendered them as four rows of numbers. The same
 * facts on a board are read in a glance.
 */
export function ChessPositionCard({ artifact }: { artifact: ChessPositionArtifact }) {
  /**
   * Phase 5 — the board can be turned round.
   *
   * It opens from the side that moved, which is right by default. But a player
   * reviewing an opponent's threat often wants the other view, and re-orienting
   * a diagram in your head is exactly the work a diagram is supposed to save.
   */
  const [flipped, setFlipped] = useState(false)
  const orientation = flipped
    ? (artifact.orientation === 'white' ? 'black' : 'white')
    : artifact.orientation

  const arrows = artifact.arrows.map(a =>
    [a.from as Square, a.to as Square, ARROW_COLOR[a.role]] as [Square, Square, string],
  )

  const squareStyles: Record<string, React.CSSProperties> = {}
  for (const sq of artifact.highlights) {
    squareStyles[sq] = { boxShadow: 'inset 0 0 0 3px rgba(198, 154, 214, 0.85)' }
  }

  // Which roles are actually on this board, so the key never advertises an
  // arrow the player cannot see.
  const roles = Array.from(new Set(artifact.arrows.map(a => a.role)))

  return (
    <figure style={{ margin: 0 }}>
      <figcaption style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 10,
        fontSize: '0.6rem', letterSpacing: '0.08em', textTransform: 'uppercase',
        color: 'var(--text-tertiary, #625C6D)', marginBottom: 7,
      }}>
        <span>{artifact.title}</span>
        <button
          onClick={() => setFlipped(f => !f)}
          aria-label="Flip the board"
          style={{
            background: 'transparent', border: 'none', cursor: 'pointer',
            color: 'inherit', font: 'inherit', letterSpacing: 'inherit', padding: 0,
          }}
        >
          Flip
        </button>
      </figcaption>

      <div style={{ maxWidth: 300 }}>
        <Chessboard
          position={artifact.fen}
          boardOrientation={orientation}
          customArrows={arrows}
          customSquareStyles={squareStyles}
          // A diagram, not a game. Dragging pieces here would imply the position
          // can be played from, which it cannot.
          arePiecesDraggable={false}
          customBoardStyle={{ borderRadius: 5 }}
          boardWidth={300}
        />
      </div>

      {roles.length > 0 && (
        <div style={{ display: 'flex', gap: 12, marginTop: 7, flexWrap: 'wrap' }}>
          {roles.map(role => (
            <span key={role} style={{
              display: 'inline-flex', alignItems: 'center', gap: 5,
              fontSize: '0.65rem', color: 'var(--text-tertiary, #625C6D)',
            }}>
              <span style={{
                width: 10, height: 2, background: ARROW_COLOR[role],
                display: 'inline-block', borderRadius: 1,
              }} />
              {ROLE_LABEL[role]}
            </span>
          ))}
        </div>
      )}

      {artifact.caption && (
        <p style={{
          margin: '7px 0 0', fontSize: '0.7rem', lineHeight: 1.4,
          color: 'var(--text-secondary, #B4AEBE)',
        }}>
          {artifact.caption}
        </p>
      )}
    </figure>
  )
}

/**
 * The board, plus what the move cost.
 *
 * The board half is the same component — a comparison must never render a board
 * that behaves differently from the one beside it. This adds only the numbers,
 * which is the whole difference between the two artifact types.
 */
export function MoveComparisonCard({ artifact }: { artifact: MoveComparisonArtifact }) {
  const rows: [string, string][] = []
  if (artifact.played_move) rows.push(['Your move', artifact.played_move])
  if (artifact.best_move) rows.push(['Engine', artifact.best_move])
  // Null means NOT MEASURED. Rendering it as 0 would say the move cost nothing,
  // which is the opposite of unknown.
  if (artifact.loss_pawns != null) {
    rows.push(['Cost', `${artifact.loss_pawns.toFixed(2)} pawns`])
  }

  return (
    <div>
      <ChessPositionCard
        artifact={{
          type: 'CHESS_POSITION',
          id: artifact.id,
          title: artifact.title,
          fen: artifact.fen,
          orientation: artifact.orientation,
          highlights: artifact.highlights,
          arrows: artifact.arrows,
          caption: artifact.caption,
        }}
      />

      {rows.length > 0 && (
        <div style={{
          display: 'flex', flexDirection: 'column', gap: 3, marginTop: 9,
          paddingTop: 8, borderTop: '1px solid var(--hairline, #26232B)',
        }}>
          {rows.map(([label, value]) => (
            <div key={label} style={{
              display: 'flex', justifyContent: 'space-between', gap: 12,
              fontSize: '0.73rem', fontFamily: 'var(--font-mono, monospace)',
            }}>
              <span style={{ color: 'var(--text-tertiary, #625C6D)' }}>{label}</span>
              <span style={{ color: 'var(--text-secondary, #B4AEBE)' }}>{value}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
