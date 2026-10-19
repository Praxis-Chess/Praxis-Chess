import { BackupFailedError } from '../api/client'

const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.78rem', lineHeight: 1.5 }

/**
 * Re-analysis takes a backup first, because it deletes analyses and drill
 * history. When none could be taken, nothing was deleted: say why and let the
 * player decide. Renders nothing for any other error.
 */
export function BackupFailedNotice({ error, onProceed, onCancel, busy }: {
  error: unknown; onProceed: () => void; onCancel: () => void; busy?: boolean
}) {
  if (!(error instanceof BackupFailedError)) return null
  return (
    <div role="alert" style={{
      marginTop: 10, padding: '10px 12px', borderLeft: '2px solid var(--warn)',
      background: 'rgba(229, 176, 75, 0.08)', display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      <span style={muted}>
        {error.message} Nothing has been changed. Re-analysis rewrites the analysis of many games, so a backup
        is taken first.
      </span>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <button className="secondary" onClick={onProceed} disabled={busy}>Re-analyse without a backup</button>
        <button className="secondary" onClick={onCancel}>Cancel</button>
      </div>
    </div>
  )
}
