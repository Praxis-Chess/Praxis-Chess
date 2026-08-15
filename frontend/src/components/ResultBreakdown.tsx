import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts'

interface Props {
  wins: number
  losses: number
  draws: number
}

export function ResultBreakdown({ wins, losses, draws }: Props) {
  const total = wins + losses + draws || 1
  const data = [
    { name: 'Wins',   value: wins,   color: '#B9D96C' },
    { name: 'Losses', value: losses, color: '#E2664A' },
    { name: 'Draws',  value: draws,  color: '#8A847E' },
  ].filter(d => d.value > 0)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
      <ResponsiveContainer width="100%" height={160}>
        <PieChart>
          <Pie data={data} cx="50%" cy="50%" innerRadius={45} outerRadius={70}
               dataKey="value" strokeWidth={0}>
            {data.map((d, i) => <Cell key={i} fill={d.color} />)}
          </Pie>
          <Tooltip
            contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 6, fontSize: 12 }}
            formatter={(v) => [`${v} (${Math.round((v as number) / total * 100)}%)`, '']}
          />
        </PieChart>
      </ResponsiveContainer>
      <div style={{ display: 'flex', gap: 16 }}>
        {data.map(d => (
          <div key={d.name} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.78rem' }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: d.color, flexShrink: 0 }} />
            <span style={{ color: 'var(--text-muted)' }}>{d.name}</span>
            <span style={{ fontWeight: 600 }}>{Math.round(d.value / total * 100)}%</span>
          </div>
        ))}
      </div>
    </div>
  )
}
