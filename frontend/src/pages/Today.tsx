import { useState, useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { TodayInsight } from '../api/types'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { weekOf, WEEKDAY_LABELS, type DayCell } from '../components/practiceDates'
import { PraxAnchor, praxThoughts, praxBus, useFocusIntent, praxInteract } from '../prax/PraxHost'

/**
 * This week, Mon–Sun.
 *
 * Sits inside the content column with a hard width cap. The previous version
 * used `marginLeft: 'auto'` in a full-width row, which threw the dots a
 * thousand pixels right into Prax's space where they read as unrelated debris.
 *
 * Five dot states, not three. A future day must not look like a missed one — on
 * a Monday that would render six failures for days that have not happened.
 */
function WeeklyStreak() {
  const { data } = useQuery({
    queryKey: ['practice-streak'],
    queryFn: () => api.practice.streak(),
    staleTime: 60_000,
  })

  if (!data || data.total_days_practiced === 0) return null

  const week = weekOf(data.today, new Set(data.practice_days))
  const { current_streak: current, longest_streak: longest } = data
  const ended = current === 0

  return (
    <div style={{ maxWidth: 540, marginBottom: 28 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 10 }}>
        <span style={{ fontSize: '0.72rem', letterSpacing: '0.04em', color: 'var(--text-muted)' }}>
          This week
        </span>
        <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
          {/* Ended is a statement and an open door, never a lament. */}
          {ended
            ? `Longest ${longest} days · Start again today`
            : `${current} day streak · ${data.practiced_today ? 'Recorded today' : 'Keep it going'}`}
        </span>
      </div>

      <div style={{ display: 'flex', gap: 4 }}>
        {week.map((d, i) => (
          <div key={d.date} style={{ flex: 1, textAlign: 'center' }} title={dayTitle(d)}>
            <div style={{
              fontSize: '0.62rem',
              color: d.isToday ? 'var(--orchid)' : 'var(--text-muted)',
              opacity: d.future ? 0.4 : 1,
              marginBottom: 5,
            }}>
              {WEEKDAY_LABELS[i]}
            </div>
            <div style={{ display: 'flex', justifyContent: 'center' }}>
              <span style={{
                width: d.isToday ? 10 : 8,
                height: d.isToday ? 10 : 8,
                borderRadius: '50%',
                background: d.practiced ? 'var(--orchid)' : 'transparent',
                // A future day is barely drawn; a missed one has a real edge.
                border: d.practiced ? 'none'
                      : d.future    ? '1px solid var(--hairline)'
                                    : '1px solid var(--text-tertiary, #625C6D)',
                opacity: d.future ? 0.35 : 1,
                boxShadow: d.isToday ? '0 0 0 3px var(--accent-wash, rgba(231,166,214,0.12))' : 'none',
              }} />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function dayTitle(d: DayCell): string {
  if (d.future) return `${d.date} — upcoming`
  return `${d.date} — ${d.practiced ? 'examined' : 'no activity'}`
}

function EvidenceRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16,
                  padding: '8px 0', borderBottom: '1px solid var(--hairline)' }}>
      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>{label}</span>
      <span style={{ fontSize: '0.8rem', color: 'var(--text)', textAlign: 'right' }}>{value}</span>
    </div>
  )
}

function InsightCard({ insight, onStartSession }: { insight: TodayInsight; onStartSession: () => void }) {
  const [evidenceOpen, setEvidenceOpen] = useState(false)

  return (
    <div className="card" style={{ padding: '28px 24px', maxWidth: 540 }}>
      <div style={{ fontSize: '0.72rem', fontWeight: 600, letterSpacing: '0.08em',
                    color: 'var(--orchid)', textTransform: 'uppercase', marginBottom: 12 }}>
        Today's focus
      </div>
      <h2 style={{ fontSize: '1.45rem', fontWeight: 700, lineHeight: 1.25, marginBottom: 16,
                   letterSpacing: '-0.02em' }}>
        {insight.title}
      </h2>
      <p style={{ fontSize: '0.88rem', color: 'var(--text-muted)', lineHeight: 1.65, marginBottom: 24 }}>
        {insight.action}
      </p>

      <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginBottom: 20 }}>
        <button onClick={onStartSession} style={{ padding: '10px 20px', fontWeight: 600, fontSize: '0.9rem' }}>
          Start session →
        </button>
        <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
          ~{insight.expected_minutes} min
        </span>
      </div>

      <button
        onClick={() => { setEvidenceOpen(o => !o); praxInteract('EVIDENCE_OPENED') }}
        className="secondary"
        style={{ fontSize: '0.75rem', padding: '4px 10px', color: 'var(--text-muted)' }}
      >
        {evidenceOpen ? 'Hide' : 'Show'} evidence ▾
      </button>

      {evidenceOpen && (
        <div style={{ marginTop: 14, borderTop: '1px solid var(--hairline)', paddingTop: 14 }}>
          <EvidenceRow label={insight.evidence.metric} value={insight.evidence.value} />
          <EvidenceRow label="Sample size" value={`${insight.evidence.sample_size} games`} />
        </div>
      )}
    </div>
  )
}

function DueCount() {
  const { data: progress } = useQuery({
    queryKey: ['progress'],
    queryFn: () => api.progress.get(),
    staleTime: 60_000,
  })
  if (!progress) return null
  const { deck_summary: d } = progress
  const due = d.new_cards + d.learning_cards + d.review_cards
  return (
    <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', marginBottom: 32 }}>
      {[
        { label: 'Due today', value: due, color: 'var(--orchid)' },
        { label: 'Learning', value: d.learning_cards, color: 'var(--yellow)' },
        { label: 'Review', value: d.review_cards, color: 'var(--gain)' },
        { label: 'New', value: d.new_cards, color: 'var(--text-muted)' },
      ].map(({ label, value, color }) => (
        <div key={label} className="card" style={{ padding: '16px 20px', minWidth: 100, textAlign: 'center' }}>
          <div style={{ fontSize: '1.6rem', fontWeight: 700, color }}>{value}</div>
          <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 2 }}>{label}</div>
        </div>
      ))}
    </div>
  )
}

export function Today() {
  const navigate = useNavigate()
  const focus = useFocusIntent('insight')

  const { data: insight, isLoading, isError } = useQuery({
    queryKey: ['today-insight'],
    queryFn: () => api.today.insight(),
    staleTime: 5 * 60_000,
  })

  // Register the prose, then announce the finding. Events carry ids, not text
  // (Contract §1) — the Thought layer resolves content by id.
  useEffect(() => {
    if (!insight) return
    const id = 'today-focus'
    praxThoughts.set(id, {
      text: insight.title,
      evidence: [
        { label: insight.evidence.metric, value: insight.evidence.value },
        { label: 'sample', value: `${insight.evidence.sample_size} games` },
      ],
      examineHref: '/insights',
    })
    praxBus.emit({ type: 'INSIGHT_FOUND', insightId: id, confidence: 0.9, importance: 'high' })
  }, [insight])

  async function startSession() {
    praxInteract('DRILL_STARTED')
    const session = await api.sessions.start()
    navigate(`/session/${session.id}`)
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
      <div style={{ marginBottom: 28 }}>
        <h1 style={{ fontSize: '1.35rem', fontWeight: 700, letterSpacing: '-0.02em', marginBottom: 4 }}>
          Today
        </h1>
        <p style={{ fontSize: '0.82rem', color: 'var(--text-muted)' }}>
          One clear focus. No overwhelm.
        </p>
      </div>

      <DueCount />
      <WeeklyStreak />

      {isLoading && (
        <div className="card" style={{ padding: '16px 24px', maxWidth: 540 }}>
          <LoadingSpinner size={56} label="Analysing your patterns…" />
        </div>
      )}

      {isError && (
        <div className="card" style={{ padding: '28px 24px', maxWidth: 540 }}>
          <h2 style={{ fontWeight: 700, marginBottom: 12 }}>Time to drill</h2>
          <p style={{ fontSize: '0.88rem', color: 'var(--text-muted)', marginBottom: 20 }}>
            Re-solve your worst mistakes. Start a session to begin.
          </p>
          <button onClick={startSession} style={{ padding: '10px 20px', fontWeight: 600 }}>
            Start session →
          </button>
        </div>
      )}

      {insight && (
        <div {...focus}>
          <InsightCard insight={insight} onStartSession={startSession} />
        </div>
      )}

      {/* Page decides placement; renderer decides motion — Contract §4. */}
      <PraxAnchor x={0.62} y={0.47} />
    </div>
  )
}
