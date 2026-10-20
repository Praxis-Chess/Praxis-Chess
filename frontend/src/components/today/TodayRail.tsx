import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Line, LineChart, ResponsiveContainer, YAxis } from 'recharts'
import { api } from '../../api/client'
import type { GameSummary, Pattern, TrainingPlan } from '../../api/types'
import { dayMonth, muted, rate1 } from '../report/ReportParts'

/**
 * Today's rail (POLISH_PLAN.md U3): the context around today's work, each a
 * small panel you can act on. Rating trend, last game, the next plan session,
 * the top habit and which way it is going since the plan. Every panel says
 * plainly when there is nothing yet, rather than drawing an empty frame.
 */
export function TodayRail() {
  const { data: pattern } = useQuery({ queryKey: ['patterns'], queryFn: api.patterns.latest, staleTime: 5 * 60_000 })
  const { data: plan } = useQuery({ queryKey: ['training-plan'], queryFn: api.trainingPlan.latest, staleTime: 5 * 60_000 })
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <RatingTrend />
      <LastGame />
      <NextSession plan={plan ?? null} />
      <TopHabit pattern={pattern ?? null} plan={plan ?? null} />
    </div>
  )
}

function Panel({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <section aria-label={label} className="card no-mark" style={{ padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 6 }}>
      <span className="micro-label">{label}</span>
      {children}
    </section>
  )
}

/** Rating over the stored history: where it is, how far it moved, and the line. */
function RatingTrend() {
  // The history comes with the dashboard stats: the server has no separate rating-history route.
  const { data } = useQuery({ queryKey: ['dashboard-stats'], queryFn: api.dashboard.stats, staleTime: 5 * 60_000 })
  const points = (data?.rating_history ?? []).filter(p => p.rating > 0)
  if (points.length < 2) {
    return <Panel label="Rating"><span style={muted}>Not enough rated games yet</span></Panel>
  }
  const first = points[0], last = points[points.length - 1]
  const change = last.rating - first.rating
  return (
    <Panel label="Rating">
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
        <span className="mono" style={{ fontSize: 'var(--fs-display)', fontWeight: 700 }}>{last.rating}</span>
        <span className="mono" style={{ ...muted, color: 'var(--text-secondary)' }}>
          {change > 0 ? '+' : change < 0 ? '−' : '±'}{Math.abs(change)} since {dayMonth(new Date(`${first.date.slice(0, 10)}T12:00:00`))}
        </span>
      </div>
      <div data-chart="rating" style={{ height: 56 }} aria-hidden="true">
        <ResponsiveContainer width="100%" height="100%">
          {/* Decorative beside the figures above it: not focusable, not announced. */}
          <LineChart data={points} margin={{ top: 4, right: 0, bottom: 0, left: 0 }} accessibilityLayer={false}>
            <YAxis hide domain={['dataMin - 10', 'dataMax + 10']} />
            <Line type="monotone" dataKey="rating" stroke="var(--text-primary)" strokeWidth={1.25} dot={false} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </Panel>
  )
}

/** The most recent game: what it was, how it went, and the way to review it. */
function LastGame() {
  const { data } = useQuery({ queryKey: ['games'], queryFn: api.games.list, staleTime: 60_000 })
  const g: GameSummary | undefined = [...(data ?? [])].sort((a, b) => b.played_at.localeCompare(a.played_at))[0]
  if (!g) return <Panel label="Last game"><span style={muted}>No games synced yet</span></Panel>
  const colour = g.result === 'win' ? 'var(--gain)' : g.result === 'loss' ? 'var(--loss)' : 'var(--text-secondary)'
  return (
    <Panel label="Last game">
      {/* The name is the way into the review, once the game is analysed. */}
      {g.analysis_status === 'ANALYZED'
        ? <Link to={`/games/${g.id}`} style={{ fontSize: '0.88rem', fontWeight: 600 }}>{g.opening_name ?? g.opening_eco ?? 'Unknown opening'}</Link>
        : <span style={{ fontSize: '0.88rem', fontWeight: 600 }}>{g.opening_name ?? g.opening_eco ?? 'Unknown opening'}</span>}
      <span style={{ ...muted, fontSize: '0.78rem' }}>
        <span style={{ color: colour, fontWeight: 600, textTransform: 'capitalize' }}>{g.result}</span>
        {' · '}{dayMonth(new Date(g.played_at))} · {g.time_class} · {g.player_color}
        {g.accuracy != null && <> · <span className="mono">{g.accuracy}%</span></>}
      </span>
    </Panel>
  )
}

/** The next Training Plan session not yet done. */
function NextSession({ plan }: { plan: TrainingPlan | null }) {
  const sessions = plan?.plan?.sessions ?? []
  if (sessions.length === 0) {
    return (
      <Panel label="Plan">
        <Link to="/insights/patterns" style={{ fontSize: '0.8rem', fontWeight: 500 }}>Make a plan →</Link>
      </Panel>
    )
  }
  const done = plan?.progress ?? {}
  const next = sessions.find(s => !done[String(s.number)])
  if (!next) {
    return (
      <Panel label="Training plan">
        <span style={{ fontSize: '0.88rem' }}>This week's {sessions.length} sessions are done</span>
        <Link to="/insights/training" style={{ fontSize: '0.8rem', fontWeight: 500 }}>See the plan →</Link>
      </Panel>
    )
  }
  return (
    <Panel label={`Next · session ${next.number} of ${sessions.length}`}>
      <span style={{ fontSize: '0.88rem', fontWeight: 600 }}>{next.goal}</span>
      <span style={{ ...muted, fontSize: '0.78rem' }}><span className="mono">{next.positions.length}</span> positions · {next.focus.join(', ')}</span>
      <Link to="/insights/training" style={{ fontSize: '0.8rem', fontWeight: 500 }}>Open the plan →</Link>
    </Panel>
  )
}

/** The costliest habit, and which way it has moved since the plan was made. */
function TopHabit({ pattern, plan }: { pattern: Pattern | null; plan: TrainingPlan | null }) {
  const top = pattern?.facts?.top_causes[0]
  const { data: since } = useQuery({
    queryKey: ['training-plan-since', plan?.id],
    queryFn: () => api.trainingPlan.since(plan!.id),
    enabled: !!plan?.id && !!top,
    staleTime: 5 * 60_000,
  })
  // No report yet: nothing to say, so no panel (the plan panel already points there).
  if (!top) return null
  const f = since?.foci.find(x => x.key === top.key)
  return (
    <Panel label="Top habit">
      <span style={{ fontSize: '0.88rem', fontWeight: 600 }}>{top.title}</span>
      <span style={{ ...muted, fontSize: '0.78rem' }}>
        {!since || !f
          ? <>Ranked first by winning chances lost</>
          : since.enough
            // Lower is better: fewer of these mistakes per 100 moves.
            ? <><span className="mono">{rate1(f.after.per_hundred)}</span> per 100 moves since {dayMonth(new Date(`${since.since}T12:00:00`))},{' '}
                {f.after.per_hundred < f.before.per_hundred ? 'down' : f.after.per_hundred > f.before.per_hundred ? 'up' : 'level'} from{' '}
                <span className="mono">{rate1(f.before.per_hundred)}</span></>
            : <>Too little play since the plan to compare: <span className="mono">{since.serious_after}</span> of {since.min_sample}</>}
      </span>
      <Link to="/insights/patterns" style={{ fontSize: '0.8rem', fontWeight: 500 }}>See the Pattern Report →</Link>
    </Panel>
  )
}
