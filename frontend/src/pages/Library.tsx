import { useState } from 'react'
import { Tabs, tabId, tabPanelId } from '../components/Tabs'
import { StaticBoard } from '../components/StaticBoard'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { GameSummary } from '../api/types'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { praxInteract } from '../prax/PraxHost'
import { dayMonth, drillLink } from '../components/report/ReportParts'

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

/** Synced on purpose but never analysed: a time control (or date) outside Settings' analysis scope. */
const outOfScope = (g: GameSummary) => g.analysis_status !== 'ANALYZED' && g.in_analysis_scope === false

function GameRow({ g }: { g: GameSummary }) {
  // One date format everywhere (principle 7): "6 Sep 2026", never the locale's "9/6/2026".
  const date = g.played_at ? `${dayMonth(new Date(g.played_at))} ${new Date(g.played_at).getFullYear()}` : '—'
  const resultColor = g.result === 'win' ? 'var(--gain)' : g.result === 'loss' ? 'var(--red)' : 'var(--text-muted)'
  return (
    <Link to={`/games/${g.id}`} style={{ textDecoration: 'none' }} onClick={() => praxInteract('GAME_SELECTED')}>
      <div className="row-link" style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 14px', cursor: 'pointer' }}>
        {outOfScope(g)
          ? <span title="Not analysed: outside your analysed time controls (Settings)" style={{
              width: 7, height: 7, borderRadius: '50%', border: '1px solid var(--text-muted)',
              display: 'inline-block', marginRight: 6, flexShrink: 0, boxSizing: 'border-box' }} />
          : statusDot(g.analysis_status)}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: '0.85rem', fontWeight: 500, color: 'var(--text)',
                        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {g.opening_name ?? g.opening_eco ?? 'Unknown opening'}
          </div>
          <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: 1 }}>
            {date} · {g.time_class} · {g.player_color}{outOfScope(g) && ' · not analysed'}
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

  // Games waiting for analysis float to the top. A game outside the analysis
  // scope (e.g. bullet, when only rapid is analysed) is not waiting for anything:
  // it stays in the main list, marked "not analysed".
  const unanalyzed = games.filter(g => g.analysis_status !== 'ANALYZED' && !outOfScope(g))
  const analyzed = games.filter(g => g.analysis_status === 'ANALYZED' || outOfScope(g))

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
        onChange={e => { setSearch(e.target.value); praxInteract('FILTER_CHANGED') }}
        style={{ width: '100%', maxWidth: 360, padding: '7px 12px', borderRadius: 6,
                 background: 'var(--surface-2)', border: '1px solid var(--hairline)',
                 color: 'var(--text)', fontSize: '0.85rem', marginBottom: 16, outline: 'none', boxSizing: 'border-box' }}
      />
      <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: 8 }}>
        {totalFiltered} game{totalFiltered !== 1 ? 's' : ''}
      </div>

      {/* Unanalysed games float to the top */}
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
        <Link to="/drills">Go to drill mode →</Link>
      </p>
      {/* U4: each drill is its position, so the list is a gallery of boards. */}
      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 12,
                   gridTemplateColumns: 'repeat(auto-fit, minmax(176px, 1fr))' }}>
        {drills.map(d => (
          <li key={d.id} aria-label={`Drill: ${d.move_played}`} className="card no-mark"
              style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'flex-start' }}>
            <StaticBoard fen={d.fen} size={152} />
            <span style={{ display: 'flex', gap: 8, alignItems: 'baseline', flexWrap: 'wrap', fontSize: '0.8rem' }}>
              <span style={{ fontSize: '0.68rem', fontWeight: 700, padding: '1px 6px', borderRadius: 10,
                             color: d.severity === 'BLUNDER' ? 'var(--red)' : d.severity === 'MISTAKE' ? 'var(--yellow)' : 'var(--text-muted)',
                             border: `1px solid ${d.severity === 'BLUNDER' ? 'var(--red)' : d.severity === 'MISTAKE' ? 'var(--yellow)' : 'var(--hairline)'}` }}>
                {d.severity}
              </span>
              <span className="mono">{d.move_played}</span>
            </span>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
              {d.game_phase?.toLowerCase() ?? '—'}
              {d.tactical_motif ? ` · ${d.tactical_motif.replace(/_/g, ' ').toLowerCase()}` : ''}
            </span>
            {d.game_id && (
              <span style={{ display: 'flex', gap: 12, fontSize: '0.75rem' }}>
                {d.ply != null && <Link to={drillLink([{ game_id: d.game_id, ply: d.ply }])}>Drill this</Link>}
                <Link to={`/games/${d.game_id}`}>View game</Link>
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}

export function Library() {
  const [tab, setTab] = useState<Tab>('games')

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* The nav names this page; the heading stays for screen readers and tests, not the eye. */}
      <h1 className="sr-only">Library</h1>

      <Tabs label="Library sections" idBase="library" active={tab}
            items={[{ key: 'games', label: 'Games' }, { key: 'drills', label: 'Drills' }]}
            onSelect={t => { setTab(t as Tab); praxInteract('SECONDARY_ACTION') }} />

      <div role="tabpanel" id={tabPanelId('library', tab)} aria-labelledby={tabId('library', tab)}>
        {tab === 'games' ? <GamesList /> : <DrillArchive />}
      </div>

      {/* Right of the list, vertically centred — Contract §4. */}
    </div>
  )
}
