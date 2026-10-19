import { useState } from 'react'
import { praxInteract } from '../prax/PraxHost'
import { useLibraryActions } from '../hooks/useLibraryActions'
import { BackupFailedNotice } from './BackupFailedNotice'
import { SetupChecklist } from './SetupChecklist'
import { useHelp } from './report/ReportParts'

const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.78rem', lineHeight: 1.5 }

/**
 * Settings → Library: where the library stands, every sync and analysis action,
 * and the setup checklist folded into one line underneath.
 */
/** `primary`: Sync Now is the page's one solid button (Settings makes Save solid while the form has changes). */
export function LibraryCard({ primary = true }: { primary?: boolean }) {
  const lib = useLibraryActions()
  const { status, isBusy, pending, newGames } = lib
  const [confirmAll, setConfirmAll] = useState(false)
  const [syncHelp, syncPanel] = useHelp('syncing', <>
    <span style={{ display: 'block' }}>Fetch new games (this month, or the sync range below)</span>
    <span style={{ display: 'block' }}>Re-fetch the last three months and refresh accuracy data</span>
  </>)

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
        <button className={primary ? 'solid' : 'secondary'} disabled={isBusy} onClick={() => { praxInteract('SYNC_STARTED'); lib.sync.mutate() }}>
          {lib.sync.isPending ? 'Syncing…' : 'Sync Now'}
        </button>
        <button className="secondary" disabled={isBusy} onClick={() => { praxInteract('SYNC_STARTED'); lib.resync.mutate() }}>
          {lib.resync.isPending ? 'Re-syncing…' : '↻ Re-Sync'}
        </button>
        <button className="secondary" disabled={isBusy || pending === 0}
                onClick={() => { praxInteract('PRIMARY_ACTION'); lib.analyzePending.mutate() }}>
          {lib.analyzePending.isPending ? 'Queuing…' : `▶ Analyze Pending${pending > 0 ? ` (${pending})` : ''}`}
        </button>
        {!confirmAll ? (
          <button className="secondary" disabled={isBusy} onClick={() => setConfirmAll(true)}>
            {lib.reanalyzeAll.isPending ? 'Backing up, then queuing…' : '⟳ Re-analyze All'}
          </button>
        ) : (
          <>
            <span style={muted}>Re-analyses every game in range. Drill cards keep their history.</span>
            <button disabled={isBusy} onClick={() => { setConfirmAll(false); praxInteract('PRIMARY_ACTION'); lib.reanalyzeAll.mutate(false) }}>
              Yes, re-analyze all
            </button>
            <button className="secondary" onClick={() => setConfirmAll(false)}>Cancel</button>
          </>
        )}
        {syncHelp}
      </div>
      {syncPanel && <div style={{ marginTop: 6 }}>{syncPanel}</div>}
      <BackupFailedNotice error={lib.reanalyzeAll.error} busy={isBusy}
                          onProceed={() => lib.reanalyzeAll.mutate(true)} onCancel={() => lib.reanalyzeAll.reset()} />
      <SetupChecklist mode="inline" />
    </section>
  )
}
