import { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { useAnalysisProgress } from '../hooks/useAnalysisProgress'
import type { AnalysisStatus, GameSummary } from '../api/types'
import { LoadingSpinner } from '../components/LoadingSpinner'

type FilterKey = 'wins' | 'losses' | 'blunders' | 'white' | 'black' | 'rapid' | 'bullet' | 'blitz' | 'last30'

const CHIPS: { key: FilterKey; label: string }[] = [
  { key: 'wins',    label: 'Wins' },
  { key: 'losses',  label: 'Losses' },
  { key: 'blunders',label: 'Has Mistakes' },
  { key: 'white',   label: 'White' },
  { key: 'black',   label: 'Black' },
  { key: 'rapid',   label: 'Rapid' },
  { key: 'blitz',   label: 'Blitz' },
  { key: 'bullet',  label: 'Bullet' },
  { key: 'last30',  label: 'Last 30 days' },
]

function resultBadge(result: string) {
  return <span className={`badge badge-${result}`}>{result.toUpperCase()}</span>
}

function statusBadge(status: string) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{status}</span>
}

function applyFilter(games: GameSummary[], active: Set<FilterKey>): GameSummary[] {
  if (active.size === 0) return games
  return games.filter(g => {
    if (active.has('wins')    && g.result !== 'win')    return false
    if (active.has('losses')  && g.result !== 'loss')   return false
    if (active.has('blunders')&& g.mistake_count === 0) return false
    if (active.has('white')   && g.player_color !== 'white') return false
    if (active.has('black')   && g.player_color !== 'black') return false
    if (active.has('rapid')   && g.time_class !== 'rapid')   return false
    if (active.has('blitz')   && g.time_class !== 'blitz')   return false
    if (active.has('bullet')  && g.time_class !== 'bullet')  return false
    if (active.has('last30')) {
      const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000
      if (!g.played_at || new Date(g.played_at).getTime() < cutoff) return false
    }
    return true
  })
}

const STATUS_ORDER: Record<AnalysisStatus, number> = {
  ANALYZING: 0, ANALYZED: 1, PENDING: 2, FAILED: 3,
}

export function GameList() {
  const navigate = useNavigate()
  const [activeFilters, setActiveFilters] = useState<Set<FilterKey>>(new Set())
  const { data: progress } = useAnalysisProgress()

  const { data: games, isLoading } = useQuery({
    queryKey: ['games'],
    queryFn: api.games.list,
    refetchInterval: progress?.running ? 4000 : false,
  })

  const toggleFilter = (key: FilterKey) => {
    setActiveFilters(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const displayed = useMemo(() => {
    if (!games) return []
    let list = [...games]

    // While analysis runs, sort ANALYZING/ANALYZED games to top
    if (progress?.running) {
      list.sort((a, b) => {
        const sa = STATUS_ORDER[a.analysis_status] ?? 99
        const sb = STATUS_ORDER[b.analysis_status] ?? 99
        if (sa !== sb) return sa - sb
        return new Date(b.played_at).getTime() - new Date(a.played_at).getTime()
      })
    }

    return applyFilter(list, activeFilters)
  }, [games, activeFilters, progress?.running])

  if (isLoading) return <LoadingSpinner label="Loading games…" />
  if (!games?.length) return <p style={{ color: 'var(--text-muted)' }}>No games synced yet. Use "Sync Now" above.</p>

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 16 }}>
        <h1 style={{ fontSize: '1.3rem', fontWeight: 700 }}>
          Games{' '}
          <span style={{ color: 'var(--text-muted)', fontWeight: 400, fontSize: '1rem' }}>
            ({displayed.length}{displayed.length !== games.length ? ` of ${games.length}` : ''})
          </span>
        </h1>
      </div>

      {/* Filter chips */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 14 }}>
        {CHIPS.map(({ key, label }) => {
          const on = activeFilters.has(key)
          return (
            <button
              key={key}
              onClick={() => toggleFilter(key)}
              style={{
                padding: '4px 12px',
                fontSize: '0.75rem',
                borderRadius: 20,
                border: `1px solid ${on ? 'var(--accent)' : 'var(--border)'}`,
                background: on ? 'var(--accent-dim)' : 'var(--surface)',
                color: on ? 'var(--accent)' : 'var(--text-muted)',
                cursor: 'pointer',
                fontWeight: on ? 600 : 400,
                transition: 'all 0.15s',
              }}
            >
              {label}
            </button>
          )
        })}
        {activeFilters.size > 0 && (
          <button
            onClick={() => setActiveFilters(new Set())}
            style={{
              padding: '4px 12px',
              fontSize: '0.75rem',
              borderRadius: 20,
              border: '1px solid var(--border)',
              background: 'transparent',
              color: 'var(--text-muted)',
              cursor: 'pointer',
            }}
          >
            Clear
          </button>
        )}
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid var(--border)', background: 'var(--surface-2)' }}>
              {['Date', 'Color', 'Result', 'Opening', 'Time', 'Rating', 'Accuracy', 'Mistakes', 'Status'].map((h) => (
                <th key={h} style={{
                  padding: '10px 16px', textAlign: 'left', fontSize: '0.75rem',
                  color: 'var(--text-muted)', fontWeight: 600,
                  textTransform: 'uppercase', letterSpacing: '0.05em',
                }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {displayed.map((g: GameSummary) => (
              <tr
                key={g.id}
                onClick={() => g.analysis_status === 'ANALYZED' && navigate(`/games/${g.id}`)}
                style={{
                  borderBottom: '1px solid var(--border)',
                  cursor: g.analysis_status === 'ANALYZED' ? 'pointer' : 'default',
                  transition: 'background 0.1s',
                  opacity: g.analysis_status === 'ANALYZING' ? 0.7 : 1,
                }}
                onMouseEnter={(e) => {
                  if (g.analysis_status === 'ANALYZED')
                    (e.currentTarget as HTMLElement).style.background = 'var(--surface-2)'
                }}
                onMouseLeave={(e) => {
                  (e.currentTarget as HTMLElement).style.background = ''
                }}
              >
                <td style={{ padding: '10px 16px', fontSize: '0.82rem', color: 'var(--text-muted)' }}>
                  {g.played_at ? new Date(g.played_at).toLocaleDateString() : '—'}
                </td>
                <td style={{ padding: '10px 16px', fontSize: '0.82rem', textTransform: 'capitalize' }}>
                  {g.player_color}
                </td>
                <td style={{ padding: '10px 16px' }}>{resultBadge(g.result)}</td>
                <td style={{ padding: '10px 16px', fontSize: '0.82rem', color: 'var(--text-muted)', maxWidth: 200 }}>
                  {g.opening_eco ? `${g.opening_eco}${g.opening_name ? ' · ' + g.opening_name : ''}` : '—'}
                </td>
                <td style={{ padding: '10px 16px', fontSize: '0.82rem', color: 'var(--text-muted)', textTransform: 'capitalize' }}>
                  {g.time_class ?? '—'}
                </td>
                <td style={{ padding: '10px 16px', fontSize: '0.82rem' }}>{g.player_rating || '—'}</td>
                <td style={{ padding: '10px 16px', fontSize: '0.82rem', color: g.accuracy != null ? 'var(--accent)' : 'var(--text-muted)' }}>
                  {g.accuracy != null ? `${g.accuracy}%` : '—'}
                </td>
                <td style={{ padding: '10px 16px', fontSize: '0.82rem' }}>
                  {g.analysis_status === 'ANALYZED' && g.mistake_count > 0
                    ? <span style={{ color: g.mistake_count >= 3 ? 'var(--red)' : 'var(--yellow)', fontWeight: 600 }}>
                        {g.mistake_count}
                      </span>
                    : <span style={{ color: 'var(--text-muted)' }}>—</span>
                  }
                </td>
                <td style={{ padding: '10px 16px' }}>{statusBadge(g.analysis_status)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
