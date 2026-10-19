import { Link, NavLink, useParams } from 'react-router-dom'
import { PatternReport } from './PatternReport'
import { TrainingPlan } from './TrainingPlan'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { PraxAnchor } from '../prax/PraxHost'
import {
  BarChart, Bar, ComposedChart, Area, XAxis, YAxis, Tooltip, Legend,
  ResponsiveContainer, CartesianGrid, Cell,
} from 'recharts'
import { api } from '../api/client'
import type { TimeBucket, TimeClassCount } from '../api/types'
import { Disclose, dayMonth } from '../components/report/ReportParts'

// Results colours only: pink is for what can be clicked, so an even win rate is neutral.
const wrColor = (pct: number) =>
  pct >= 55 ? 'var(--green)' : pct >= 45 ? 'var(--text-primary)' : 'var(--red)'
/** Bars behind a neutral figure: a brighter neutral, never the accent. */
const wrBar = (pct: number) =>
  pct >= 55 ? 'var(--green)' : pct >= 45 ? 'var(--grey-1)' : 'var(--red)'

/** The diagnosis's "no tactic" categories: counted, never shown as a tactic (as in the Pattern Report). */
const NOT_A_TACTIC = new Set(['POSITIONAL', 'OTHER'])

const motifLabel = (m: string) =>
  m.split('_').map(w => w.charAt(0) + w.slice(1).toLowerCase()).join(' ')

/** A card's title and, at most, a few words on what it measures. */
function SectionTitle({ children, hint }: { children: React.ReactNode; hint?: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 14 }}>
      <h3 style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text-muted)', margin: 0 }}>{children}</h3>
      {hint && <div data-reading="intro" style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 2 }}>{hint}</div>}
    </div>
  )
}

const tooltipStyle = {
  contentStyle: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 6, fontSize: 12 },
  labelStyle: { color: 'var(--text-muted)', fontSize: 11 },
}

const shortDate = (ts: number) => new Date(ts).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
const longDate = (ts: number) => new Date(ts).toLocaleString('en-US',
  { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' })

/**
 * About six ticks at local midnights, evenly spaced in days, so the labels mean
 * real dates. The axis starts at the first tick: the first game's own day.
 */
function dateTicks(times: number[]): number[] {
  if (times.length === 0) return []
  const day = 86_400_000
  const first = new Date(Math.min(...times)); first.setHours(0, 0, 0, 0)
  const last = Math.max(...times)
  const span = Math.max(1, Math.ceil((last - first.getTime()) / day))
  const step = Math.max(1, Math.ceil(span / 6))
  const ticks: number[] = []
  for (const d = new Date(first); d.getTime() <= last; d.setDate(d.getDate() + step)) ticks.push(d.getTime())
  return ticks
}

function WinRateBars({ data }: { data: TimeBucket[] }) {
  const rows = data.map(d => ({ label: d.label, win_pct: d.win_pct, games: d.games }))
  return (
    <ResponsiveContainer width="100%" height={170}>
      <BarChart data={rows} margin={{ top: 4, right: 8, left: -22, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
        <XAxis dataKey="label" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} tickLine={false} />
        <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} tickLine={false} domain={[0, 100]} />
        <Tooltip {...tooltipStyle} formatter={(value, _name, item) => [`${value}% · ${(item as { payload?: { games?: number } })?.payload?.games ?? 0}g`, 'Win rate']} cursor={{ fill: 'var(--surface-2)' }} />
        <Bar dataKey="win_pct" radius={[3, 3, 0, 0]}>
          {rows.map((r, i) => <Cell key={i} fill={r.games === 0 ? 'var(--surface-2)' : wrBar(r.win_pct)} />)}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

/**
 * Trends here mix every analysed game. If those games were analysed with
 * different engine settings, a "trend" can be the engine looking deeper rather
 * than the player improving — so say so, and point at the fix.
 */
function MixedSettingsNotice() {
  const { data } = useQuery({ queryKey: ['settings-coverage'], queryFn: api.settings.coverage })
  if (!data?.mixed_settings) return null
  return (
    <div role="status" style={{
      padding: '10px 12px', borderLeft: '2px solid var(--warn)', background: 'rgba(229, 176, 75, 0.08)',
      fontSize: '0.78rem', color: 'var(--text-secondary)', lineHeight: 1.5,
    }}>
      These games were analysed with {data.analyzed_with.length} different engine settings
      ({data.analyzed_with.map(c => `${c.label}: ${c.games}`).join(', ')}), and the trends below mix them.{' '}
      <Link to="/settings">Re-analyse on the Settings page</Link> to compare like with like.
    </div>
  )
}

/**
 * Speeds don't mix: a bullet flag-loss says nothing about rapid, and each speed
 * has its own rating. The page opens on the analysed time class (rapid by default).
 */
function TimeClassPicker({ selected, options, onPick }: {
  selected: string | null
  options: TimeClassCount[]
  onPick: (tc: string) => void
}) {
  if (options.length < 2) return null
  const chip = (value: string, label: string, title?: string) => {
    const on = (selected ?? 'all') === value
    return (
      <button key={value} type="button" onClick={() => onPick(value)} aria-pressed={on} title={title}
        style={{
          padding: '4px 10px', borderRadius: 999, fontSize: '0.75rem', cursor: 'pointer',
          border: `1px solid ${on ? 'var(--accent)' : 'var(--border)'}`,
          background: on ? 'var(--accent-dim)' : 'transparent',
          color: on ? 'var(--text)' : 'var(--text-muted)',
        }}>
        {label}
      </button>
    )
  }
  return (
    <div role="group" aria-label="Time control" style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
      {options.map(o => chip(o.time_class,
        `${o.time_class[0].toUpperCase()}${o.time_class.slice(1)} · ${o.games}${o.analysed ? '' : ' · results only'}`,
        o.analysed ? undefined : 'Not analysed: results and win rates only (Settings → time controls)'))}
      {chip('all', 'All')}
    </div>
  )
}

const TABS = [
  { key: '', label: 'Overview', to: '/insights' },
  { key: 'patterns', label: 'Pattern Report', to: '/insights/patterns' },
  { key: 'training', label: 'Training Plan', to: '/insights/training' },
]

/**
 * Insights, with the Pattern Report and Training Plan as tabs (Phase 12, D5).
 * The old /patterns and /training routes redirect here.
 */
export function Insights() {
  const { tab = '' } = useParams()
  const active = TABS.some(t => t.key === tab) ? tab : ''
  return (
    // Insights is the one page whose analytics span the full column, leaving no
    // negative space for Prax. Capping the width opens the right-hand column the
    // other pages already have — Prax occupies space rather than competing for it.
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20, maxWidth: 'min(100%, 860px)' }}>
      <h2 style={{ fontSize: '1.2rem', fontWeight: 700, margin: 0 }}>Insights</h2>
      {/* A group, not a second <nav>: the app shell owns the one navigation landmark. */}
      <div role="group" aria-label="Insights sections" style={{ display: 'flex', gap: 6, flexWrap: 'wrap', borderBottom: '1px solid var(--hairline)', paddingBottom: 8 }}>
        {TABS.map(t => (
          <NavLink key={t.key} to={t.to} end aria-current={active === t.key ? 'page' : undefined}
                   style={{
                     padding: '5px 12px', borderRadius: 4, fontSize: '0.82rem', textDecoration: 'none',
                     color: active === t.key ? 'var(--orchid)' : 'var(--text-secondary)',
                     background: active === t.key ? 'var(--accent-wash)' : 'transparent',
                     fontWeight: active === t.key ? 600 : 400,
                   }}>
            {t.label}
          </NavLink>
        ))}
      </div>
      {active === 'patterns' ? <PatternReport /> : active === 'training' ? <TrainingPlan /> : <InsightsOverview />}
      {/* Right of the capped analytics column, clear of KPI cards and charts. */}
      <PraxAnchor x={0.85} y={0.45} />
    </div>
  )
}

function InsightsOverview() {
  // Undefined until a chip is clicked: the server picks the analysed time class.
  const [timeClass, setTimeClass] = useState<string | undefined>()
  const { data, isLoading, error } = useQuery({
    queryKey: ['insights', timeClass ?? 'default'],
    queryFn: () => api.insights.get(timeClass),
    placeholderData: prev => prev,
  })

  if (isLoading) return <p style={{ color: 'var(--text-muted)' }}>Loading insights…</p>
  if (error)     return <p style={{ color: 'var(--red)' }}>Failed to load insights. Is analysis done?</p>
  if (!data)     return <p style={{ color: 'var(--text-muted)' }}>No data yet. Sync and analyze games first.</p>

  const tm = data.time_management
  const cv = data.conversion
  const phaseTotal = data.phase_accuracy.opening + data.phase_accuracy.middlegame + data.phase_accuracy.endgame || 1
  const tactics = data.missed_tactics.filter(m => !NOT_A_TACTIC.has(m.motif))
  const notTactics = data.missed_tactics.filter(m => NOT_A_TACTIC.has(m.motif))
  const maxMotif = Math.max(1, ...tactics.map(m => m.count))
  // Placed by when each game was played, so a gap of a week looks like one and
  // four games on one day sit together.
  const accSeries = data.accuracy_trend.map(p => ({
    ts: Date.parse(p.played_at ?? `${p.date}T12:00:00`),
    accuracy: p.accuracy,
    moving_avg: p.moving_avg,
    rating: p.rating,
  }))
  const xTicks = dateTicks(accSeries.map(p => p.ts))
  // Elo is drawn on its own right-hand axis: it lives around 1000–1600, accuracy on 0–100.
  const hasRating = accSeries.some(p => p.rating != null)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <TimeClassPicker selected={data.time_class ?? null} options={data.time_classes ?? []} onPick={setTimeClass} />

      <MixedSettingsNotice />

      {/* Row 1 — headline metric cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 150px), 1fr))', gap: 12 }}>
        <div className="card">
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: wrColor(cv.conversion_pct) }}>
            {cv.winning_games > 0 ? `${cv.conversion_pct}%` : '—'}
          </div>
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: 3 }}>Winning-position conversion</div>
          <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 2 }}>
            {cv.converted}/{cv.winning_games} games ≥ +2.0 won
          </div>
        </div>

        <div className="card">
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: tm.time_trouble_rate >= 40 ? 'var(--red)' : 'var(--text-primary)' }}>
            {tm.total_blunders > 0 ? `${tm.time_trouble_rate}%` : '—'}
          </div>
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: 3 }}>Blunders in time trouble</div>
          <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 2 }}>
            {tm.blunders_in_time_pressure}/{tm.total_blunders} under 30s
          </div>
        </div>

        <div className="card">
          <div style={{ fontSize: '1.4rem', fontWeight: 700 }}>
            {tm.avg_move_seconds != null ? `${tm.avg_move_seconds}s` : '—'}
          </div>
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: 3 }}>Avg move time</div>
          <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 2 }}>timed games</div>
        </div>

        <Link to="/drills" className="card" style={{ textDecoration: 'none', color: 'inherit', display: 'block' }}>
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--accent)' }}>♟ Drill →</div>
        </Link>
      </div>

      {/* Row 2 — accuracy trend (full width) */}
      <div className="card">
        <SectionTitle hint="First 9 points: fewer games">
          {hasRating ? 'Accuracy & Elo Trend' : 'Accuracy Trend'}
        </SectionTitle>
        {accSeries.length > 1 ? (
          <div aria-label="Accuracy and Elo trend">
          <ResponsiveContainer width="100%" height={220}>
            <ComposedChart data={accSeries} margin={{ top: 4, right: hasRating ? -8 : 12, left: -20, bottom: 0 }}>
              {/* Each series fades from its own colour to nothing: overlapping translucent silhouettes. */}
              <defs>
                {[['fill-per-game', 'var(--per-game)', 0.22], ['fill-avg', 'var(--text-primary)', 0.18], ['fill-elo', 'var(--rating)', 0.28]].map(([id, color, top]) => (
                  <linearGradient key={id as string} id={id as string} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" style={{ stopColor: color as string, stopOpacity: top as number }} />
                    <stop offset="100%" style={{ stopColor: color as string, stopOpacity: 0 }} />
                  </linearGradient>
                ))}
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
              <XAxis dataKey="ts" type="number" scale="time" domain={[xTicks[0] ?? 'dataMin', 'dataMax']}
                ticks={xTicks} tickFormatter={shortDate}
                tick={{ fill: 'var(--text-muted)', fontSize: 10 }} tickLine={false} />
              <YAxis yAxisId="acc" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} tickLine={false} domain={[0, 100]} />
              {hasRating && (
                <YAxis yAxisId="elo" orientation="right" tick={{ fill: 'var(--rating)', fontSize: 10 }} tickLine={false}
                  allowDecimals={false} width={44}
                  domain={[(min: number) => Math.floor((min - 25) / 50) * 50, (max: number) => Math.ceil((max + 25) / 50) * 50]} />
              )}
              <Tooltip {...tooltipStyle} labelFormatter={label => (typeof label === 'number' ? longDate(label) : label)} />
              {/* Labels in muted text: the per-game line's own colour is too faint to read as a label. */}
              <Legend verticalAlign="top" height={22} iconType="plainline" wrapperStyle={{ fontSize: 11 }}
                formatter={(value: string) => <span style={{ color: 'var(--text-muted)' }}>{value}</span>} />
              {/* Linear, not smoothed: on a real time axis games played minutes apart
                  sit almost on top of each other, and a smoothing curve swings wildly
                  between them. One dot per game shows where the games are.
                  Not animated: the draw-in took seconds on a time axis, and repainting
                  this frosted card every frame over Prax's animated canvas left stale
                  copies of card edges beside it. */}
              <Area yAxisId="acc" type="linear" dataKey="accuracy" stroke="var(--per-game)" strokeOpacity={0.7} strokeWidth={1}
                fill="url(#fill-per-game)" dot={{ r: 1.6, fill: 'var(--per-game)', strokeWidth: 0 }} activeDot={{ r: 3 }} name="Game accuracy" isAnimationActive={false} />
              <Area yAxisId="acc" type="linear" dataKey="moving_avg" stroke="var(--text-primary)" strokeWidth={2.5}
                fill="url(#fill-avg)" dot={false} activeDot={{ r: 3 }} name="Rolling avg (up to 10 games)" isAnimationActive={false} />
              {hasRating && (
                <Area yAxisId="elo" type="linear" dataKey="rating" stroke="var(--rating)" strokeWidth={2}
                  fill="url(#fill-elo)" dot={false} activeDot={{ r: 3 }} name="Elo" connectNulls isAnimationActive={false} />
              )}
            </ComposedChart>
          </ResponsiveContainer>
          </div>
        ) : <p style={{ color: 'var(--text-muted)', fontSize: '0.82rem' }}>Not enough analyzed games yet.</p>}
        <Disclose>
          {hasRating
            ? 'Per-game accuracy with a rolling average of up to 10 games (left axis), and your Elo in each game (right axis). Dates are to scale.'
            : 'Per-game accuracy with a rolling average of up to 10 games, the least noisy signal of real improvement. Dates are to scale.'}
        </Disclose>
      </div>

      {/* Row 3 — opponent strength | phase leak | missed tactics */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 220px), 1fr))', gap: 16 }}>
        <div className="card">
          <SectionTitle hint="Win % · ±50 bands">vs Opponent Strength</SectionTitle>
          {data.opponent_strength.map(b => (
            <div key={b.bucket} style={{ marginBottom: 12 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.8rem', marginBottom: 4 }}>
                <span>{b.bucket}</span>
                <span style={{ color: 'var(--text-muted)' }}>
                  {b.games > 0 ? `${b.win_pct}% · ${b.games}g` : 'no games'}
                  {b.avg_accuracy != null ? ` · ${b.avg_accuracy}% acc` : ''}
                </span>
              </div>
              <div style={{ height: 6, background: 'var(--surface-2)', borderRadius: 4, overflow: 'hidden' }}>
                <div style={{ height: '100%', width: `${b.win_pct}%`, background: wrBar(b.win_pct), borderRadius: 4 }} />
              </div>
            </div>
          ))}
        </div>

        <div className="card">
          {/* phase_accuracy holds mistake COUNTS per phase (the field is misnamed): a share of
              all flagged mistakes, not a rate. The rate per 100 moves is on the Pattern Report. */}
          <SectionTitle hint={<>All flagged · <Link to="/insights/patterns#where-they-happen">per 100 moves →</Link></>}>
            Mistakes by Phase
          </SectionTitle>
          {[
            { label: 'Opening', count: data.phase_accuracy.opening },
            { label: 'Middlegame', count: data.phase_accuracy.middlegame },
            { label: 'Endgame', count: data.phase_accuracy.endgame },
          ].map(({ label, count }) => {
            const pct = Math.round((count / phaseTotal) * 100)
            return (
              <div key={label} style={{ marginBottom: 12 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.8rem', marginBottom: 4 }}>
                  <span>{label}</span>
                  <span style={{ color: 'var(--text-muted)', fontWeight: 600 }}>{count} <span style={{ fontWeight: 400, opacity: 0.7 }}>({pct}%)</span></span>
                </div>
                <div style={{ height: 6, background: 'var(--surface-2)', borderRadius: 4, overflow: 'hidden' }}>
                  <div style={{ height: '100%', width: `${pct}%`, background: 'var(--grey-1)', borderRadius: 4 }} />
                </div>
              </div>
            )
          })}
        </div>

        <div className="card">
          <SectionTitle>Missed Tactics</SectionTitle>
          {tactics.length > 0 ? tactics.slice(0, 6).map(m => (
            <div key={m.motif} style={{ marginBottom: 10 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.8rem', marginBottom: 4 }}>
                <span>{motifLabel(m.motif)}</span>
                <span style={{ color: 'var(--text-muted)', fontWeight: 600 }}>{m.count}</span>
              </div>
              <div style={{ height: 6, background: 'var(--surface-2)', borderRadius: 4, overflow: 'hidden' }}>
                <div style={{ height: '100%', width: `${(m.count / maxMotif) * 100}%`, background: 'var(--grey-1)', borderRadius: 4 }} />
              </div>
            </div>
          )) : <p style={{ color: 'var(--text-muted)', fontSize: '0.82rem' }}>No tactical motifs flagged yet.</p>}
          {(tactics.length > 6 || notTactics.length > 0) && (
            <p style={{ color: 'var(--text-muted)', fontSize: '0.75rem', margin: 0 }}>
              The rest: {[...tactics.slice(6), ...notTactics].map(m => `${motifLabel(m.motif).toLowerCase()} ${m.count}`).join(', ')}
              {notTactics.length > 0 && ' (no tactic proven)'}.
            </p>
          )}
        </div>
      </div>

      {/* Row 4 — time of day | day of week */}
      <div className="stack-on-phone" style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.4fr)', gap: 16 }}>
        <div className="card">
          <SectionTitle hint="Win %">Time of Day</SectionTitle>
          <WinRateBars data={data.time_of_day} />
        </div>
        <div className="card">
          <SectionTitle hint="Win %">Day of Week</SectionTitle>
          <WinRateBars data={data.day_of_week} />
        </div>
      </div>

      {/* Row 5 — tilt | conversion detail */}
      <div className="stack-on-phone" style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.6fr)', gap: 16 }}>
        <div className="card">
          <SectionTitle hint="Next game's win %">Resilience</SectionTitle>
          <div style={{ display: 'flex', gap: 12 }}>
            <div style={{ flex: 1, textAlign: 'center' }}>
              <div style={{ fontSize: '1.5rem', fontWeight: 700, color: wrColor(data.tilt.after_win_win_pct) }}>{data.tilt.after_win_win_pct}%</div>
              <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: 2 }}>After a win</div>
              <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>{data.tilt.after_win_games} games</div>
            </div>
            <div style={{ width: 1, background: 'var(--border)' }} />
            <div style={{ flex: 1, textAlign: 'center' }}>
              <div style={{ fontSize: '1.5rem', fontWeight: 700, color: wrColor(data.tilt.after_loss_win_pct) }}>{data.tilt.after_loss_win_pct}%</div>
              <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: 2 }}>After a loss</div>
              <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>{data.tilt.after_loss_games} games</div>
            </div>
          </div>
          {data.tilt.after_loss_win_pct < data.tilt.after_win_win_pct - 8 && (
            <div style={{ fontSize: '0.72rem', color: 'var(--red)', marginTop: 12, lineHeight: 1.4 }}>
              ⚠ {Math.round((data.tilt.after_win_win_pct - data.tilt.after_loss_win_pct) * 10) / 10} points lower after a loss · rest first
            </div>
          )}
        </div>

        <div className="card">
          <SectionTitle hint="≥ +2.0, not won">Thrown-Away Wins</SectionTitle>
          {cv.blown_games.length > 0 ? (
            <div>
              {cv.blown_games.map(g => (
                <Link key={g.game_id}
                      to={g.turning_ply != null ? `/games/${g.game_id}?ply=${g.turning_ply}&from=thrown` : `/games/${g.game_id}`}
                      title={g.turning_move ? `Open the game at ${g.turning_move}, where the win slipped` : undefined}
                      style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10,
                               padding: '7px 0', borderBottom: '1px solid var(--border)',
                               textDecoration: 'none', color: 'inherit', fontSize: '0.8rem' }}>
                  <span style={{ minWidth: 0, display: 'flex', flexDirection: 'column' }}>
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 220 }}>
                      {g.opening_name || 'Unknown opening'}
                    </span>
                    {g.turning_move && (
                      <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
                        slipped at <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--red)' }}>{g.turning_move}</span>
                      </span>
                    )}
                  </span>
                  <span style={{ display: 'flex', gap: 10, flexShrink: 0 }}>
                    {/* Mate is stored as ±100 pawns; "+100" reads as a bug. */}
                    <span style={{ color: 'var(--green)', fontWeight: 600 }}>{g.max_advantage >= 99 ? 'mate' : `+${g.max_advantage}`}</span>
                    <span style={{ color: g.result === 'loss' ? 'var(--red)' : 'var(--text-muted)', textTransform: 'capitalize' }}>{g.result}</span>
                    <span style={{ color: 'var(--text-muted)' }}>{g.played_at ? dayMonth(new Date(g.played_at)) : ''}</span>
                  </span>
                </Link>
              ))}
            </div>
          ) : <p style={{ color: 'var(--text-muted)', fontSize: '0.82rem' }}>No thrown-away winning positions. Great conversion!</p>}
        </div>
      </div>

      {/* Row 6 — openings table */}
      <div className="card">
        <SectionTitle hint="Most-played first">Openings</SectionTitle>
        {data.openings.length > 0 ? (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem' }}>
              <thead>
                <tr style={{ color: 'var(--text-muted)', textAlign: 'left' }}>
                  <th style={{ padding: '6px 8px', fontWeight: 600 }}>ECO</th>
                  <th style={{ padding: '6px 8px', fontWeight: 600 }}>Opening</th>
                  <th style={{ padding: '6px 8px', fontWeight: 600, textAlign: 'right' }}>Games</th>
                  <th style={{ padding: '6px 8px', fontWeight: 600, textAlign: 'right' }}>Win %</th>
                  <th style={{ padding: '6px 8px', fontWeight: 600, textAlign: 'right' }}>Accuracy</th>
                </tr>
              </thead>
              <tbody>
                {data.openings.map(o => (
                  <tr key={o.eco} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={{ padding: '6px 8px', color: 'var(--text-muted)' }}>{o.eco}</td>
                    <td style={{ padding: '6px 8px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 320 }}>{o.name}</td>
                    <td style={{ padding: '6px 8px', textAlign: 'right' }}>{o.games}</td>
                    <td style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 600, color: wrColor(o.win_pct) }}>{o.win_pct}%</td>
                    <td style={{ padding: '6px 8px', textAlign: 'right', color: 'var(--text-muted)' }}>{o.avg_accuracy != null ? `${o.avg_accuracy}%` : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <p style={{ color: 'var(--text-muted)', fontSize: '0.82rem' }}>No opening data yet.</p>}
      </div>
    </div>
  )
}
