import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { PraxAnchor } from '../prax/PraxHost'
import {
  BarChart, Bar, LineChart, Line, XAxis, YAxis, Tooltip,
  ResponsiveContainer, CartesianGrid, Cell,
} from 'recharts'
import { api } from '../api/client'
import type { TimeBucket } from '../api/types'

const wrColor = (pct: number) =>
  pct >= 55 ? 'var(--green)' : pct >= 45 ? 'var(--accent)' : 'var(--red)'

const motifLabel = (m: string) =>
  m.split('_').map(w => w.charAt(0) + w.slice(1).toLowerCase()).join(' ')

function SectionTitle({ children, hint }: { children: React.ReactNode; hint?: string }) {
  return (
    <div style={{ marginBottom: 14 }}>
      <div style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text-muted)' }}>{children}</div>
      {hint && <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', opacity: 0.75, marginTop: 2 }}>{hint}</div>}
    </div>
  )
}

const tooltipStyle = {
  contentStyle: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 6, fontSize: 12 },
  labelStyle: { color: 'var(--text-muted)', fontSize: 11 },
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
          {rows.map((r, i) => <Cell key={i} fill={r.games === 0 ? 'var(--surface-2)' : wrColor(r.win_pct)} />)}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

export function Insights() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['insights'],
    queryFn: api.insights.get,
  })

  if (isLoading) return <p style={{ color: 'var(--text-muted)' }}>Loading insights…</p>
  if (error)     return <p style={{ color: 'var(--red)' }}>Failed to load insights. Is analysis done?</p>
  if (!data)     return <p style={{ color: 'var(--text-muted)' }}>No data yet. Sync and analyze games first.</p>

  const tm = data.time_management
  const cv = data.conversion
  const phaseTotal = data.phase_accuracy.opening + data.phase_accuracy.middlegame + data.phase_accuracy.endgame || 1
  const maxMotif = Math.max(1, ...data.missed_tactics.map(m => m.count))
  const accSeries = data.accuracy_trend.map(p => ({
    date: new Date(p.date).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
    accuracy: p.accuracy,
    moving_avg: p.moving_avg,
  }))

  return (
    // Insights is the one page whose analytics span the full column, leaving no
    // negative space for Prax. Capping the width opens the right-hand column the
    // other pages already have — Prax occupies space rather than competing for it.
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20, maxWidth: 'min(100%, 860px)' }}>
      <div>
        <h2 style={{ fontSize: '1.2rem', fontWeight: 700, margin: 0 }}>Insights</h2>
        <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 4 }}>
          Practical analytics derived from your analyzed games — where your rating actually leaks.
        </p>
      </div>

      {/* Row 1 — headline metric cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
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
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: tm.time_trouble_rate >= 40 ? 'var(--red)' : 'var(--accent)' }}>
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
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: 3 }}>Avg time per move</div>
          <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 2 }}>across timed games</div>
        </div>

        <Link to="/drills" className="card" style={{ textDecoration: 'none', color: 'inherit', display: 'block' }}>
          <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--accent)' }}>♟ Drill</div>
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: 3 }}>Practice your own blunders</div>
          <div style={{ fontSize: '0.7rem', color: 'var(--accent)', marginTop: 2 }}>Open trainer →</div>
        </Link>
      </div>

      {/* Row 2 — accuracy trend (full width) */}
      <div className="card">
        <SectionTitle hint="Per-game accuracy with a 10-game rolling average — the least noisy signal of real improvement.">
          Accuracy Trend
        </SectionTitle>
        {accSeries.length > 1 ? (
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={accSeries} margin={{ top: 4, right: 12, left: -20, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
              <XAxis dataKey="date" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} tickLine={false} interval="preserveStartEnd" />
              <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} tickLine={false} domain={[0, 100]} />
              <Tooltip {...tooltipStyle} />
              <Line type="monotone" dataKey="accuracy" stroke="var(--border)" strokeWidth={1} dot={false} name="Game" />
              <Line type="monotone" dataKey="moving_avg" stroke="var(--accent)" strokeWidth={2.5} dot={false} name="10-game avg" />
            </LineChart>
          </ResponsiveContainer>
        ) : <p style={{ color: 'var(--text-muted)', fontSize: '0.82rem' }}>Not enough analyzed games yet.</p>}
      </div>

      {/* Row 3 — opponent strength | phase leak | missed tactics */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
        <div className="card">
          <SectionTitle hint="Win rate vs opponents ±50 rating.">vs Opponent Strength</SectionTitle>
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
                <div style={{ height: '100%', width: `${b.win_pct}%`, background: wrColor(b.win_pct), borderRadius: 4 }} />
              </div>
            </div>
          ))}
        </div>

        <div className="card">
          <SectionTitle hint="Where your flagged mistakes cluster by phase.">Mistakes by Phase</SectionTitle>
          {[
            { label: 'Opening', count: data.phase_accuracy.opening, color: 'var(--green)' },
            { label: 'Middlegame', count: data.phase_accuracy.middlegame, color: 'var(--yellow)' },
            { label: 'Endgame', count: data.phase_accuracy.endgame, color: 'var(--red)' },
          ].map(({ label, count, color }) => {
            const pct = Math.round((count / phaseTotal) * 100)
            return (
              <div key={label} style={{ marginBottom: 12 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.8rem', marginBottom: 4 }}>
                  <span>{label}</span>
                  <span style={{ color: 'var(--text-muted)', fontWeight: 600 }}>{count} <span style={{ fontWeight: 400, opacity: 0.7 }}>({pct}%)</span></span>
                </div>
                <div style={{ height: 6, background: 'var(--surface-2)', borderRadius: 4, overflow: 'hidden' }}>
                  <div style={{ height: '100%', width: `${pct}%`, background: color, borderRadius: 4 }} />
                </div>
              </div>
            )
          })}
        </div>

        <div className="card">
          <SectionTitle hint="Tactical motifs you miss most often.">Missed Tactics</SectionTitle>
          {data.missed_tactics.length > 0 ? data.missed_tactics.slice(0, 6).map(m => (
            <div key={m.motif} style={{ marginBottom: 10 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.8rem', marginBottom: 4 }}>
                <span>{motifLabel(m.motif)}</span>
                <span style={{ color: 'var(--text-muted)', fontWeight: 600 }}>{m.count}</span>
              </div>
              <div style={{ height: 6, background: 'var(--surface-2)', borderRadius: 4, overflow: 'hidden' }}>
                <div style={{ height: '100%', width: `${(m.count / maxMotif) * 100}%`, background: 'var(--accent)', borderRadius: 4 }} />
              </div>
            </div>
          )) : <p style={{ color: 'var(--text-muted)', fontSize: '0.82rem' }}>No tactical motifs flagged yet.</p>}
        </div>
      </div>

      {/* Row 4 — time of day | day of week */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.4fr', gap: 16 }}>
        <div className="card">
          <SectionTitle hint="Win rate by part of day.">Time of Day</SectionTitle>
          <WinRateBars data={data.time_of_day} />
        </div>
        <div className="card">
          <SectionTitle hint="Win rate by weekday.">Day of Week</SectionTitle>
          <WinRateBars data={data.day_of_week} />
        </div>
      </div>

      {/* Row 5 — tilt | conversion detail */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.6fr', gap: 16 }}>
        <div className="card">
          <SectionTitle hint="Do you tilt after a loss? Compare the next game's win rate.">Resilience</SectionTitle>
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
              ⚠ You perform notably worse after a loss — consider a short break before the next game.
            </div>
          )}
        </div>

        <div className="card">
          <SectionTitle hint="Games where you reached ≥ +2.0 but didn't win.">Thrown-Away Wins</SectionTitle>
          {cv.blown_games.length > 0 ? (
            <div>
              {cv.blown_games.map(g => (
                <Link key={g.game_id} to={`/games/${g.game_id}`}
                      style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                               padding: '7px 0', borderBottom: '1px solid var(--border)',
                               textDecoration: 'none', color: 'inherit', fontSize: '0.8rem' }}>
                  <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 220 }}>
                    {g.opening_name || 'Unknown opening'}
                  </span>
                  <span style={{ display: 'flex', gap: 10, flexShrink: 0 }}>
                    <span style={{ color: 'var(--green)', fontWeight: 600 }}>+{g.max_advantage}</span>
                    <span style={{ color: g.result === 'loss' ? 'var(--red)' : 'var(--text-muted)', textTransform: 'capitalize' }}>{g.result}</span>
                    <span style={{ color: 'var(--text-muted)' }}>{g.played_at ?? ''}</span>
                  </span>
                </Link>
              ))}
            </div>
          ) : <p style={{ color: 'var(--text-muted)', fontSize: '0.82rem' }}>No thrown-away winning positions. Great conversion!</p>}
        </div>
      </div>

      {/* Row 6 — openings table */}
      <div className="card">
        <SectionTitle hint="Win rate and average accuracy per opening (most-played first).">Openings</SectionTitle>
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
      {/* Right of the capped analytics column, clear of KPI cards and charts. */}
      <PraxAnchor x={0.85} y={0.45} />
    </div>
  )
}
