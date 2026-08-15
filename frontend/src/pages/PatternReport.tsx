import { usePatternReport } from '../hooks/usePatternReport'
import { PatternHeatmap } from '../components/PatternHeatmap'
import { LoadingSpinner } from '../components/LoadingSpinner'

export function PatternReport() {
  const { data: pattern, isLoading } = usePatternReport()

  if (isLoading) return <LoadingSpinner label="Analysing patterns…" />
  if (!pattern) {
    return (
      <div>
        <h1 style={{ fontSize: '1.3rem', fontWeight: 700, marginBottom: 12 }}>Pattern Report</h1>
        <p style={{ color: 'var(--text-muted)' }}>No pattern data yet. Sync and analyze your games first.</p>
      </div>
    )
  }

  const motifData: Record<string, number> = pattern.motif_frequency ? JSON.parse(pattern.motif_frequency) : {}

  const totalMistakes = pattern.mistakes_opening + pattern.mistakes_middlegame + pattern.mistakes_endgame
  const gamesAnalyzed = pattern.games_analyzed || 1
  const totalMotifCount = Object.values(motifData).reduce((s, v) => s + v, 0) || 1

  const phaseData = [
    { phase: 'Opening',    count: pattern.mistakes_opening,    color: 'var(--green)' },
    { phase: 'Middlegame', count: pattern.mistakes_middlegame, color: 'var(--yellow)' },
    { phase: 'Endgame',    count: pattern.mistakes_endgame,    color: 'var(--red)' },
  ]

  const sortedMotifs = Object.entries(motifData).sort(([, a], [, b]) => b - a)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
        <h1 style={{ fontSize: '1.3rem', fontWeight: 700 }}>Pattern Report</h1>
        <span style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
          {pattern.games_analyzed} games · {totalMistakes} mistakes flagged
        </span>
      </div>

      {/* AI-generated weaknesses — ranked by severity */}
      {pattern.primary_weakness && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 12 }}>
          {[
            { rank: 1, label: 'Primary Weakness',   text: pattern.primary_weakness,   color: 'var(--red)' },
            { rank: 2, label: 'Secondary Weakness',  text: pattern.secondary_weakness, color: 'var(--yellow)' },
            { rank: 3, label: 'Tertiary Weakness',   text: pattern.tertiary_weakness,  color: 'var(--accent)' },
          ].filter(w => w.text).map(({ rank, label, text, color }) => (
            <div key={rank} className="card" style={{ borderLeft: `2px solid ${color}` }}>
              <div style={{ fontSize: '0.7rem', color, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 8 }}>
                #{rank} {label}
              </div>
              <p style={{ fontSize: '0.85rem', lineHeight: 1.6 }}>{text}</p>
            </div>
          ))}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
        {/* Mistake heatmap by move range */}
        <div className="card">
          <h2 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: 16 }}>
            Mistakes by Move Range
          </h2>
          <PatternHeatmap pattern={pattern} />
          {pattern.critical_move_range && (
            <p style={{ marginTop: 10, fontSize: '0.8rem', color: 'var(--orange)' }}>
              Critical zone: <strong>{pattern.critical_move_range}</strong>
            </p>
          )}
        </div>

        {/* Phase breakdown */}
        <div className="card">
          <h2 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: 16 }}>
            Mistakes by Game Phase
          </h2>
          {phaseData.map(({ phase, count, color }) => {
            const pct = Math.round((count / Math.max(1, totalMistakes)) * 100)
            const perGame = gamesAnalyzed > 0 ? (count / gamesAnalyzed).toFixed(1) : '0'
            return (
              <div key={phase} style={{ marginBottom: 14 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4, fontSize: '0.82rem' }}>
                  <span>{phase}</span>
                  <span style={{ color: 'var(--text-muted)' }}>
                    {count} ({pct}%) · <span style={{ color: 'var(--text-muted)', fontSize: '0.78rem' }}>{perGame}/game</span>
                  </span>
                </div>
                <div style={{ height: 8, background: 'var(--surface-2)', borderRadius: 4, overflow: 'hidden' }}>
                  <div style={{ height: '100%', width: `${pct}%`, background: color, borderRadius: 4 }} />
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* Tactical motif ranking */}
      {sortedMotifs.length > 0 && (
        <div className="card">
          <h2 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: 16 }}>
            Tactical Motifs — Ranked by Frequency
            {pattern.dominant_motif && (
              <span style={{ marginLeft: 10, color: 'var(--orange)', fontWeight: 400 }}>
                dominant: {pattern.dominant_motif.replace(/_/g, ' ')}
              </span>
            )}
          </h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))', gap: 10 }}>
            {sortedMotifs.map(([motif, count], i) => {
              const pctOfErrors = Math.round((count / Math.max(1, totalMistakes)) * 100)
              const barPct = Math.round((count / totalMotifCount) * 100)
              return (
                <div key={motif} className="card" style={{ padding: '10px 14px' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 6 }}>
                    <span style={{
                      fontSize: '0.62rem', color: 'var(--text-muted)',
                      background: 'var(--surface-2)', borderRadius: 10,
                      padding: '1px 6px', fontWeight: 600,
                    }}>#{i + 1}</span>
                    <span style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--yellow)' }}>{count}</span>
                  </div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text)', fontWeight: 500 }}>
                    {motif.replace(/_/g, ' ')}
                  </div>
                  <div style={{ fontSize: '0.68rem', color: 'var(--text-muted)', marginTop: 4 }}>
                    {pctOfErrors}% of flagged errors
                  </div>
                  <div style={{ height: 3, background: 'var(--surface-2)', borderRadius: 2, marginTop: 6, overflow: 'hidden' }}>
                    <div style={{ height: '100%', width: `${barPct}%`, background: 'var(--yellow)', borderRadius: 2 }} />
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {/* Opening assessment */}
      {pattern.opening_assessment && (
        <div className="card">
          <h2 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: 8 }}>
            Opening Assessment
          </h2>
          <p style={{ fontSize: '0.85rem', lineHeight: 1.6 }}>{pattern.opening_assessment}</p>
        </div>
      )}
    </div>
  )
}
