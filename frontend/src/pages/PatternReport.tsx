import { useId, useState } from 'react'
import { TeachingEmpty } from '../components/TeachingEmpty'
import { StaticBoard } from '../components/StaticBoard'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ReportCount, ReportFacts, ReportOpening, ReportRate, ReportWeakness } from '../api/types'
import { usePatternReport } from '../hooks/usePatternReport'
import { LoadingSpinner } from '../components/LoadingSpinner'
import {
  Bars, Disclose, Positions, dayMonth, drillLink, itemTitle, linkStyle, marker, muted, rate1,
  sectionLabel, sectionTitle, statLine,
} from '../components/report/ReportParts'

/**
 * Insights → Pattern Report (Phase 12).
 *
 * Principles, in order: say what the data measures (cost is winning chances
 * lost, a rate is per 100 moves); show what a figure is compared with (your own
 * overall rate, your own average per game); account for the whole (every cause
 * and every tactic, not only the top ones); one threshold for "too few"; one
 * format per fact; pink only for the next action and the active state; show,
 * don't tell: no sentence repeats a number already on screen, method sits
 * behind one disclosure per section, and every caveat stays visible.
 *
 * Hierarchy: an item has three levels (title with its one headline number, the
 * instruction, then three positions and one action); everything else is in
 * its Details. Causes and tactics are two views of the same mistakes, so they
 * are tabs of one section, not two lists. Cards are for what you act on (each
 * item leads to a drill); what you read stays open.
 */
export function PatternReport() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { data: pattern, isLoading } = usePatternReport()
  const { data: plan } = useQuery({ queryKey: ['training-plan'], queryFn: api.trainingPlan.latest })
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
      // U7: an empty report teaches what it will show and what it is built from.
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <TeachingEmpty title="Pattern Report" label="Pattern Report"
                       promise="Your costliest habits, ranked by winning chances lost, with their positions."
                       note={pattern ? 'This report was made by an older version: rebuild it' : 'No report yet: built after each analysis run, or now'}
                       action={rebuildButton} />
        {rebuild.isError && <p role="alert" style={{ ...muted, color: 'var(--red)' }}>Could not rebuild: {(rebuild.error as Error).message}</p>}
      </div>
    )
  }

  const f = pattern.facts
  const prose = pattern.prose_status === 'checked' ? pattern.prose?.summary : null
  const overall = f.player_moves > 0 ? Math.round((1000 * f.serious) / f.player_moves) / 10 : 0
  const short = (w: ReportWeakness) => pattern.drill_short?.[w.key] ?? null
  const hasPlan = !!plan?.plan?.sessions?.every(s => typeof s.goal === 'string')

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 40 }}>
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

      <WhatGoesWrong f={f} short={short} />

      <section id="where-they-happen" aria-labelledby="where-title" style={{ display: 'flex', flexDirection: 'column', gap: 16, scrollMarginTop: 72 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <h3 id="where-title" style={sectionTitle}>Where they happen</h3>
          <WhereLead f={f} />
          <p data-reading="intro" style={sectionLabel}>
            Per 100 of your moves · line = your rate, <span className="mono">{rate1(overall)}</span>
          </p>
          <Disclose>
            Serious mistakes for every 100 moves you played in that part of the game. The bright bar is the one named
            above; the line is your overall rate. A figure with fewer than {f.min_sample} mistakes behind it is faded,
            marked few, and never called the worst.
          </Disclose>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', columnGap: 56, rowGap: 22 }}>
          <RateBars title="By phase" rows={f.by_phase} baseline={overall} highlight={f.worst_phase} />
          <RateBars title="By move number" rows={f.by_move_range} baseline={overall} highlight={f.worst_range} />
        </div>
        {!f.time_pressure.enough && (
          <p style={sectionLabel}>
            Time trouble: clock data on <span className="mono">{f.time_pressure.with_clock}</span> of{' '}
            <span className="mono">{f.serious}</span>, fewer than {f.min_sample}
          </p>
        )}
      </section>

      <Openings f={f} />

      <p style={{ ...muted, display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', borderTop: '1px solid var(--hairline)', paddingTop: 14 }}>
        <span>
          <span className="mono">{f.games_analysed}</span> analysed games · {dayMonth(new Date(pattern.computed_at))}
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
          <a href="#where-they-happen" style={linkStyle}>{worst.label}</a>
          {' · '}<span className="mono">{rate1(worst.per_hundred)}</span> vs <span className="mono">{rate1(overall)}</span> per 100 moves
        </p>
      )}
      <div style={{ paddingTop: 4 }}>{action}</div>
    </section>
  )
}

type View = 'causes' | 'tactics'

/**
 * Causes and tactics count the same mistakes on two axes (one mistake can have
 * both), so they are one section with two views. The cause view comes first:
 * a cause is something the player can change; a tactic is what it cost.
 */
function WhatGoesWrong({ f, short }: { f: ReportFacts; short: (w: ReportWeakness) => string | null }) {
  const [view, setView] = useState<View>('causes')
  const id = useId()
  const proven = f.diagnosed_serious - f.no_named_tactic
  const pct = (n: number) => (f.diagnosed_serious > 0 ? Math.round((100 * n) / f.diagnosed_serious) : 0)
  const tabs: { key: View; label: string }[] = [
    { key: 'causes', label: 'By cause' },
    { key: 'tactics', label: 'By tactic your opponent got' },
  ]
  return (
    <section aria-labelledby="causes-title" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <h3 id="causes-title" style={sectionTitle}>What goes wrong</h3>
        <p data-reading="intro" style={sectionLabel}>Ranked by winning chances lost</p>
      </div>
      <div role="tablist" aria-label="Group mistakes" style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
        {tabs.map(t => (
          <button key={t.key} type="button" role="tab" id={`${id}-${t.key}-tab`} aria-controls={`${id}-${t.key}`}
                  aria-selected={view === t.key} onClick={() => setView(t.key)} style={tabStyle(view === t.key)}>
            {t.label}
          </button>
        ))}
      </div>

      <div role="tabpanel" id={`${id}-causes`} aria-labelledby={`${id}-causes-tab`}
           hidden={view !== 'causes'} style={view === 'causes' ? panel : undefined}>
        {f.no_cause > 0 && (
          // The biggest bucket is often "no cause": said plainly, not left in a footnote.
          <p style={sectionLabel}>
            No cause found for <span className="mono">{f.no_cause}</span> of <span className="mono">{f.diagnosed_serious}</span>{' '}
            (<span className="mono">{pct(f.no_cause)}%</span>)
          </p>
        )}
        {f.top_causes.length === 0
          ? <p style={muted}>No cause has {f.min_sample} serious mistakes behind it yet.</p>
          : <ol style={list}>
              {f.top_causes.map((w, i) => <WeaknessItem key={w.key} rank={i + 1} w={w} short={short(w)} />)}
            </ol>}
        <Remainder rows={f.causes} shown={f.top_causes} />
      </div>

      <div role="tabpanel" id={`${id}-tactics`} aria-labelledby={`${id}-tactics-tab`}
           hidden={view !== 'tactics'} style={view === 'tactics' ? panel : undefined}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <p style={sectionLabel}>
            A tactic proven in <span className="mono">{proven}</span> of <span className="mono">{f.diagnosed_serious}</span>
          </p>
          <Disclose>
            The tactic your opponent had after your move, or a piece of yours left undefended. A tactic is named only
            when the board proves it; the rest are counted, never guessed. One mistake can have a cause and a tactic,
            so these are the same mistakes as the causes, grouped another way.
          </Disclose>
        </div>
        {f.top_tactics.length > 0 && (
          <ol style={list}>
            {f.top_tactics.map(w => (
              <WeaknessItem key={w.key} w={w} short={short(w)} note={overlapNote(w, f.top_causes)} />
            ))}
          </ol>
        )}
        <Remainder rows={f.tactics} shown={f.top_tactics} />
      </div>
    </section>
  )
}

/**
 * Three levels: the title with one headline number (what each costs), the
 * instruction, then three positions and the drill. The share, blunders,
 * overlap, full drill and every other position are in Details.
 */
function WeaknessItem({ w, rank, note, short }: {
  w: ReportWeakness; rank?: number; note?: string | null; short: string | null
}) {
  const [open, setOpen] = useState(false)
  const n = w.positions.length
  const extra = short && short !== w.drill ? w.drill : null
  return (
    <li aria-label={w.title} style={{ ...card, display: 'grid', gridTemplateColumns: w.positions[0] ? 'minmax(0, 1fr) auto' : 'minmax(0, 1fr)', columnGap: 18, alignItems: 'start' }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
      <div style={{ display: 'flex', gap: 10, alignItems: 'baseline' }}>
        {rank != null && <span style={{ ...marker, fontSize: '0.8rem' }}>{rank}</span>}
        <h4 style={{ ...itemTitle, flex: 1, minWidth: 0 }}>{w.title}</h4>
        <span className="mono" style={{ fontSize: '0.8rem', color: 'var(--loss-soft)', whiteSpace: 'nowrap' }}
              aria-label={`−${Math.round(w.avg_lost)}% winning chance each`}>
          −{Math.round(w.avg_lost)}% each
        </span>
      </div>
      <p data-reading={short ? 'item' : undefined}
         style={{ margin: 0, fontSize: '0.86rem', lineHeight: 1.45, color: 'var(--text-primary)' }}>
        {short ?? w.drill}
      </p>
      <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
        <Positions positions={w.positions.slice(0, 3)} />
        {n > 0 && (
          <Link to={drillLink(w.positions)} style={{ ...linkStyle, fontSize: '0.8rem' }}>
            Drill {n === 1 ? 'this position' : n} →
          </Link>
        )}
        <button type="button" data-disclose aria-expanded={open} onClick={() => setOpen(!open)} className="link"
                style={{ color: 'var(--text-secondary)', fontSize: '0.75rem' }}>
          {open ? 'Hide details' : 'Details'}
        </button>
      </div>
      {open && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, paddingTop: 8, borderTop: '1px solid var(--hairline)' }}>
          <span style={{ ...muted, fontSize: '0.78rem' }}>{statLine(w)}</span>
          {extra && <p style={{ ...muted, margin: 0 }}>{extra}</p>}
          {note && <p style={{ ...muted, margin: 0, fontSize: '0.75rem' }}>{note}</p>}
          {n > 3 && <Positions positions={w.positions.slice(3)} first={0} more={`Show the other ${n - 3} positions`} />}
        </div>
      )}
      </div>
      {/* U4: the costliest of these positions, so the habit has a face. */}
      {w.positions[0] && <StaticBoard fen={w.positions[0].fen} size={112} label={`${w.positions[0].move}: the costliest`} />}
    </li>
  )
}

/** The rest of the list, so the section accounts for every mistake: a list, not a run-on sentence. */
function Remainder({ rows, shown }: { rows: ReportCount[]; shown: ReportWeakness[] }) {
  const rest = rows.filter(r => !shown.some(w => w.key === r.key))
  if (rest.length === 0) return null
  return (
    <div style={{ display: 'flex', gap: 10, alignItems: 'baseline', flexWrap: 'wrap' }}>
      <span className="micro-label">The rest</span>
      <ul aria-label="The rest" style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexWrap: 'wrap', columnGap: 18, rowGap: 4 }}>
        {rest.map(r => (
          <li key={r.key} style={{ ...muted, fontSize: '0.78rem' }}>
            {r.label} <span className="mono" style={{ color: 'var(--text-primary)' }}>{r.count}</span>
          </li>
        ))}
      </ul>
    </div>
  )
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

/** The finding first, in words; the bars below are its evidence. */
function WhereLead({ f }: { f: ReportFacts }) {
  const phase = f.by_phase.find(r => r.key === f.worst_phase)
  const range = f.by_move_range.find(r => r.key === f.worst_range)
  if (!phase && !range) return null
  const where = [phase && `the ${phase.label}`, range?.label].filter(Boolean).join(', ')
  return <p style={{ margin: 0, fontSize: '0.95rem', color: 'var(--text-primary)' }}>Most in {where}</p>
}

function RateBars({ title, rows, baseline, highlight }: {
  title: string; rows: ReportRate[]; baseline: number; highlight: string | null
}) {
  return (
    <div style={{ minWidth: 0 }}>
      <div className="micro-label" style={{ marginBottom: 8 }}>{title}</div>
      <Bars baseline={baseline} highlight={highlight} rows={rows.filter(r => r.moves > 0).map(r => ({
        key: r.key, label: r.label, value: r.per_hundred, enough: r.enough,
        text: `${rate1(r.per_hundred)} · ${r.count} of ${r.moves}`,
      }))} />
    </div>
  )
}

/**
 * Could this opening's excess be chance? Its serious mistakes against what your
 * own average predicts for that many games: under two standard deviations
 * (a count's spread is its square root) and the row is marked low sample.
 */
export function lowSample(o: Pick<ReportOpening, 'games' | 'serious'>, perGame: number) {
  const expected = perGame * o.games
  return o.serious - expected < 2 * Math.sqrt(expected)
}

function Openings({ f }: { f: ReportFacts }) {
  const cell: React.CSSProperties = { padding: '6px 8px' }
  const num: React.CSSProperties = { ...cell, textAlign: 'right' }
  return (
    <section aria-labelledby="openings-title" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <h3 id="openings-title" style={sectionTitle}>Openings above your average</h3>
      {f.openings.length === 0 ? (
        <p style={muted}>
          None: no opening with 5 or more games and {f.min_sample} or more serious mistakes is a fifth above your
          average of <span className="mono">{rate1(f.per_game)}</span> per game.
        </p>
      ) : (
        <>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', maxWidth: 640, borderCollapse: 'collapse', fontSize: '0.8rem', fontVariantNumeric: 'tabular-nums' }}>
              <caption style={{ ...muted, textAlign: 'left', paddingBottom: 6 }}>
                Your average: <span className="mono">{rate1(f.per_game)}</span> per game
              </caption>
              <thead>
                <tr style={{ color: 'var(--text-secondary)', textAlign: 'left' }}>
                  <th scope="col" style={{ ...cell, fontWeight: 500 }}>Opening</th>
                  <th scope="col" style={{ ...num, fontWeight: 500 }}>Games</th>
                  <th scope="col" style={{ ...num, fontWeight: 500 }}>Per game</th>
                  <th scope="col" style={{ ...num, fontWeight: 500 }}>vs average</th>
                </tr>
              </thead>
              <tbody>
                {f.openings.map(o => {
                  const low = lowSample(o, f.per_game)
                  return (
                    <tr key={o.name} aria-label={o.name} style={{ borderTop: '1px solid var(--hairline)', color: low ? 'var(--text-secondary)' : undefined }}>
                      <td style={cell}>
                        {o.name}
                        {low && <>{' '}<span className="micro-label" style={{ marginLeft: 4 }}>Low sample</span></>}
                      </td>
                      <td className="mono" style={num}>{o.games}</td>
                      <td className="mono" style={num}>{rate1(o.serious_per_game)}</td>
                      <td className="mono" style={num}>×{o.vs_average.toFixed(2)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <Disclose>
            Low sample: the gap could be chance. At this many games, your own average would often give this many
            mistakes; it is marked until the gap is more than twice the usual spread.
          </Disclose>
        </>
      )}
    </section>
  )
}

/** Light cards: each item leads to a drill, so it is an object you act on. */
const card: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 8, padding: '14px 16px',
  background: 'var(--surface-1)', border: '1px solid var(--hairline)', borderRadius: 4,
}

const list: React.CSSProperties = { listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 10 }

const panel: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 14 }

/** The Insights tab look: the active view is an accent wash, the only pink here besides the next action. */
function tabStyle(on: boolean): React.CSSProperties {
  return {
    // Not `all: unset`: the button keeps its focus ring.
    border: '1px solid transparent', padding: '5px 12px', borderRadius: 3, fontSize: '0.8rem', fontWeight: on ? 600 : 500,
    color: on ? 'var(--orchid)' : 'var(--text-secondary)', background: on ? 'var(--accent-wash)' : 'transparent',
  }
}
