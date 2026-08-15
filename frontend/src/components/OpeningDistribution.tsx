import { useState } from 'react'
import type { OpeningStat } from '../api/types'

interface Props {
  data: OpeningStat[]
  totalGames: number
}

// Chart sequence: accent pair, then signal hues, then grayscale for the long tail.
const SERIES = ['#E7A6D6', '#B0679F', '#B9D96C', '#E5B04B', '#E2664A', '#7A736E', '#635C58', '#423C39']

export function OpeningDistribution({ data, totalGames }: Props) {
  const [selected, setSelected] = useState<string | null>(null)

  if (!data || data.length === 0)
    return <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>No opening data yet.</p>

  const top = data.slice(0, 8)
  const max = top[0]?.games ?? 1
  const total = Math.max(totalGames, 1)
  const selectedStat = selected ? top.find(d => d.eco === selected) : null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {top.map((d, i) => {
        const barW = Math.round((d.games / max) * 100)
        const pct  = Math.round((d.games / total) * 100)
        const name = d.name ?? d.eco
        const short = name.length > 22 ? name.slice(0, 21) + '…' : name
        const isSelected = selected === d.eco

        return (
          <div key={d.eco}>
            <div
              onClick={() => setSelected(isSelected ? null : d.eco)}
              style={{
                display: 'flex', alignItems: 'center', gap: 8,
                cursor: 'pointer', padding: '4px 6px', borderRadius: 6,
                background: isSelected ? 'var(--accent-dim)' : 'transparent',
                transition: 'background 0.15s',
              }}
              onMouseEnter={e => { if (!isSelected) (e.currentTarget as HTMLElement).style.background = 'var(--surface-2)' }}
              onMouseLeave={e => { if (!isSelected) (e.currentTarget as HTMLElement).style.background = 'transparent' }}
            >
              <span style={{ width: 30, fontSize: '0.7rem', fontWeight: 700, color: isSelected ? 'var(--accent)' : 'var(--text-muted)', flexShrink: 0 }}>
                {d.eco}
              </span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: '0.75rem', color: isSelected ? 'var(--text)' : 'var(--text)', marginBottom: 3, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                  {short}
                </div>
                <div style={{ height: 6, borderRadius: 3, background: 'var(--surface-2)', overflow: 'hidden' }}>
                  <div style={{ height: '100%', width: `${barW}%`, borderRadius: 3,
                                background: SERIES[i % SERIES.length], transition: 'width 0.4s' }} />
                </div>
              </div>
              <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)', width: 38, textAlign: 'right', flexShrink: 0 }}>
                {d.games} <span style={{ opacity: 0.6 }}>({pct}%)</span>
              </span>
            </div>

            {/* Expanded detail panel */}
            {isSelected && selectedStat && (
              <div style={{
                margin: '4px 0 6px 36px',
                background: 'var(--surface-2)', borderRadius: 8, padding: '12px 14px',
                borderLeft: '2px solid var(--accent)',
              }}>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 10, marginBottom: 10 }}>
                  <div style={{ textAlign: 'center' }}>
                    <div style={{ fontSize: '1.1rem', fontWeight: 700 }}>{selectedStat.games}</div>
                    <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)', marginTop: 2 }}>Games</div>
                  </div>
                  <div style={{ textAlign: 'center' }}>
                    <div style={{ fontSize: '1.1rem', fontWeight: 700,
                      color: selectedStat.win_pct >= 55 ? 'var(--green)' : selectedStat.win_pct >= 45 ? 'var(--accent)' : 'var(--red)' }}>
                      {selectedStat.win_pct}%
                    </div>
                    <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)', marginTop: 2 }}>Win Rate</div>
                  </div>
                  <div style={{ textAlign: 'center' }}>
                    <div style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--text-muted)' }}>{selectedStat.wins}W</div>
                    <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)', marginTop: 2 }}>Wins</div>
                  </div>
                </div>
                {/* Win rate bar */}
                <div style={{ height: 6, background: 'var(--surface)', borderRadius: 3, overflow: 'hidden' }}>
                  <div style={{
                    height: '100%', width: `${selectedStat.win_pct}%`,
                    background: selectedStat.win_pct >= 55 ? 'var(--green)' : selectedStat.win_pct >= 45 ? 'var(--accent)' : 'var(--red)',
                    borderRadius: 3, transition: 'width 0.4s',
                  }} />
                </div>
                <div style={{ marginTop: 8, fontSize: '0.72rem', color: 'var(--text-muted)' }}>
                  {selectedStat.win_pct >= 55
                    ? '✓ Strong opening for you — keep playing it'
                    : selectedStat.win_pct >= 45
                    ? '→ Neutral results — study key middlegame plans'
                    : '✗ Struggling here — consider repertoire change or targeted study'}
                </div>
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}
