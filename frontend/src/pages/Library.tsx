import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { GameSummary } from '../api/types'
import { LoadingSpinner } from '../components/LoadingSpinner'

type Tab = 'games' | 'drills'

function statusDot(status: string) {
  const colors: Record<string, string> = {
    ANALYZED: 'var(--gain)', ANALYZING: 'var(--yellow)', PENDING: 'var(--text-muted)', FAILED: 'var(--red)',
  }
  return (
    <span style={{ width: 7, height: 7, borderRadius: '50%', background: colors[status] ?? 'var(--text-muted)',
                   display: 'inline-block', marginRight: 6, flexShrink: 0 }} />
  )
}

function GameRow({ g }: { g: GameSummary }) {
  const date = g.played_at ? new Date(g.played_at).toLocaleDateString() : '—'
  const resultColor = g.result === 'win' ? 'var(--gain)' : g.result === 'loss' ? 'var(--red)' : 'var(--text-muted)'
  return (
    <Link to={`/games/${g.id}`} style={{ textDecoration: 'none' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 14px',
                    borderRadius: 6, cursor: 'pointer', transition: 'background 0.12s' }}
           onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
           onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
        {statusDot(g.analysis_status)}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: '0.85rem', fontWeight: 500, color: 'var(--text)',
                        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {g.opening_name ?? g.opening_eco ?? 'Unknown opening'}
          </div>
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: 1 }}>
            {date} · {g.time_class} · {g.player_color}
          </div>
        </div>
        <div style={{ textAlign: 'right', flexShrink: 0 }}>
          <div style={{ fontWeight: 700, color: resultColor, fontSize: '0.85rem', textTransform: 'capitalize' }}>
            {g.result}
          </div>
          {g.accuracy != null && (
            <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
              {g.accuracy}% accuracy
            </div>
          )}
        </div>
      </div>
    </Link>
  )
}

function GamesList() {
  const { data: games, isLoading } = useQuery({
    queryKey: ['games'],
    queryFn: () => api.games.list(),
    staleTime: 30_000,
  })
  const [search, setSearch] = useState('')

  if (isLoading) return <LoadingSpinner label="Loading games…" />
  if (!games?.length) return <p style={{ color: 'var(--text-muted)' }}>No games yet. Sync to import.</p>

  // Separate unanalyzed games (needs attention) from the rest
  const unanalyzed = games.filter(g => g.analysis_status !== 'ANALYZED')
  const analyzed = games.filter(g => g.analysis_status === 'ANALYZED')

  const matchSearch = (g: GameSummary) => !search ||
    (g.opening_name ?? '').toLowerCase().includes(search.toLowerCase()) ||
    (g.opening_eco  ?? '').toLowerCase().includes(search.toLowerCase()) ||
    g.result.toLowerCase().includes(search.toLowerCase())

  const filteredUnanalyzed = unanalyzed.filter(matchSearch)
  const filteredAnalyzed   = analyzed.filter(matchSearch)
  const totalFiltered = filteredUnanalyzed.length + filteredAnalyzed.length

  return (
    <div>
      <input
        type="text"
        placeholder="Filter by opening, result…"
        value={search}
        onChange={e => setSearch(e.target.value)}
        style={{ width: '100%', maxWidth: 360, padding: '7px 12px', borderRadius: 6,
                 background: 'var(--surface-2)', border: '1px solid var(--hairline)',
                 color: 'var(--text)', fontSize: '0.85rem', marginBottom: 16, outline: 'none', boxSizing: 'border-box' }}
      />
      <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: 8 }}>
        {totalFiltered} game{totalFiltered !== 1 ? 's' : ''}
      </div>

      {/* Unanalyzed games float to the top */}
      {filteredUnanalyzed.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <div style={{
            fontSize: '0.7rem', fontWeight: 600, textTransform: 'uppercase',
            letterSpacing: '0.06em', color: 'var(--yellow)',
            padding: '4px 0', marginBottom: 4, borderBottom: '1px solid var(--hairline)',
          }}>
            Needs analysis ({filteredUnanalyzed.length})
          </div>
          {filteredUnanalyzed.map(g => <GameRow key={g.id} g={g} />)}
        </div>
      )}

      {filteredAnalyzed.map(g => <GameRow key={g.id} g={g} />)}
    </div>
  )
}

function DrillArchive() {
  const { data: drills, isLoading } = useQuery({
    queryKey: ['drills'],
    queryFn: () => api.drills.list(50),
    staleTime: 60_000,
  })
  if (isLoading) return <LoadingSpinner label="Loading games…" />
  if (!drills?.length) return (
    <p style={{ color: 'var(--text-muted)' }}>
      No drills yet. Analyse games to generate puzzles from your mistakes.
    </p>
  )
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 4 }}>
        {drills.length} drill{drills.length !== 1 ? 's' : ''} from your analysed games.{' '}
        <Link to="/drills" style={{ color: 'var(--orchid)' }}>Go to drill mode →</Link>
      </p>
      {drills.map(d => (
        <div key={d.id} className="card" style={{ padding: '10px 14px', display: 'flex',
                                                   alignItems: 'center', gap: 12 }}>
          <span style={{ fontSize: '0.72rem', fontWeight: 700, padding: '2px 7px', borderRadius: 10,
                         color: d.severity === 'BLUNDER' ? 'var(--red)' : d.severity === 'MISTAKE' ? 'var(--yellow)' : 'var(--text-muted)',
                         border: `1px solid ${d.severity === 'BLUNDER' ? 'var(--red)' : d.severity === 'MISTAKE' ? 'var(--yellow)' : 'var(--hairline)'}` }}>
            {d.severity}
          </span>
          <div style={{ flex: 1, fontSize: '0.82rem', color: 'var(--text-muted)' }}>
            Move {d.move_played} · {d.game_phase?.toLowerCase() ?? '—'}
            {d.tactical_motif ? ` · ${d.tactical_motif.replace(/_/g, ' ').toLowerCase()}` : ''}
          </div>
          {d.game_id && (
            <Link to={`/games/${d.game_id}`} style={{ fontSize: '0.75rem', color: 'var(--orchid)' }}>
              View game
            </Link>
          )}
        </div>
      ))}
    </div>
  )
}

export function Library() {
  const [tab, setTab] = useState<Tab>('games')

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20, maxWidth: 720 }}>
      <div>
        <h1 style={{ fontSize: '1.35rem', fontWeight: 700, letterSpacing: '-0.02em', marginBottom: 4 }}>
          Library
        </h1>
        <p style={{ fontSize: '0.82rem', color: 'var(--text-muted)' }}>
          All your games and drill archive
        </p>
      </div>

      <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--hairline)', paddingBottom: 0 }}>
        {(['games', 'drills'] as Tab[]).map(t => (
          <button
            key={t}
            onClick={() => setTab(t)}
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              padding: '8px 16px', fontSize: '0.85rem', fontWeight: tab === t ? 600 : 400,
              color: tab === t ? 'var(--orchid)' : 'var(--text-muted)',
              borderBottom: tab === t ? '2px solid var(--orchid)' : '2px solid transparent',
              textTransform: 'capitalize',
            }}
          >
            {t}
          </button>
        ))}
      </div>

      <div>
        {tab === 'games' ? <GamesList /> : <DrillArchive />}
      </div>
    </div>
  )
}
