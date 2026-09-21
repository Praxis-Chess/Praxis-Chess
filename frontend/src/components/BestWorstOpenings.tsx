import type { OpeningStat } from '../api/types'

interface Props { data: OpeningStat[] }

export function BestWorstOpenings({ data }: Props) {
  const qualified = data.filter(d => d.games >= 5)
  const best  = [...qualified].sort((a, b) => b.win_pct - a.win_pct).slice(0, 3)
  const worst = [...qualified].sort((a, b) => a.win_pct - b.win_pct).slice(0, 3)

  if (qualified.length === 0)
    return <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>Not enough data yet.</p>

  const Row = ({ d, good }: { d: OpeningStat; good: boolean }) => (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '5px 0',
                  borderBottom: '1px solid var(--border)' }}>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: '0.8rem', fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {(d.name ?? d.eco).length > 20 ? (d.name ?? d.eco).slice(0, 19) + '…' : (d.name ?? d.eco)}
        </div>
        <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>{d.eco} · {d.games} games</div>
      </div>
      <span style={{ fontWeight: 700, fontSize: '0.9rem', color: good ? 'var(--green)' : 'var(--red)', flexShrink: 0, marginLeft: 8 }}>
        {d.win_pct}%
      </span>
    </div>
  )

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: 20 }}>
      <div>
        <div style={{ fontSize: '0.75rem', fontWeight: 700, color: 'var(--green)', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
          Best Openings
        </div>
        {best.map(d => <Row key={d.eco} d={d} good={true} />)}
      </div>
      <div>
        <div style={{ fontSize: '0.75rem', fontWeight: 700, color: 'var(--red)', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
          Worst Openings
        </div>
        {worst.map(d => <Row key={d.eco} d={d} good={false} />)}
      </div>
    </div>
  )
}
