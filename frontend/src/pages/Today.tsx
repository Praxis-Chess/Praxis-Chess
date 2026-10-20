import { useState, useEffect } from 'react'
import { FirstRunSample } from '../sample/FirstRunSample'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { TodayInsight } from '../api/types'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { SetupChecklist } from '../components/SetupChecklist'
import { weekOf, WEEKDAY_LABELS, type DayCell } from '../components/practiceDates'
import { praxThoughts, praxBus, useFocusIntent, praxInteract } from '../prax/PraxHost'
import { MiniBoard, shortDate } from '../components/report/ReportParts'
import { Page } from '../components/Page'
import { TodayRail } from '../components/today/TodayRail'
import { usePatternReport } from '../hooks/usePatternReport'

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
    <div style={{ marginBottom: 28 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 10 }}>
        <span className="mono" style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
          {shortDate(week[0].date)} – {shortDate(week[week.length - 1].date)}
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
          <div key={d.date} style={{ flex: 1, textAlign: 'center' }} title={dayTitle(d)} role="img" aria-label={dayTitle(d)}>
            <div style={{
              fontSize: '0.62rem',
              color: d.isToday ? 'var(--text-primary)' : 'var(--text-muted)',
              fontWeight: d.isToday ? 700 : 400,
              opacity: d.future ? 0.6 : 1,
              marginBottom: 5,
            }}>
              {WEEKDAY_LABELS[i]}
            </div>
            <div style={{ display: 'flex', justifyContent: 'center' }}>
              <span style={{
                width: d.isToday ? 10 : 8,
                height: d.isToday ? 10 : 8,
                borderRadius: '50%',
                background: d.practiced ? 'var(--gain)' : 'transparent',
                // N17: a future day is a dashed ring, plainly there but not yet a day;
                // a missed one has a solid edge; a practised one is filled.
                border: d.practiced ? 'none'
                      : d.future    ? '1px dashed var(--text-tertiary)'
                                    : '1px solid var(--text-tertiary, #625C6D)',
                boxShadow: d.isToday ? '0 0 0 3px var(--surface-2)' : 'none',
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
  // One of your own positions behind the focus: the costliest of the report's top habit (U3).
  const { data: pattern } = usePatternReport()
  const top = pattern?.facts?.top_causes[0]
  const position = top?.positions[0]

  return (
    <div className="card" style={{ padding: '28px 24px', display: 'grid', gap: 24,
                                    gridTemplateColumns: position ? 'minmax(0, 1fr) auto' : 'minmax(0, 1fr)', alignItems: 'start' }}>
    <div>
      <div style={{ fontSize: '0.72rem', fontWeight: 600, letterSpacing: '0.08em',
                    color: 'var(--text-secondary)', textTransform: 'uppercase', marginBottom: 12 }}>
        Today's focus
      </div>
      <h2 data-reading="takeaway" style={{ fontSize: '1.45rem', fontWeight: 700, lineHeight: 1.25, marginBottom: 16,
                   letterSpacing: '-0.02em' }}>
        {insight.title}
      </h2>
      <p style={{ fontSize: '0.88rem', color: 'var(--text-muted)', lineHeight: 1.65, marginBottom: 24 }}>
        {insight.action}
      </p>

      <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginBottom: 20 }}>
        <button className="solid" onClick={onStartSession} style={{ padding: '10px 20px', fontWeight: 600, fontSize: '0.9rem' }}>
          Start session →
        </button>
        <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
          ~{insight.expected_minutes} min
        </span>
      </div>

      <button
        data-disclose aria-expanded={evidenceOpen} aria-controls="today-evidence"
        onClick={() => { setEvidenceOpen(o => !o); praxInteract('EVIDENCE_OPENED') }}
        className="secondary"
        style={{ fontSize: '0.75rem', padding: '4px 10px', color: 'var(--text-muted)' }}
      >
        {evidenceOpen ? 'Hide' : 'Show'} evidence ▾
      </button>

      {evidenceOpen && (
        <div id="today-evidence" style={{ marginTop: 14, borderTop: '1px solid var(--hairline)', paddingTop: 14 }}>
          <EvidenceRow label={insight.evidence.metric} value={insight.evidence.value} />
          <EvidenceRow label="Sample size" value={`${insight.evidence.sample_size} games`} />
          {/* Where the claim is measured against your own rate (F3): the report's phase bars. */}
          <Link to="/insights/patterns#where-they-happen" style={{ display: 'inline-block', marginTop: 10, fontSize: '0.8rem' }}>
            Where your mistakes happen →
          </Link>
        </div>
      )}
    </div>
      {position && top && (
        <figure aria-label="One of your positions" style={{ margin: 0, display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'center' }}>
          <MiniBoard fen={position.fen} />
          <figcaption style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', textAlign: 'center', maxWidth: 168 }}>
            <span className="mono" style={{ color: 'var(--text-primary)' }}>{position.move}</span>{' '}
            <span className="mono" style={{ color: 'var(--loss-soft)' }}>−{Math.round(position.lost)}%</span>
            <br />{top.title}
            <br /><Link to={`/games/${position.game_id}?ply=${position.ply}`}>Open the game</Link>
          </figcaption>
        </figure>
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
  // The server counts what is due today (reviews due, plus today's new cards, at most 10 a day).
  // An older server sent only totals: fall back to the old sum.
  const due = d.due_today ?? d.new_cards + d.learning_cards + d.review_cards
  return (
    <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', marginBottom: 32 }}>
      {[
        { label: 'Due today', value: due, color: 'var(--text-primary)' },
        // Neutral: amber means mistake severity, green a result (principle 2, N16).
        { label: 'Learning', value: d.learning_cards, color: 'var(--text-primary)' },
        { label: 'Review', value: d.review_cards, color: 'var(--text-primary)' },
        { label: 'New', value: d.new_cards, color: 'var(--text-primary)' },
      ].map(({ label, value, color }) => (
        <div key={label} className="card" style={{ padding: '16px 20px', minWidth: 100, flex: 1, textAlign: 'center' }}>
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

  // A session that cannot start says why (in sample mode: the sample is read-only), never nothing.
  const [startError, setStartError] = useState<string | null>(null)
  async function startSession() {
    praxInteract('DRILL_STARTED')
    setStartError(null)
    try {
      const session = await api.sessions.start()
      navigate(`/session/${session.id}`)
    } catch (e) {
      const text = e instanceof Error ? e.message : String(e)
      const said = /"error"\s*:\s*"([^"]+)"/.exec(text)?.[1]
      setStartError(said ?? text)
    }
  }

  return (
    // The cockpit (U3): today's work in the main column, its context in the rail.
    <Page width="standard" rail={<TodayRail />}>
    <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
      {/* The nav names this page; the heading stays for screen readers and tests, not the eye. */}
      <h1 className="sr-only">Today</h1>

      <SetupChecklist mode="attention" />
      {/* U7: before the first games are in, the sample library is one click away. */}
      <FirstRunSample />

      {isLoading && (
        <div className="card" style={{ padding: '16px 24px' }}>
          <LoadingSpinner size={56} label="Analysing your patterns…" />
        </div>
      )}

      {isError && (
        <div className="card" style={{ padding: '28px 24px' }}>
          <h2 style={{ fontWeight: 700, marginBottom: 12 }}>Time to drill</h2>
          <p style={{ fontSize: '0.88rem', color: 'var(--text-muted)', marginBottom: 20 }}>
            Re-solve your worst mistakes. Start a session to begin.
          </p>
          <button className="solid" onClick={startSession} style={{ padding: '10px 20px', fontWeight: 600 }}>
            Start session →
          </button>
        </div>
      )}

      {insight && (
        <div {...focus}>
          <InsightCard insight={insight} onStartSession={startSession} />
        </div>
      )}
      {startError && (
        <p role="alert" style={{ margin: '10px 0 0', fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
          Couldn't start a session: {startError}
        </p>
      )}

      {/* The day's work first; then the deck and the week. */}
      <div style={{ marginTop: 24 }}>
        <DueCount />
        <WeeklyStreak />
      </div>
    </div>
    </Page>
  )
}
