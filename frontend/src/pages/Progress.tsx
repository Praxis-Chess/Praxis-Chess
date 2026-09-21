import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { DailyStat } from '../api/types'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { monthsFrom } from '../components/practiceDates'
import { PraxAnchor } from '../prax/PraxHost'

function pct(n: number, d: number) {
  return d === 0 ? 0 : Math.round((n / d) * 100)
}

/**
 * Every month you have practised, oldest first.
 *
 * This page's job is consistency over time, so it gets the long arc while Today
 * keeps only the current week. One row per month rather than a calendar grid
 * per month: it stays legible at any number of months, and density over time is
 * the question this panel answers.
 *
 * Months with nothing in them are still drawn. Skipping them would compress a
 * three-month gap into adjacent rows and quietly rewrite the history as more
 * consistent than it was.
 */
function PracticeHistory() {
  const { data } = useQuery({
    queryKey: ['practice-streak'],
    queryFn: () => api.practice.streak(),
    staleTime: 60_000,
  })

  if (!data || data.total_days_practiced === 0) return null
  const months = monthsFrom(data.practice_days, data.today)

  return (
    <div className="card" style={{ padding: '20px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 4 }}>
        <h3 style={{ fontSize: '0.9rem', fontWeight: 600, margin: 0 }}>Practice history</h3>
        <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
          {data.total_days_practiced} days examined · longest {data.longest_streak}
        </span>
      </div>
      <p style={{ fontSize: '0.72rem', color: 'var(--text-muted)', margin: '0 0 16px' }}>
        Every day you examined your chess. Gaps are shown, not hidden.
      </p>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 7, maxHeight: 320, overflowY: 'auto' }}>
        {months.map(mo => (
          <div key={mo.key} style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{
              fontSize: '0.68rem', color: 'var(--text-muted)',
              minWidth: 58, flexShrink: 0, fontVariantNumeric: 'tabular-nums',
            }}>
              {mo.label}
            </span>

            <div style={{ display: 'flex', gap: 2, flexWrap: 'nowrap' }}>
              {mo.days.map(d => (
                <span
                  key={d.date}
                  title={`${d.date} — ${d.future ? 'upcoming' : d.practiced ? 'examined' : 'no activity'}`}
                  style={{
                    width: 7, height: 7, borderRadius: 2, flexShrink: 0,
                    background: d.practiced ? 'var(--orchid)' : 'var(--surface-2, #1B1920)',
                    outline: d.isToday ? '1px solid var(--orchid)' : 'none',
                    outlineOffset: 1,
                    opacity: d.future ? 0.25 : 1,
                  }}
                />
              ))}
            </div>

            <span style={{
              fontSize: '0.66rem', color: 'var(--text-muted)',
              marginLeft: 'auto', flexShrink: 0, fontVariantNumeric: 'tabular-nums',
            }}>
              {mo.practicedCount}/{mo.days.length}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

function AccuracyBar({ value, max = 100 }: { value: number; max?: number }) {
  const w = Math.min(100, (value / max) * 100)
  const color = value >= 80 ? 'var(--gain)' : value >= 60 ? 'var(--yellow)' : 'var(--red)'
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <div style={{ flex: 1, height: 6, background: 'var(--surface-2)', borderRadius: 3, overflow: 'hidden' }}>
        <div style={{ height: '100%', width: `${w}%`, background: color, borderRadius: 3, transition: 'width 0.4s' }} />
      </div>
      <span style={{ fontSize: '0.8rem', fontWeight: 600, color, minWidth: 36, textAlign: 'right' }}>
        {value}%
      </span>
    </div>
  )
}

function PhaseBreakdown({ stat }: { stat: DailyStat }) {
  const { phases: p } = stat
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {([
        ['Opening', p.opening_correct, p.opening_total],
        ['Middlegame', p.middlegame_correct, p.middlegame_total],
        ['Endgame', p.endgame_correct, p.endgame_total],
      ] as [string, number, number][]).map(([label, c, t]) => (
        <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)', minWidth: 80 }}>{label}</span>
          <div style={{ flex: 1 }}>
            <AccuracyBar value={pct(c, t)} />
          </div>
          <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', minWidth: 40 }}>{c}/{t}</span>
        </div>
      ))}
    </div>
  )
}

function MiniChart({ history }: { history: DailyStat[] }) {
  const max = Math.max(...history.map(s => s.reviewed), 1)
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 3, height: 80, overflow: 'hidden' }}>
      {history.slice(-30).map((s, i) => {
        const h = Math.max(4, (s.reviewed / max) * 80)
        const acc = s.accuracy
        const color = acc >= 80 ? 'var(--gain)' : acc >= 60 ? 'var(--yellow)' : 'var(--red)'
        return (
          <div key={i} title={`${s.date}: ${s.reviewed} cards, ${s.accuracy}% accuracy`}
               style={{ flex: 1, height: h, background: color, borderRadius: '2px 2px 0 0',
                        opacity: 0.85, minWidth: 4 }} />
        )
      })}
    </div>
  )
}

export function Progress() {
  const [breakdownOpen, setBreakdownOpen] = useState(false)

  const { data: progress, isLoading } = useQuery({
    queryKey: ['progress'],
    queryFn: () => api.progress.get(),
    staleTime: 60_000,
  })

  if (isLoading) return <LoadingSpinner label="Loading progress…" />
  if (!progress) return null

  const { deck_summary: d, history } = progress
  const recentStats = history.slice(-7)
  const totalRecent = recentStats.reduce((s, r) => s + r.reviewed, 0)
  const correctRecent = recentStats.reduce((s, r) => s + r.correct, 0)
  const recentAcc = pct(correctRecent, totalRecent)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24, maxWidth: 720 }}>
      <div>
        <h1 style={{ fontSize: '1.35rem', fontWeight: 700, letterSpacing: '-0.02em', marginBottom: 4 }}>
          Progress
        </h1>
        <p style={{ fontSize: '0.82rem', color: 'var(--text-muted)' }}>
          Deck health · recall rate · phase trends
        </p>
      </div>

      {/* Deck summary */}
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {[
          { label: 'Total', value: d.total_cards, color: 'var(--text)' },
          { label: 'New', value: d.new_cards, color: 'var(--text-muted)' },
          { label: 'Learning', value: d.learning_cards, color: 'var(--yellow)' },
          { label: 'Review', value: d.review_cards, color: 'var(--gain)' },
          { label: 'Suspended', value: d.suspended_cards, color: 'var(--red)' },
        ].map(({ label, value, color }) => (
          <div key={label} className="card" style={{ padding: '14px 18px', textAlign: 'center', minWidth: 90 }}>
            <div style={{ fontSize: '1.4rem', fontWeight: 700, color }}>{value}</div>
            <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 2 }}>{label}</div>
          </div>
        ))}
      </div>

      {/* 7-day accuracy */}
      <div className="card" style={{ padding: '20px 24px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
          <div>
            <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>7-day recall</div>
            <div style={{ fontSize: '1.8rem', fontWeight: 700,
                          color: recentAcc >= 80 ? 'var(--gain)' : recentAcc >= 60 ? 'var(--yellow)' : 'var(--red)' }}>
              {recentAcc}%
            </div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginBottom: 4 }}>Cards reviewed</div>
            <div style={{ fontSize: '1.4rem', fontWeight: 700 }}>{totalRecent}</div>
          </div>
        </div>
        {history.length > 0 && <MiniChart history={history} />}
      </div>

      {/* Latest phase breakdown */}
      {history.length > 0 && (
        <div className="card" style={{ padding: '20px 24px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
            <div style={{ fontWeight: 600, fontSize: '0.9rem' }}>Phase recall — last session</div>
          </div>
          <PhaseBreakdown stat={history[history.length - 1]} />
        </div>
      )}

      {/* Per-day history table (collapsed by default) */}
      <div>
        <button
          onClick={() => setBreakdownOpen(o => !o)}
          className="secondary"
          style={{ fontSize: '0.8rem' }}
        >
          {breakdownOpen ? 'Hide' : 'Show'} daily history ({history.length} days)
        </button>

        {breakdownOpen && history.length > 0 && (
          <div style={{ marginTop: 14, overflowX: 'auto' }}>
            <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: '0.8rem' }}>
              <thead>
                <tr>
                  {['Date', 'Reviewed', 'Correct', 'Again', 'Accuracy', 'Avg interval'].map(h => (
                    <th key={h} style={{ textAlign: 'left', padding: '6px 12px',
                                         borderBottom: '1px solid var(--hairline)',
                                         color: 'var(--text-muted)', fontWeight: 500 }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {[...history].reverse().map(s => (
                  <tr key={s.date}>
                    <td style={{ padding: '6px 12px' }}>{s.date}</td>
                    <td style={{ padding: '6px 12px' }}>{s.reviewed}</td>
                    <td style={{ padding: '6px 12px', color: 'var(--gain)' }}>{s.correct}</td>
                    <td style={{ padding: '6px 12px', color: 'var(--red)' }}>{s.again}</td>
                    <td style={{ padding: '6px 12px', fontWeight: 600 }}>{s.accuracy}%</td>
                    <td style={{ padding: '6px 12px', color: 'var(--text-muted)' }}>
                      {s.avg_interval_days != null ? `${s.avg_interval_days}d` : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* The long arc lives here; Today keeps only the current week. */}
      <PracticeHistory />

      {/* Right of the analytics column — Contract §4. */}
      <PraxAnchor x={0.74} y={0.45} />
    </div>
  )
}
