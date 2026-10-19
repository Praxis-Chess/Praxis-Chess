import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ReportCount, ReportFacts, ReportRate, ReportWeakness } from '../api/types'
import { usePatternReport } from '../hooks/usePatternReport'
import { LoadingSpinner } from '../components/LoadingSpinner'
import {
  Bars, Disclose, Positions, dayMonth, drillLink, itemTitle, linkButton, linkStyle, marker, muted, rate1,
  sectionLabel, sectionTitle, statLine,
} from '../components/report/ReportParts'

/**
 * Insights → Pattern Report (Phase 12).
 *
 * Principles, in order: say what the data measures (cost is winning chances
 * lost, a rate is per 100 moves); show what a figure is compared with (your own
 * overall rate, your own average per game); account for the whole (every cause
 * and every tactic, not only the top ones); one threshold for "too few"; one
 * format per fact; pink only for what can be clicked; show, don't tell: no
 * sentence repeats a number already on screen, method sits behind one
 * disclosure per section, and every caveat stays visible.
 */
export function PatternReport() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { data: pattern, isLoading } = usePatternReport()
  const { data: plan } = useQuery({ queryKey: ['training-plan'], queryFn: api.trainingPlan.latest })
  const [fullDrills, setFullDrills] = useState(false)
  const rebuild = useMutation({
    mutationFn: api.patterns.recompute,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['patterns'] }),
  })
  const makePlan = useMutation({
    mutationFn: api.trainingPlan.generate,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['training-plan'] }); navigate('/insights/training') },
  })

  if (isLoading) return <LoadingSpinner label="Loading the report…" />

  const rebuildButton = (
    <button className="secondary" onClick={() => rebuild.mutate()} disabled={rebuild.isPending}
            style={{ fontSize: '0.78rem', padding: '4px 10px' }}>
      {rebuild.isPending ? 'Rebuilding…' : 'Rebuild'}
    </button>
  )

  // Reports made before this layout have another shape: offer a rebuild.
  if (!pattern?.facts || pattern.facts.min_sample == null) {
    return (
      <section style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <h3 style={{ ...sectionTitle, borderTop: 'none', paddingTop: 0 }}>Pattern Report</h3>
        <p style={muted}>
          {pattern ? 'This report was made by an older version. Rebuild it to see the current one.'
                   : 'No report yet. It is built after each analysis run, or now.'}
        </p>
        <div>{rebuildButton}</div>
        {rebuild.isError && <p role="alert" style={{ ...muted, color: 'var(--red)' }}>Could not rebuild: {(rebuild.error as Error).message}</p>}
      </section>
    )
  }

  const f = pattern.facts
  const prose = pattern.prose_status === 'checked' ? pattern.prose?.summary : null
  const overall = f.player_moves > 0 ? Math.round((1000 * f.serious) / f.player_moves) / 10 : 0
  const short = (w: ReportWeakness) => pattern.drill_short?.[w.key] ?? null
  const hasPlan = !!plan?.plan?.sessions?.every(s => typeof s.goal === 'string')
  const anyDrill = [...f.top_causes, ...f.top_tactics].length > 0
  const drillsToggle = anyDrill && (
    <button type="button" data-disclose aria-expanded={fullDrills} onClick={() => setFullDrills(!fullDrills)} style={linkButton}>
      {fullDrills ? 'Hide full drills' : 'Show full drills'}
    </button>
  )

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 26 }}>
      <Headline f={f} overall={overall}
                action={hasPlan
                  ? <button className="solid" onClick={() => navigate('/insights/training')}>Train this week →</button>
                  : <button className="solid" onClick={() => makePlan.mutate()} disabled={makePlan.isPending}>
                      {makePlan.isPending ? 'Planning…' : 'Make my plan'}
                    </button>} />
      {makePlan.isError && <p role="alert" style={{ ...muted, color: 'var(--red)', margin: 0 }}>Could not make a plan: {(makePlan.error as Error).message}</p>}
      {prose && (
        <p style={{ ...muted, fontStyle: 'italic' }}>
          {prose} <span style={{ fontStyle: 'normal' }}>(Written by AI; its numbers were checked, its wording was not.)</span>
        </p>
      )}

      <section aria-labelledby="causes-title" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <h3 id="causes-title" style={sectionTitle}>What goes wrong</h3>
          <div style={{ display: 'flex', gap: 12, alignItems: 'baseline', flexWrap: 'wrap' }}>
            <p data-reading="intro" style={sectionLabel}>Ranked by winning chances lost</p>
            {drillsToggle}
          </div>
        </div>
        {f.top_causes.length === 0
          ? <p style={muted}>No cause has {f.min_sample} serious mistakes behind it yet.</p>
          : <ol style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 18 }}>
              {f.top_causes.map((w, i) => (
                <WeaknessRow key={w.key} label={String(i + 1)} w={w} short={short(w)} full={fullDrills} />
              ))}
            </ol>}
        <Remainder rows={f.causes} shown={f.top_causes} extra={f.no_cause > 0 ? `no cause found ${f.no_cause}` : null} />
      </section>

      <section aria-labelledby="tactics-title" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <h3 id="tactics-title" style={sectionTitle}>Tactics your opponent got</h3>
          <p data-reading="intro" style={sectionLabel}>
            No proven tactic in <span className="mono">{f.no_named_tactic}</span> of <span className="mono">{f.diagnosed_serious}</span>
          </p>
          <Disclose>
            The tactic your opponent had after your move, or a piece of yours left undefended. A tactic is named only
            when the board proves it; the rest are counted, never guessed.
          </Disclose>
        </div>
        {f.top_tactics.length > 0 && (
          <ol style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 18 }}>
            {f.top_tactics.map(w => (
              <WeaknessRow key={w.key} label="–" w={w} short={short(w)} full={fullDrills} note={overlapNote(w, f.top_causes)} />
            ))}
          </ol>
        )}
        <Remainder rows={f.tactics} shown={f.top_tactics} extra={null} />
      </section>

      <section id="where-they-happen" aria-labelledby="where-title" style={{ display: 'flex', flexDirection: 'column', gap: 14, scrollMarginTop: 72 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <h3 id="where-title" style={sectionTitle}>Where they happen</h3>
          <p data-reading="intro" style={sectionLabel}>
            Per 100 of your moves · line = your rate, <span className="mono">{rate1(overall)}</span>
          </p>
          <Disclose>
            Serious mistakes for every 100 moves you played in that part of the game. A brighter bar is above your
            overall rate. A figure with fewer than {f.min_sample} mistakes behind it is faded, marked few, and never
            called the worst.
          </Disclose>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 360px), 1fr))', gap: 22 }}>
          <RateBars title="By phase" rows={f.by_phase} baseline={overall} />
          <RateBars title="By move number" rows={f.by_move_range} baseline={overall} />
        </div>
        {!f.time_pressure.enough && (
          <p style={sectionLabel}>
            Time trouble: clock data on <span className="mono">{f.time_pressure.with_clock}</span> of{' '}
            <span className="mono">{f.serious}</span>, fewer than {f.min_sample}
          </p>
        )}
      </section>

      <section aria-labelledby="openings-title" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <h3 id="openings-title" style={sectionTitle}>Openings above your average</h3>
        {f.openings.length === 0 ? (
          <p style={muted}>
            None: no opening with 3 or more games and {f.min_sample} or more serious mistakes is a fifth above your
            average of <span className="mono">{rate1(f.per_game)}</span> per game.
          </p>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem', fontVariantNumeric: 'tabular-nums' }}>
              <caption style={{ ...muted, textAlign: 'left', paddingBottom: 6 }}>
                Your average: <span className="mono">{rate1(f.per_game)}</span> per game
              </caption>
              <thead>
                <tr style={{ color: 'var(--text-secondary)', textAlign: 'left' }}>
                  <th scope="col" style={{ padding: '4px 8px', fontWeight: 500 }}>Opening</th>
                  <th scope="col" style={{ padding: '4px 8px', fontWeight: 500, textAlign: 'right' }}>Games</th>
                  <th scope="col" style={{ padding: '4px 8px', fontWeight: 500, textAlign: 'right' }}>Per game</th>
                  <th scope="col" style={{ padding: '4px 8px', fontWeight: 500, textAlign: 'right' }}>vs average</th>
                </tr>
              </thead>
              <tbody>
                {f.openings.map(o => (
                  <tr key={o.name} style={{ borderTop: '1px solid var(--hairline)' }}>
                    <td style={{ padding: '4px 8px' }}>{o.name}</td>
                    <td className="mono" style={{ padding: '4px 8px', textAlign: 'right' }}>{o.games}</td>
                    <td className="mono" style={{ padding: '4px 8px', textAlign: 'right' }}>{rate1(o.serious_per_game)}</td>
                    <td className="mono" style={{ padding: '4px 8px', textAlign: 'right' }}>×{o.vs_average.toFixed(2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <p style={{ ...muted, display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', borderTop: '1px solid var(--hairline)', paddingTop: 14 }}>
        <span>
          <span className="mono">{f.games_analysed}</span> analyzed games · {dayMonth(new Date(pattern.computed_at))}
          {f.diagnosed_serious < f.serious && <> · {f.serious - f.diagnosed_serious} serious mistakes not diagnosed yet</>}
        </span>
        {rebuildButton}
      </p>
    </div>
  )
}

/** The one thing to take away; where mistakes cluster, as a label; and the next step. */
function Headline({ f, overall, action }: { f: ReportFacts; overall: number; action: React.ReactNode }) {
  const top = f.top_causes[0]
  const worst = f.by_phase.find(r => r.key === f.worst_phase)
  return (
    <section aria-label="Summary" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <p data-reading="takeaway" style={{ fontSize: '1.1rem', lineHeight: 1.5, color: 'var(--text)', margin: 0 }}>
        {top
          ? <>Your costliest habit: <strong>{top.title.toLowerCase()}</strong>.</>
          : <>No cause has {f.min_sample} serious mistakes behind it yet.</>}
      </p>
      {worst && f.player_moves > 0 && (
        <p style={sectionLabel}>
          <span className="micro-label" style={{ marginRight: 4 }}>Most often</span>{' '}
          <a href="#where-they-happen" style={{ ...linkStyle, fontWeight: 600 }}>{worst.label}</a>
          {' · '}<span className="mono">{rate1(worst.per_hundred)}</span> vs <span className="mono">{rate1(overall)}</span> per 100 moves
        </p>
      )}
      <div style={{ paddingTop: 4 }}>{action}</div>
    </section>
  )
}

function WeaknessRow({ w, label, note, short, full }: {
  w: ReportWeakness; label: string; note?: string | null; short: string | null; full: boolean
}) {
  return (
    <li aria-label={w.title} style={{ display: 'grid', gridTemplateColumns: '1.5rem minmax(0, 1fr)', gap: 8 }}>
      <span style={marker}>{label}</span>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
        <h4 style={itemTitle}>{w.title}</h4>
        <span style={{ ...muted, fontSize: '0.78rem' }}>{statLine(w)}</span>
        <p data-reading={short ? 'item' : undefined} style={{ ...muted, margin: 0, color: 'var(--text)' }}>{short ?? w.drill}</p>
        {full && short && <p style={{ ...muted, margin: 0 }}>{w.drill}</p>}
        {note && <p style={{ ...muted, margin: 0, fontSize: '0.75rem' }}>{note}</p>}
        <Positions positions={w.positions} />
        {w.positions.length > 0 && (
          <Link to={drillLink(w.positions)} style={{ ...linkStyle, fontSize: '0.78rem', width: 'fit-content' }}>
            Drill {w.positions.length === 1 ? 'this position' : `these ${w.positions.length}`}
          </Link>
        )}
      </div>
    </li>
  )
}

/** The rest of the list, so the section accounts for every mistake. */
function Remainder({ rows, shown, extra }: { rows: ReportCount[]; shown: ReportWeakness[]; extra: string | null }) {
  const rest = rows.filter(r => !shown.some(w => w.key === r.key)).map(r => `${r.label} ${r.count}`)
  if (extra) rest.push(extra)
  if (rest.length === 0) return null
  return <p style={muted}>The rest: {rest.join(', ')}.</p>
}

/** One mistake can have a cause and a tactic; say how much of a tactic is already above. */
function overlapNote(t: ReportWeakness, causes: ReportWeakness[]) {
  const ids = (w: ReportWeakness) => new Set(w.positions.map(p => `${p.game_id}:${p.ply}`))
  const mine = ids(t)
  const best = causes
    .map(c => ({ c, shared: [...ids(c)].filter(k => mine.has(k)).length }))
    .sort((a, b) => b.shared - a.shared)[0]
  if (!best || best.shared === 0) return null
  return `${best.shared} of ${t.count} also under: ${best.c.title}`
}

function RateBars({ title, rows, baseline }: { title: string; rows: ReportRate[]; baseline: number }) {
  return (
    <div>
      <div className="micro-label" style={{ marginBottom: 8 }}>{title}</div>
      <Bars baseline={baseline} rows={rows.filter(r => r.moves > 0).map(r => ({
        key: r.key, label: r.label, value: r.per_hundred, enough: r.enough,
        text: `${rate1(r.per_hundred)} · ${r.count} of ${r.moves}`,
      }))} />
    </div>
  )
}
