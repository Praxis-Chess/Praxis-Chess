import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { PracticeGameSummary, PracticePattern } from '../api/types'
import { dayMonth, drillLink, muted, sectionLabel } from './report/ReportParts'

/**
 * Play → Practice so far (PLAY_PLAN.md, P2/P3).
 *
 * Earlier games as a table (result, date, the opening each was aimed at,
 * accuracy, what was flagged), then the patterns across them. Below the games
 * threshold there is no pattern to name, so the section says how far along it
 * is ("1 of 5 games") rather than printing caveats about nothing.
 *
 * Neutral throughout: result colours only for the result; pink only for what
 * can be clicked.
 */

const RESULT: Record<string, { word: string; colour: string }> = {
  win: { word: 'Won', colour: 'var(--gain)' },
  loss: { word: 'Lost', colour: 'var(--loss)' },
  draw: { word: 'Drew', colour: 'var(--text-secondary)' },
}

const plural = (n: number, one: string) => `${n} ${one}${n === 1 ? '' : 's'}`

/** "1 mistake", "1 blunder · 2 mistakes", "none": as the review counts them. */
function flagged(g: PracticeGameSummary): string {
  if (g.blunders == null && g.mistakes == null && g.inaccuracies == null) return '–'
  const parts = [
    g.blunders ? plural(g.blunders, 'blunder') : null,
    g.mistakes ? plural(g.mistakes, 'mistake') : null,
    g.inaccuracies ? (g.inaccuracies === 1 ? '1 inaccuracy' : `${g.inaccuracies} inaccuracies`) : null,
  ].filter(Boolean)
  return parts.length ? parts.join(' · ') : 'none'
}

const cell: React.CSSProperties = { padding: '6px 8px', textAlign: 'left' }

export function PracticeSoFar({ games }: { games: PracticeGameSummary[] }) {
  const navigate = useNavigate()
  const { data: report } = useQuery({ queryKey: ['play-patterns'], queryFn: () => api.play.patterns(), staleTime: 60_000 })
  const [why, setWhy] = useState(false)

  // Games you actually finished: a session row is created the moment a game
  // starts, so a closed tab leaves rows that were never games.
  const played = games.filter(g => g.status === 'FINISHED')
  const considered = report?.games_considered ?? 0
  const minGames = report?.min_games ?? 5
  if (played.length === 0 && considered === 0) return null

  const progress = considered >= minGames
    ? `${considered} games · patterns from ${minGames}`
    : `${considered} of ${minGames} games · patterns from ${minGames}`
  const notes = [...(report?.not_measured ?? []), report?.opening_caveat].filter(Boolean)
  const patterns = report?.patterns ?? []
  // Every game aimed at one opening: the note line says so once, so no column repeats it.
  const oneOpening = !!report?.aimed_at && played.every(g => g.target_opening === report.aimed_at)

  return (
    <section aria-labelledby="practice-title" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 12, flexWrap: 'wrap' }}>
        <h3 id="practice-title" style={{ fontSize: '0.95rem', fontWeight: 700, margin: 0 }}>Practice so far</h3>
        <span className="micro-label" aria-live="polite">{progress}</span>
      </div>

      {played.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem' }}>
            <thead>
              <tr>
                <th scope="col" style={cell}>Result</th>
                <th scope="col" style={cell}>Date</th>
                {!oneOpening && <th scope="col" style={cell}>Aimed at</th>}
                <th scope="col" style={{ ...cell, textAlign: 'right' }}>Accuracy</th>
                <th scope="col" style={cell}>Flagged</th>
                <th scope="col" style={cell} aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {played.slice(0, 10).map(g => {
                const r = RESULT[g.result ?? 'draw'] ?? RESULT.draw
                const reviewable = g.analysed && g.game_id !== null
                return (
                  <tr key={g.id} style={{ borderTop: '1px solid var(--hairline)' }}>
                    <th scope="row" style={{ ...cell, fontWeight: 600, color: r.colour, whiteSpace: 'nowrap' }}>
                      {r.word}
                      {!g.rated && <span style={{ ...muted, fontWeight: 400, fontSize: '0.7rem' }}> · unrated</span>}
                    </th>
                    <td className="mono" style={{ ...cell, whiteSpace: 'nowrap' }}>{dayMonth(new Date(g.started_at))}</td>
                    {!oneOpening && <td style={cell}>{g.target_opening ?? 'open'}</td>}
                    <td className="mono" style={{ ...cell, textAlign: 'right' }}>
                      {g.accuracy != null ? `${Math.round(g.accuracy * 10) / 10}%` : '–'}
                    </td>
                    <td style={{ ...cell, color: 'var(--text-secondary)' }}>{g.analysed ? flagged(g) : 'not analysed'}</td>
                    <td style={{ ...cell, textAlign: 'right' }}>
                      {reviewable && (
                        <button className="secondary" onClick={() => navigate(`/play/review/${g.game_id}`)}
                                style={{ padding: '3px 9px', fontSize: '0.72rem' }}>
                          Review
                        </button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Each note is its own item: "Untimed: no clock", "All aimed at the Center Game". */}
      {notes.length > 0 && (
        <ul style={{ ...sectionLabel, listStyle: 'none', padding: 0, display: 'flex', flexWrap: 'wrap', gap: '2px 14px' }}>
          {notes.map(n => <li key={n}>{n}</li>)}
        </ul>
      )}

      {report?.claimable && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'baseline', flexWrap: 'wrap' }}>
            <span className="micro-label">Patterns</span>
            {patterns.length > 0 && (
              <button type="button" data-disclose aria-expanded={why} onClick={() => setWhy(!why)} className="link">
                {why ? 'Hide details' : 'Details'}
              </button>
            )}
          </div>
          {patterns.length === 0
            ? <p style={muted}>Nothing recurred in 3 or more of these games.</p>
            : <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 14 }}>
                {patterns.map(p => <PatternRow key={p.id} p={p} why={why} />)}
              </ul>}
        </div>
      )}
    </section>
  )
}

/** One tendency: what, how often, what to do now; why one tap away for the section. */
function PatternRow({ p, why }: { p: PracticePattern; why: boolean }) {
  const navigate = useNavigate()
  const [games, setGames] = useState(false)
  // Positions with a move to find: the pattern's own mistakes (I7), never a random set.
  const positions = p.drill_motif ? p.evidence.filter(o => o.ply != null) : []
  return (
    <li aria-label={p.title} style={{ display: 'flex', flexDirection: 'column', gap: 6, borderTop: '1px solid var(--hairline)', paddingTop: 12 }}>
      {/* Item title, then its count and strength as metadata. */}
      <div style={{ display: 'flex', gap: 10, alignItems: 'baseline', flexWrap: 'wrap' }}>
        <h4 style={{ fontSize: '0.9rem', fontWeight: 600, margin: 0, color: 'var(--text-primary)' }}>{p.title}</h4>
        <span className="mono" style={{ color: 'var(--text-secondary)', fontSize: '0.75rem' }}>{p.games_affected} of {p.games_considered} games</span>
        {p.strength === 'EMERGING' && <span className="micro-label">Early signal</span>}
      </div>
      <p data-reading={p.do_now ? 'item' : undefined} style={{ ...muted, margin: 0 }}>{p.do_now ?? p.what_to_do}</p>
      {why && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <p style={{ ...muted, color: 'var(--text-tertiary)', margin: 0 }}>{p.finding}</p>
          <p style={{ ...muted, color: 'var(--text-tertiary)', margin: 0 }}>{p.why} {p.do_now ? p.what_to_do : ''}</p>
        </div>
      )}
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'baseline' }}>
        {positions.length > 0 && (
          <Link to={drillLink(positions.map(o => ({ game_id: o.game_id, ply: o.ply! })))} style={rowAction}>
            Drill {positions.length === 1 ? 'this position' : `these ${positions.length} positions`}
          </Link>
        )}
        {p.evidence.length > 0 && (
          <button type="button" data-disclose aria-expanded={games} onClick={() => setGames(!games)}
                  className="link" style={rowAction}>
            {games ? 'Hide games' : `Show ${p.evidence.length === 1 ? 'the game' : `the ${p.evidence.length} games`}`}
          </button>
        )}
      </div>
      {games && (
        // The games behind the count: date, what happened, and the way into the review.
        <ul style={evidenceList}>
          {p.evidence.map((o, i) => {
            const { what, date } = splitLabel(o.label)
            return (
              <li key={`${o.game_id}-${i}`} style={subgridRow}>
                <button type="button" aria-label={o.label} style={evidenceRow}
                        onClick={() => navigate(o.ply === null ? `/play/review/${o.game_id}` : `/play/review/${o.game_id}?ply=${o.ply}`)}>
                  <span className="mono" style={{ color: 'var(--text-tertiary)', fontSize: '0.72rem' }}>{date}</span>
                  <span style={{ color: 'var(--text-secondary)' }}>{what}</span>
                  <span className="link" style={{ fontWeight: 500 }}>Review →</span>
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </li>
  )
}

/** A row's own actions: small neutral links under the item's text. */
const rowAction: React.CSSProperties = { fontSize: '0.75rem', fontWeight: 500 }

// One grid for the whole list, so dates, labels and Review line up across rows.
const evidenceList: React.CSSProperties = {
  listStyle: 'none', margin: 0, padding: '2px 0 2px 12px', borderLeft: '1px solid var(--hairline)',
  display: 'grid', gridTemplateColumns: '3.6rem minmax(0, max-content) max-content',
  justifyContent: 'start', columnGap: 12, rowGap: 2,
}

const subgridRow: React.CSSProperties = { display: 'grid', gridColumn: '1 / -1', gridTemplateColumns: 'subgrid' }

const evidenceRow: React.CSSProperties = {
  all: 'unset', cursor: 'pointer', ...subgridRow, columnGap: 12,
  alignItems: 'baseline', fontSize: '0.78rem', padding: '3px 0',
}

/** The server writes "Never castled (23 Sep)": the date becomes its own column. */
function splitLabel(label: string): { what: string; date: string } {
  const m = /^(.*) \((\d{1,2} [A-Z][a-z]{2})\)$/.exec(label)
  return m ? { what: m[1], date: m[2] } : { what: label, date: '' }
}
