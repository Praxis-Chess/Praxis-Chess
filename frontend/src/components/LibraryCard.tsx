import { useState } from 'react'
import { praxInteract } from '../prax/PraxHost'
import { useLibraryActions } from '../hooks/useLibraryActions'

const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.78rem', lineHeight: 1.5 }

/** Settings → Library: where the library stands, and every sync and analysis action. */
export function LibraryCard() {
  const lib = useLibraryActions()
  const { status, isBusy, pending, newGames } = lib
  const [confirmAll, setConfirmAll] = useState(false)

  const lastSync = !status ? '…'
    : status.last_synced_at === 'Never' ? 'never' : new Date(status.last_synced_at).toLocaleString()

  return (
    <section className="card" aria-labelledby="library-card-title">
      <h2 id="library-card-title" style={{ fontSize: '1rem', marginBottom: 6 }}>Library</h2>
      <p style={muted}>
        <span className="mono">{status?.games_analyzed ?? 0}</span> analyzed
        {pending > 0 && <> · <span className="mono" style={{ color: 'var(--yellow)' }}>{pending}</span> pending</>}
        {newGames > 0 && <> · <span className="mono" style={{ color: 'var(--orchid)' }}>{newGames}</span> new on Chess.com</>}
        {' · last sync '}{lastSync}
      </p>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 12, alignItems: 'center' }}>
        <button className="solid" disabled={isBusy} onClick={() => { praxInteract('SYNC_STARTED'); lib.sync.mutate() }}
                title="Fetch new games (this month, or the sync range below)">
          {lib.sync.isPending ? 'Syncing…' : 'Sync Now'}
        </button>
        <button className="secondary" disabled={isBusy} onClick={() => { praxInteract('SYNC_STARTED'); lib.resync.mutate() }}
                title="Re-fetch the last three months and refresh accuracy data">
          {lib.resync.isPending ? 'Re-syncing…' : '↻ Re-Sync'}
        </button>
        <button className="secondary" disabled={isBusy || pending === 0}
                onClick={() => { praxInteract('PRIMARY_ACTION'); lib.analyzePending.mutate() }}>
          {lib.analyzePending.isPending ? 'Queuing…' : `▶ Analyze Pending${pending > 0 ? ` (${pending})` : ''}`}
        </button>
        {!confirmAll ? (
          <button className="secondary" disabled={isBusy} onClick={() => setConfirmAll(true)}>⟳ Re-analyze All</button>
        ) : (
          <>
            <span style={muted}>Re-analyses every game in range and rebuilds its drill cards.</span>
            <button disabled={isBusy} onClick={() => { setConfirmAll(false); praxInteract('PRIMARY_ACTION'); lib.reanalyzeAll.mutate() }}>
              Yes, re-analyze all
            </button>
            <button className="secondary" onClick={() => setConfirmAll(false)}>Cancel</button>
          </>
        )}
      </div>
    </section>
  )
}
