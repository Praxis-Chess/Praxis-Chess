import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { WhyArrow, WhyStep } from '../api/types'
import { ARROW_BETTER, ARROW_PLAYED_BAD, ARROW_THREAT, type Arrow } from './moveArrows'

/**
 * "Why?" for one mistake (plan §17.2, D3).
 *
 * The mistake card's explanation stays the headline. This opens under it on
 * request and shows the rules' diagnosis: a cause checked against the engine
 * before it is shown, one step per claim. Clicking a step puts the position
 * that shows it on the page's board, with arrows for the moves the step is
 * about.
 *
 * Fetched only when opened. The first view of a mistake builds its evidence
 * (about a second of engine time); after that it is stored.
 */

/** What the board should show instead of the mistake's own position; null to go back. */
export interface WhyBoardView {
  fen: string
  arrows: Arrow[]
  title: string
}

interface Props {
  gameId: string
  ply: number
  /** The engine move stored with the game (UCI or SAN), shown on the mistake card. */
  recordedBest?: string | null
  onShowBoard: (board: WhyBoardView | null) => void
}

const CONSEQUENCE: Record<string, string> = {
  MATED: 'Got mated',
  LOST_MATERIAL: 'Lost material',
  MISSED_MATE: 'Missed a mate',
  MISSED_MATERIAL: 'Missed winning material',
  NOT_CONCRETE: 'Positional',
}

const MECHANISM: Record<string, string> = {
  IGNORED_THREAT: 'Ignored a threat',
  REMOVED_DEFENDER: 'Removed a defender',
  MOVED_INTO_ATTACK: 'Moved into attack',
  LOSING_CAPTURE: 'Losing capture',
  CREATED_TACTIC: 'Allowed a tactic',
  MISSED_OPPORTUNITY: 'Missed a chance',
}

const ARROW_COLOR: Record<WhyArrow['kind'], string> = {
  PLAYED: ARROW_PLAYED_BAD,
  BEST: ARROW_BETTER,
  REPLY: ARROW_THREAT,
  THREAT: ARROW_THREAT,
}

export function WhyPanel({ gameId, ply, recordedBest, onShowBoard }: Props) {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState<number | null>(null)

  const why = useQuery({
    queryKey: ['why', gameId, ply],
    queryFn: () => api.diagnosis.why(gameId, ply),
    enabled: open,
    staleTime: Infinity,
    retry: false,
  })

  const toggle = () => {
    if (open) {
      setActive(null)
      onShowBoard(null)
    }
    setOpen(!open)
  }

  const show = (i: number, step: WhyStep) => {
    const d = why.data?.diagnosis
    const board = d?.boards[step.board]
    if (!board) return
    if (active === i) {
      setActive(null)
      onShowBoard(null)
      return
    }
    setActive(i)
    onShowBoard({
      fen: board.fen,
      title: board.title,
      arrows: step.arrows.map(a => ({ from: a.from, to: a.to, color: ARROW_COLOR[a.kind] })),
    })
  }

  const d = why.data?.diagnosis
  // The diagnosis re-checks the position at a fixed depth, so its engine move
  // can differ from the one the game's analysis stored: on 25 of the first 101
  // real mistakes it did. Two "best moves" on one screen, unexplained, read as a bug.
  const otherBest = d && recordedBest && recordedBest !== d.best_uci && recordedBest !== d.best_san
    ? recordedBest : null

  return (
    <div style={{ margin: '-4px 0 10px' }}>
      <button
        className="secondary"
        aria-expanded={open}
        onClick={toggle}
        style={{ padding: '4px 10px', fontSize: '0.78rem' }}
      >
        {open ? 'Hide why' : 'Why?'}
      </button>

      {open && (
        <section
          aria-label="Why this was a mistake"
          style={{
            marginTop: 8,
            padding: '12px 14px',
            borderRadius: 8,
            border: '1px solid var(--border)',
            background: 'var(--surface)',
          }}
        >
          {why.isLoading && (
            <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Working out why…</p>
          )}
          {why.isError && (
            <p role="alert" style={{ fontSize: '0.8rem', color: 'var(--red)' }}>
              Couldn’t work out why for this move.
            </p>
          )}

          {d && (
            <>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 8 }}>
                <span className="badge">{CONSEQUENCE[d.consequence] ?? d.consequence}</span>
                {MECHANISM[d.mechanism] && <span className="badge">{MECHANISM[d.mechanism]}</span>}
              </div>

              <p style={{ fontSize: '0.85rem', lineHeight: 1.5, color: 'var(--text)' }}>{d.explanation}</p>

              {otherBest && (
                <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
                  This check prefers {d.best_san}; the game’s analysis listed {otherBest}. Both are
                  engine choices from separate searches, and this explanation is built on {d.best_san}.
                </p>
              )}

              {d.composite && (
                <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
                  No single cause: more than one applies, or none does. Only what happened is stated.
                </p>
              )}

              {d.steps.length > 0 && (
                <ol aria-label="Steps" style={{ margin: '10px 0 0', paddingLeft: 18 }}>
                  {d.steps.map((s, i) => (
                    <li key={i} style={{ marginBottom: 4 }}>
                      <button
                        aria-pressed={active === i}
                        onClick={() => show(i, s)}
                        style={{
                          all: 'unset',
                          cursor: 'pointer',
                          fontSize: '0.8rem',
                          lineHeight: 1.45,
                          padding: '2px 6px',
                          borderRadius: 4,
                          color: active === i ? 'var(--text)' : 'var(--text-muted)',
                          background: active === i ? 'var(--accent-dim)' : 'transparent',
                        }}
                      >
                        {s.text}
                        <span style={{ marginLeft: 6, fontSize: '0.7rem', opacity: 0.7 }}>
                          · {d.boards[s.board]?.title ?? s.board}
                        </span>
                      </button>
                    </li>
                  ))}
                </ol>
              )}

              <p style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 8 }}>
                {d.verified
                  ? 'Checked against the engine’s analysis · worked out by rules, not AI'
                  : 'This explanation did not pass the check against the engine — treat it with care'}
              </p>
            </>
          )}
        </section>
      )}
    </div>
  )
}
