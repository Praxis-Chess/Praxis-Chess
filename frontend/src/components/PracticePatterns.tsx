import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { PracticePattern } from '../api/types'

/** Cycled so adjacent cards are distinguishable, like PatternReport's weakness cards. */
const ACCENTS = ['var(--loss, #E2664A)', 'var(--orange)', 'var(--yellow)']

function PatternCard({ pattern, accent }: { pattern: PracticePattern; accent: string }) {
  const navigate = useNavigate()
  const [showEvidence, setShowEvidence] = useState(false)

  // EMERGING clears the recurrence floor but sits near it. Same data, softer
  // claim — one rule, driven by the field, so cards cannot drift apart in tone.
  const emerging = pattern.strength === 'EMERGING'

  return (
    <div className="card" style={{ padding: '16px 18px', borderLeft: `2px solid ${accent}` }}>
      <div style={{
        fontSize: '0.7rem', letterSpacing: '0.05em', textTransform: 'uppercase',
        color: 'var(--text-tertiary)', marginBottom: 6,
      }}>
        {emerging ? 'Early signal' : 'Pattern'}
        <span className="mono" style={{ marginLeft: 8, opacity: 0.7 }}>
          {pattern.games_affected}/{pattern.games_considered}
        </span>
      </div>

      <p style={{ margin: '0 0 8px', fontSize: '0.92rem', fontWeight: 600 }}>
        {emerging ? `${pattern.title} — on this evidence so far` : pattern.title}
      </p>

      <p style={{ margin: '0 0 12px', fontSize: '0.82rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
        {pattern.finding}
      </p>

      {/* why and whatToDo are never collapsed: they are the reason the card is
          worth reading. Hiding them turns this back into a diagnostic. */}
      <p style={{ margin: '0 0 6px', fontSize: '0.8rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
        <span style={{ color: 'var(--text-tertiary)' }}>Why it costs you: </span>
        {pattern.why}
      </p>
      <p style={{ margin: 0, fontSize: '0.8rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
        <span style={{ color: 'var(--gain)' }}>What to do: </span>
        {pattern.what_to_do}
      </p>

      <div style={{ display: 'flex', gap: 10, marginTop: 12, flexWrap: 'wrap' }}>
        {pattern.drill_motif && (
          <button
            onClick={() => navigate(`/drills?motif=${pattern.drill_motif}`)}
            style={{
              background: 'transparent', border: '1px solid var(--hairline-lit)',
              color: 'var(--accent)', borderRadius: 3,
              padding: '4px 10px', fontSize: '0.74rem', cursor: 'pointer',
            }}
          >
            Practise this
          </button>
        )}
        {pattern.evidence.length > 0 && (
          <button
            onClick={() => setShowEvidence(v => !v)}
            aria-expanded={showEvidence}
            style={{
              background: 'transparent', border: '1px solid var(--hairline)',
              color: 'var(--text-secondary)', borderRadius: 3,
              padding: '4px 10px', fontSize: '0.74rem', cursor: 'pointer',
            }}
          >
            {showEvidence ? 'Hide games' : `Show the ${pattern.evidence.length} games`}
          </button>
        )}
      </div>

      {showEvidence && (
        <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 2 }}>
          {pattern.evidence.map((o, i) => (
            // A link, not a line of text. A move number is only checkable by
            // someone who can rebuild the position in their head.
            <button
              key={`${o.game_id}-${i}`}
              onClick={() => navigate(
                o.ply === null
                  ? `/play/review/${o.game_id}`
                  : `/play/review/${o.game_id}?ply=${o.ply}`)}
              style={{
                background: 'transparent', border: 'none', padding: '3px 0',
                color: 'var(--text-tertiary)', fontSize: '0.74rem',
                textAlign: 'left', cursor: 'pointer', textDecoration: 'underline',
                textDecorationColor: 'var(--hairline)', textUnderlineOffset: 3,
              }}
            >
              {o.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

export function PracticePatterns() {
  const { data } = useQuery({
    queryKey: ['play-patterns'],
    queryFn: () => api.play.patterns(),
    staleTime: 60_000,
  })

  if (!data) return null
  // Nothing played yet is not a finding; say nothing rather than an empty card.
  if (data.games_considered === 0) return null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div>
        <h3 style={{ fontSize: '0.95rem', fontWeight: 700, margin: 0 }}>My playing patterns</h3>
        <p style={{ fontSize: '0.76rem', color: 'var(--text-muted)', margin: '4px 0 0' }}>
          Across your last {data.games_considered} analysed practice game
          {data.games_considered === 1 ? '' : 's'}.
        </p>
      </div>

      {/* Above the cards, not below: a caveat printed after the thing it
          qualifies is read once the reader has already drawn the conclusion. */}
      {data.opening_caveat && (
        <p style={{
          margin: 0, fontSize: '0.76rem', color: 'var(--text-tertiary)',
          borderLeft: '2px solid var(--hairline)', paddingLeft: 10, lineHeight: 1.5,
        }}>
          {data.opening_caveat}
        </p>
      )}

      {!data.claimable && data.caveat && (
        <div className="card" style={{ padding: '14px 16px' }}>
          <p style={{ margin: 0, fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
            {data.caveat}
          </p>
        </div>
      )}

      {data.patterns.map((p, i) => (
        <PatternCard key={p.id} pattern={p} accent={ACCENTS[i % ACCENTS.length]} />
      ))}

      {data.claimable && data.patterns.length === 0 && (
        <div className="card" style={{ padding: '14px 16px' }}>
          <p style={{ margin: 0, fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
            Nothing recurred often enough to call a tendency. That is a result, not
            an empty page — these games did not repeat the same mistake.
          </p>
        </div>
      )}

      {data.not_measured.length > 0 && (
        <p style={{ margin: 0, fontSize: '0.72rem', color: 'var(--text-tertiary)', lineHeight: 1.5 }}>
          Not measured — {data.not_measured.join(' ')}
        </p>
      )}
    </div>
  )
}
