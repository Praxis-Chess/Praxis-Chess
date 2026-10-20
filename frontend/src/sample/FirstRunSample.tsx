import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { SampleLink } from './SampleLink'
import { sampleAvailable, sampleOn } from './sampleMode'

/**
 * Today before any game is synced: one line offering the sample library, so a
 * first run has something to look at while the real games come in. Absent once
 * there are games, or when this build has no sample.
 */
export function FirstRunSample() {
  const show = sampleAvailable && !sampleOn()
  const { data } = useQuery({ queryKey: ['dashboard-stats'], queryFn: api.dashboard.stats, enabled: show })
  if (!show || !data || data.total_games > 0) return null
  return (
    <p style={{ margin: '0 0 20px', fontSize: '0.82rem', color: 'var(--text-secondary)', display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'baseline' }}>
      Your games take a while to sync and analyse. Meanwhile: <SampleLink />
    </p>
  )
}
