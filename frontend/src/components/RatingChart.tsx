import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts'
import type { RatingPoint } from '../api/types'

interface Props {
  data: RatingPoint[]
  currentRating?: number
  ratingDelta?: number
}

export function RatingChart({ data, currentRating, ratingDelta }: Props) {
  const formatted = data.map((p) => ({
    date: new Date(p.date).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
    rating: p.rating,
  }))

  const deltaColor = (ratingDelta ?? 0) >= 0 ? 'var(--green)' : 'var(--red)'
  const deltaLabel = ratingDelta != null ? `${ratingDelta >= 0 ? '+' : ''}${ratingDelta}` : null

  // Derived stats from the rating series
  const deltas = data.length > 1
    ? data.slice(1).map((p, i) => p.rating - data[i].rating)
    : []
  const positiveDeltas = deltas.filter(d => d > 0)
  const negativeDeltas = deltas.filter(d => d < 0)
  const highestClimb = positiveDeltas.length ? Math.max(...positiveDeltas) : 0
  const worstSlump   = negativeDeltas.length ? Math.min(...negativeDeltas) : 0

  const firstDate = data.length > 1 ? new Date(data[0].date).getTime() : null
  const lastDate  = data.length > 1 ? new Date(data[data.length - 1].date).getTime() : null
  const weeks = firstDate && lastDate ? (lastDate - firstDate) / (7 * 24 * 60 * 60 * 1000) : 0
  const avgPerWeek = weeks > 0 && data.length > 1
    ? Math.round((data[data.length - 1].rating - data[0].rating) / weeks)
    : null

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 10 }}>
        {currentRating != null && currentRating > 0 && (
          <>
            <span className="stat-value" style={{ fontSize: '1.75rem', fontWeight: 500, letterSpacing: '-0.02em' }}>{currentRating}</span>
            {deltaLabel && (
              <span className="stat-value" style={{ fontSize: '0.85rem', fontWeight: 500, color: deltaColor }}>{deltaLabel}</span>
            )}
          </>
        )}
        <span className="micro-label" style={{ marginLeft: 'auto' }}>Current Rating</span>
      </div>

      <ResponsiveContainer width="100%" height={155}>
        <AreaChart data={formatted} margin={{ top: 4, right: 8, left: -20, bottom: 0 }}>
          <defs>
            <linearGradient id="ratingFade" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%"   stopColor="#E7A6D6" stopOpacity={0.12} />
              <stop offset="100%" stopColor="#E7A6D6" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="2 2" stroke="var(--hairline)" vertical={false} />
          <XAxis dataKey="date" tick={{ fill: 'var(--text-tertiary)', fontSize: 10 }} tickLine={false} axisLine={{ stroke: 'var(--hairline)' }} interval="preserveStartEnd" />
          <YAxis tick={{ fill: 'var(--text-tertiary)', fontSize: 10 }} tickLine={false} axisLine={false} domain={['auto', 'auto']} />
          <Tooltip
            contentStyle={{ background: 'rgba(18,17,16,0.92)', border: '1px solid var(--hairline-lit)', borderRadius: 3 }}
            labelStyle={{ color: 'var(--text-tertiary)', fontSize: 10, letterSpacing: '0.14em', textTransform: 'uppercase' }}
            itemStyle={{ color: 'var(--orchid)', fontWeight: 500 }}
          />
          <Area type="monotone" dataKey="rating" stroke="var(--orchid)" strokeWidth={1.25}
                fill="url(#ratingFade)" dot={false} activeDot={{ r: 2.5, fill: 'var(--orchid)', stroke: 'none' }} />
        </AreaChart>
      </ResponsiveContainer>

      {/* Derived stat chips */}
      {deltas.length > 0 && (
        <div style={{ display: 'flex', gap: 10, marginTop: 10 }}>
          {highestClimb > 0 && (
            <div style={{ flex: 1, border: '1px solid var(--hairline)', borderRadius: 3, padding: '7px 10px', textAlign: 'center' }}>
              <div className="stat-value" style={{ fontSize: '0.95rem', fontWeight: 500, color: 'var(--gain)' }}>+{highestClimb}</div>
              <div className="micro-label" style={{ marginTop: 4 }}>Best climb</div>
            </div>
          )}
          {worstSlump < 0 && (
            <div style={{ flex: 1, border: '1px solid var(--hairline)', borderRadius: 3, padding: '7px 10px', textAlign: 'center' }}>
              <div className="stat-value" style={{ fontSize: '0.95rem', fontWeight: 500, color: 'var(--loss)' }}>{worstSlump}</div>
              <div className="micro-label" style={{ marginTop: 4 }}>Worst slump</div>
            </div>
          )}
          {avgPerWeek !== null && (
            <div style={{ flex: 1, border: '1px solid var(--hairline)', borderRadius: 3, padding: '7px 10px', textAlign: 'center' }}>
              <div className="stat-value" style={{ fontSize: '0.95rem', fontWeight: 500, color: avgPerWeek >= 0 ? 'var(--orchid)' : 'var(--text-secondary)' }}>
                {avgPerWeek >= 0 ? '+' : ''}{avgPerWeek}
              </div>
              <div className="micro-label" style={{ marginTop: 4 }}>Avg / week</div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
