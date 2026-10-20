import { Link, useNavigate } from 'react-router-dom'
import { TeachingEmpty } from '../components/TeachingEmpty'
import { StaticBoard } from '../components/StaticBoard'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { PlanPriority, PlanRecall, SincePlan } from '../api/types'
import { LoadingSpinner } from '../components/LoadingSpinner'
import {
  PopoverButton, Positions, dayMonth, drillLink, linkStyle, muted, rate1, sectionLabel, sectionTitle, statLine,
} from '../components/report/ReportParts'


/**
 * Insights → Training Plan (Phase 12). A plan with memory: this week's dates,
 * sessions of your own positions with what each trains, which are done, and
 * the next one to do. Decided on the server from the report's facts.
 *
 * Show, don't tell: why each focus was chosen is the plan's own stat line,
 * one tap away on its chip, not a second copy of the Pattern Report.
 */
export function TrainingPlan() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { data: plan, isLoading } = useQuery({ queryKey: ['training-plan'], queryFn: api.trainingPlan.latest })
  // F10 and F11: what happened since the plan. Read-only, and silent until there is enough.
  const planId = plan?.plan ? plan.id : null
  const { data: since } = useQuery({ queryKey: ['plan-since', planId], queryFn: () => api.trainingPlan.since(planId!), enabled: !!planId })
  const { data: recall } = useQuery({ queryKey: ['plan-recall', planId], queryFn: () => api.trainingPlan.recall(planId!), enabled: !!planId })
  const generate = useMutation({
    mutationFn: api.trainingPlan.generate,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['training-plan'] }),
  })

  if (isLoading) return <LoadingSpinner label="Loading the plan…" />

  const p = plan?.plan
  const current = !!p?.sessions && p.sessions.every(s => typeof s.goal === 'string')
  const makeButton = (
    <button className={current ? 'secondary' : 'solid'} onClick={() => generate.mutate()} disabled={generate.isPending}
            style={current ? { fontSize: '0.78rem', padding: '4px 10px' } : undefined}>
      {generate.isPending ? 'Planning…' : current ? 'Make a new plan' : 'Make my plan'}
    </button>
  )
  const failed = generate.isError && (
    <p role="alert" style={{ ...muted, color: 'var(--red)' }}>
      {/no analysed mistakes/i.test((generate.error as Error).message)
        ? 'There is nothing to plan from yet: analyse some games first.'
        : `Could not make a plan: ${(generate.error as Error).message}`}
    </p>
  )

  if (!plan || !p || !current) {
    return (
      // U7: an empty plan teaches what it will hold and what it is built from.
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <TeachingEmpty title="Training Plan" label="Training Plan"
                       promise="Weekly sessions of your own positions, aimed at your costliest mistakes."
                       note={plan ? 'This plan was made by an older version: make a new one' : 'No plan yet: made from your latest report'}
                       action={makeButton} />
        {failed}
      </div>
    )
  }

  const progress = plan.progress ?? {}
  const done = p.sessions.filter(s => progress[String(s.number)]).length
  const next = p.sessions.find(s => !progress[String(s.number)])
  const start = new Date(plan.generated_at)
  const end = new Date(start.getTime() + 6 * 86_400_000)
  // Every focus the sessions train, by name: the priorities in rank order, then the tactic.
  const foci = new Map<string, { x: PlanPriority; of: number }>()
  p.priorities.forEach(x => foci.set(x.focus, { x, of: p.priorities.length }))
  if (p.pattern) foci.set(p.pattern.focus, { x: p.pattern, of: 0 })
  const short = (x: PlanPriority) => plan.drill_short?.[x.key] ?? null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 26 }}>
      <section aria-label="This week" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <p data-reading="takeaway" style={{ fontSize: '1.1rem', lineHeight: 1.5, color: 'var(--text)', margin: 0 }}>
            This week, {dayMonth(start)} to {dayMonth(end)}: {p.sessions.length} sessions of about {p.minutes_per_session} minutes.
          </p>
          <p style={{ ...muted, margin: 0 }} aria-live="polite">
            {done === p.sessions.length ? `All ${done} done.` : `${done} of ${p.sessions.length} done.`}
            {next && ` Next: session ${next.number}.`}
          </p>
          {p.priorities.length > 0 && (
            <p style={sectionLabel}>
              <span className="micro-label" style={{ marginRight: 4 }}>Focus</span>{' '}
              {p.priorities.map(x => x.focus).join(', then ')}
              {p.pattern && <> · tactic: {p.pattern.focus}</>}
            </p>
          )}
        </div>
        {p.sessions.length === 0 && <p style={muted}>No positions to work on yet.</p>}
        <ol style={{ listStyle: 'none', padding: 0, margin: 0, display: 'grid',
                     gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 250px), 1fr))', gap: 12 }}>
          {p.sessions.map(s => {
            const result = progress[String(s.number)]
            const isNext = next?.number === s.number
            return (
              <li key={s.number} className="card plain" aria-label={`Session ${s.number}`}
                  style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: 16 }}>
                <div className="micro-label">Session {s.number} · {s.positions.length} positions</div>
                <h4 style={{ fontSize: '0.98rem', fontWeight: 600, margin: 0 }}>{s.goal}</h4>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {s.focus.map(name => {
                    const f = foci.get(name)
                    return f ? <FocusChip key={name} x={f.x} of={f.of} short={short(f.x)} since={since} />
                             : <span key={name} style={chipStyle}>{name}</span>
                  })}
                </div>
                {/* U4: the session is these positions; show them, not only their moves. */}
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  {s.positions.slice(0, 4).map(p => (
                    <StaticBoard key={`${p.game_id}-${p.ply}`} fen={p.fen} size={64} label={p.move} />
                  ))}
                </div>
                <Positions positions={s.positions} first={s.positions.length} />
                <RecallLine r={recall?.sessions.find(x => x.number === s.number)} since={recall?.since} />
                <div style={{ marginTop: 'auto', display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                  {result && (
                    <span style={{ fontSize: '0.78rem', color: 'var(--green)' }}>
                      ✓ Done {dayMonth(new Date(result.done_at))} · {result.solved} of {result.total} solved
                    </span>
                  )}
                  <button className={isNext ? 'solid' : 'secondary'}
                          onClick={() => navigate(drillLink(s.positions, s.number, plan.id))}>
                    {result ? 'Do it again' : `Start session ${s.number}`}
                  </button>
                </div>
              </li>
            )
          })}
        </ol>
      </section>

      {p.openings_to_review.length > 0 && (
        <section aria-labelledby="review-title" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <h3 id="review-title" style={sectionTitle}>Openings to review</h3>
          <ul style={{ margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 4 }}>
            {p.openings_to_review.map(o => (
              <li key={o.name} data-scope={o.name} style={{ fontSize: '0.82rem' }}>
                {o.name} · <span className="mono">{rate1(o.serious_per_game)}</span> vs your{' '}
                <span className="mono">{rate1(p.per_game)}</span> per game · <span className="mono">{o.games}</span> games
              </li>
            ))}
          </ul>
        </section>
      )}

      <div style={{ ...muted, display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', borderTop: '1px solid var(--hairline)', paddingTop: 14 }}>
        <span>Made {dayMonth(start)} · <span className="mono">{p.based_on_games}</span> analysed games</span>
        {makeButton}
        <span>Keeps this plan in your history</span>
      </div>
      {failed}
    </div>
  )
}

/** A chip as a label: the focus of a session. */
const chipStyle: React.CSSProperties = {
  fontSize: '0.7rem', padding: '2px 8px', borderRadius: 10,
  border: '1px solid var(--hairline-strong)', color: 'var(--text-secondary)',
}

/**
 * A focus chip that opens: neutral, with a caret that says it opens. Inside, what
 * the plan knew about this focus when it was made: rank, stat line, drill.
 */
function FocusChip({ x, of, short, since }: { x: PlanPriority; of: number; short: string | null; since?: SincePlan }) {
  return (
    <span data-scope={x.focus} style={{ display: 'inline-flex' }}>
      <PopoverButton title={x.focus} ariaLabel={`${x.focus}: why this session trains it`}
                     style={{ ...chipStyle, color: 'var(--text-primary)', fontWeight: 500, display: 'inline-flex', gap: 4 }}
                     label={<>{x.focus}<span aria-hidden="true" style={{ color: 'var(--text-tertiary)' }}>▾</span></>}>
        <span className="micro-label">{of > 0 ? `Priority ${x.rank} of ${of}` : 'Tactic your opponent got'}</span>
        <strong style={{ fontSize: '0.85rem' }}>{x.focus}</strong>
        <span style={{ ...muted, fontSize: '0.78rem' }}>{statLine(x)}</span>
        {short && <span style={{ fontSize: '0.8rem' }}>{short}</span>}
        <span style={{ ...muted, fontSize: '0.78rem' }}>{x.drill}</span>
        {since && <SinceLine since={since} focusKey={x.key} />}
        <Link to="/insights/patterns" style={{ ...linkStyle, fontSize: '0.78rem' }}>See in Pattern Report →</Link>
      </PopoverButton>
    </span>
  )
}

/**
 * F10: this focus in games since the plan, against before, per 100 of your
 * moves. Silent, and says why, until there has been enough play to compare.
 */
function SinceLine({ since, focusKey }: { since: SincePlan; focusKey: string }) {
  const c = since.foci.find(f => f.key === focusKey)
  if (!c) return null
  const day = dayMonth(new Date(`${since.since}T12:00:00`))
  return (
    <span data-scope="Since this plan" style={{ ...muted, fontSize: '0.78rem', borderTop: '1px solid var(--hairline)', paddingTop: 6 }}>
      <span className="micro-label" style={{ marginRight: 4 }}>Since {day}</span>{' '}
      {since.enough
        ? <><span className="mono">{rate1(c.after.per_hundred)}</span> per 100 moves, from{' '}
            <span className="mono">{rate1(c.before.per_hundred)}</span> · <span className="mono">{c.after.games}</span> games</>
        : <>too little play to compare: <span className="mono">{since.serious_after}</span> of {since.min_sample} serious mistakes</>}
    </span>
  )
}

/**
 * F11: how the reviews of this session's positions went since the plan, for
 * the positions that are also cards in your review deck.
 */
function RecallLine({ r, since }: { r?: PlanRecall['sessions'][number]; since?: string }) {
  if (!r || !since) return null
  const day = dayMonth(new Date(since))
  return (
    <span style={{ ...muted, fontSize: '0.75rem' }}>
      {r.tracked === 0
        ? 'Not in your review deck'
        : r.reviews === 0
          ? <>In your review deck: <span className="mono">{r.tracked}</span> of {r.positions} · no reviews since {day}</>
          : <>Reviews since {day}: <span className="mono">{r.correct}</span> of {r.reviews} correct
              · <span className="mono">{r.tracked}</span> of {r.positions} positions in your deck</>}
    </span>
  )
}
