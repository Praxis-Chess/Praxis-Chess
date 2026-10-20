import { useNavigate } from 'react-router-dom'
import type { RecentGame } from '../api/types'

interface Props { games: RecentGame[] }

export function RecentGames({ games }: Props) {
  const navigate = useNavigate()

  if (!games || games.length === 0)
    return <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>No games yet.</p>

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {games.map(g => {
        const date = g.played_at ? new Date(g.played_at).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) : '—'
        const resColor = g.result === 'win' ? 'var(--green)' : g.result === 'loss' ? 'var(--red)' : 'var(--text-muted)'
        const resLabel = g.result === 'win' ? '1-0' : g.result === 'loss' ? '0-1' : '½-½'
        const opening  = (g.opening_name ?? g.opening_eco ?? 'Unknown').slice(0, 18)

        return (
          <div key={g.id} className="row-link"
               onClick={() => navigate(`/games/${g.id}`)}
               style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 8px', cursor: 'pointer' }}>
            <span style={{ fontWeight: 700, fontSize: '0.8rem', color: resColor, width: 28, flexShrink: 0 }}>{resLabel}</span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: '0.78rem', fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{opening}</div>
              <div style={{ fontSize: '0.68rem', color: 'var(--text-muted)' }}>
                {g.player_color} · {g.time_class}
              </div>
            </div>
            <div style={{ textAlign: 'right', flexShrink: 0 }}>
              {g.accuracy != null && (
                <div style={{ fontSize: '0.78rem', fontWeight: 600, color: 'var(--accent)' }}>{g.accuracy.toFixed(1)}%</div>
              )}
              <div style={{ fontSize: '0.68rem', color: 'var(--text-muted)' }}>{date}</div>
            </div>
          </div>
        )
      })}
    </div>
  )
}
