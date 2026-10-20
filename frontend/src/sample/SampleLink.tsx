import { enterSample, exitSample, sampleAvailable, sampleOn } from './sampleMode'

/** "Explore with sample games →": absent when this build has no sample library, or the sample is already on. */
export function SampleLink({ label = 'Explore with sample games →' }: { label?: string }) {
  if (!sampleAvailable || sampleOn()) return null
  return (
    <button type="button" className="link" onClick={enterSample} style={{ fontSize: '0.75rem' }}>
      {label}
    </button>
  )
}

/**
 * While the sample is on, one slab under the nav says so on every page and is
 * the way out. A `role="status"`, not an alert: nothing went wrong.
 */
export function SampleBanner() {
  if (!sampleOn()) return null
  return (
    <div role="status" aria-label="Sample games" className="glass"
         style={{ margin: '12px auto 0', maxWidth: 'var(--page-standard)', width: 'calc(100% - 48px)', padding: '10px 16px',
                  display: 'flex', gap: 16, alignItems: 'baseline', flexWrap: 'wrap', fontSize: '0.82rem' }}>
      <strong style={{ fontWeight: 600 }}>Sample games</strong>
      <span style={{ color: 'var(--text-secondary)' }}>A made-up player's games, analysed by Stockfish. Nothing here is yours, and nothing is saved.</span>
      <button type="button" className="secondary" onClick={exitSample} style={{ marginLeft: 'auto', padding: '4px 12px', fontSize: '0.78rem' }}>
        Exit sample
      </button>
    </div>
  )
}
