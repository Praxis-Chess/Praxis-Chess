import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { BackupResult, BackupSnapshot } from '../api/types'
import { Disclose } from './report/ReportParts'

const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.78rem', lineHeight: 1.5 }
const cell: React.CSSProperties = { padding: '4px 8px', borderTop: '1px solid var(--hairline)' }

const REASON: Record<string, string> = {
  daily: 'daily',
  manual: 'Back up now',
  'before-reanalysis': 'before re-analysis',
  'before-restore': 'before a restore',
  imported: 'older backup',
}

const SHOWN = 8

export function ago(iso: string | null): string {
  if (!iso) return 'never'
  const min = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000))
  if (min < 1) return 'just now'
  if (min < 60) return `${min} min ago`
  const h = Math.floor(min / 60)
  if (h < 48) return `${h} h ago`
  return `${Math.floor(h / 24)} days ago`
}

export function size(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`
}

function resultText(r: BackupResult): string {
  if (!r.ok) return r.error ?? 'No backup was taken.'
  if (r.unchanged) return `Nothing has changed since ${r.file}, so that snapshot already holds this data.`
  return `Saved ${r.file}.`
}

function Command({ label, text }: { label: string; text: string }) {
  const [copied, setCopied] = useState(false)
  const copy = () => {
    navigator.clipboard?.writeText(text)
      .then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 1500) })
      .catch(() => { /* the command stays selectable */ })
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <span style={muted}>{label}</span>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', minWidth: 0 }}>
        <code className="mono" style={{
          flex: 1, minWidth: 0, overflowX: 'auto', whiteSpace: 'nowrap', padding: '6px 8px',
          fontSize: '0.75rem', background: 'var(--surface-2, rgba(127,127,127,0.08))', borderRadius: 4,
        }}>{text}</code>
        <button className="secondary" onClick={copy} style={{ fontSize: '0.75rem', padding: '4px 10px' }}>
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
    </div>
  )
}

/**
 * Settings → Backups. The backup service takes the snapshots; this shows what
 * it has done, asks for one now, and gives the restore commands. Nothing here
 * deletes a snapshot.
 */
export function BackupsCard() {
  const qc = useQueryClient()
  const { data: b } = useQuery({ queryKey: ['backups'], queryFn: api.backups.get, refetchInterval: 60_000 })
  const [all, setAll] = useState(false)
  const [result, setResult] = useState<BackupResult | null>(null)

  const now = useMutation({
    mutationFn: api.backups.create,
    onSuccess: r => {
      setResult(r)
      qc.invalidateQueries({ queryKey: ['backups'] })
      qc.invalidateQueries({ queryKey: ['setup-status'] })
    },
    onError: e => setResult({ ok: false, file: null, unchanged: false, error: (e as Error).message }),
  })

  if (!b) return null
  const shown: BackupSnapshot[] = all ? b.snapshots : b.snapshots.slice(0, SHOWN)
  const target = b.latest?.file ?? 'praxis-YYYYMMDD-HHMMSS.sql.gz'
  const withFile = (c: string) => c.replace('{file}', target)
  const v = b.last_verification

  return (
    <section id="backups" className="card" aria-labelledby="backups-title">
      <h2 id="backups-title" style={{ fontSize: '1rem', marginBottom: 6 }}>Backups</h2>

      {!b.service_running ? (
        <div role="status" style={{
          margin: '4px 0 10px', padding: '10px 12px', borderLeft: '2px solid var(--warn)',
          background: 'rgba(229, 176, 75, 0.08)', ...muted,
        }}>
          {b.count === 0 ? 'No backups yet: ' : `The last backup was ${ago(b.last_check_at)}, and `}
          the backup service is not running. Start it with{' '}
          <span className="mono">{b.docker ? 'docker compose -f docker/compose.yaml up -d backup' : 'docker compose up -d backup'}</span>.
        </div>
      ) : (
        <p style={muted}>
          Last backup <strong>{ago(b.last_check_at)}</strong>
          {b.last_check_result === 'unchanged' && b.latest && <> (no change since {ago(b.latest.taken_at)})</>}
          {' · '}<span className="mono">{b.count}</span> snapshot{b.count === 1 ? '' : 's'}
          {' · '}<span className="mono">{size(b.total_bytes)}</span>
          {v && (v.ok
            ? <> · test restore passed {ago(v.verified_at)}</>
            : <> · <span style={{ color: 'var(--red)' }}>test restore failed: {v.error}</span></>)}
        </p>
      )}
      {b.service_running && b.last_check_result === 'error' && (
        <p role="alert" style={{ ...muted, color: 'var(--red)', marginTop: 6 }}>The last backup failed: {b.last_error}</p>
      )}

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginTop: 12 }}>
        <button className="secondary" onClick={() => { setResult(null); now.mutate() }}
                disabled={now.isPending || !b.service_running}>
          {now.isPending ? 'Backing up…' : 'Back up now'}
        </button>
        {result && (
          <span role={result.ok ? 'status' : 'alert'} style={{ ...muted, color: result.ok ? undefined : 'var(--red)' }}>
            {resultText(result)}
          </span>
        )}
      </div>

      <div style={{ marginTop: 10 }}>
        <Disclose summary="How backups work">
          Automatic once a day while Docker is running, and before every re-analysis. A day with no changes adds
          no file. Snapshots are never deleted, and they stay on this computer
          {b.dir && <>: <span className="mono">{b.dir}</span></>}.
        </Disclose>
      </div>

      {/* The status line above says how many, how big, how recent and whether a restore was
          tested; the list itself is one tap away. */}
      {b.snapshots.length > 0 && (
        <details data-disclose className="disclose" style={{ marginTop: 12 }}>
        <summary>Snapshots ({b.snapshots.length}), newest first</summary>
        <div style={{ overflowX: 'auto', marginTop: 8 }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.78rem' }}>
            <thead>
              <tr style={{ color: 'var(--text-secondary)', textAlign: 'left' }}>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500 }}>Taken</th>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500 }}>Why</th>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500, textAlign: 'right' }}>Size</th>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500, textAlign: 'right' }}>Games</th>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500 }}>Test restore</th>
              </tr>
            </thead>
            <tbody>
              {shown.map(s => (
                <tr key={s.file} data-scope={s.taken_at ? new Date(s.taken_at).toLocaleString() : s.file}>
                  <td style={cell} className="mono">
                    {s.taken_at ? new Date(s.taken_at).toLocaleString() : s.file}
                    {/* The file name was hover-only; the restore commands need it. */}
                    {s.taken_at && <div style={{ fontSize: '0.68rem', color: 'var(--text-tertiary)' }}>{s.file}</div>}
                  </td>
                  <td style={cell}>{REASON[s.reason] ?? s.reason}</td>
                  <td style={{ ...cell, textAlign: 'right' }} className="mono">{size(s.bytes)}</td>
                  <td style={{ ...cell, textAlign: 'right' }} className="mono">{s.games ?? '–'}</td>
                  <td style={cell}>{s.verified ? <span style={{ color: 'var(--green)' }}>✓ passed</span> : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {b.snapshots.length > SHOWN && (
            <button className="secondary" onClick={() => setAll(!all)} style={{ marginTop: 8, fontSize: '0.75rem' }}>
              {all ? 'Show fewer' : `Show all ${b.snapshots.length}`}
            </button>
          )}
        </div>
        </details>
      )}

      <details data-disclose className="disclose" style={{ marginTop: 10 }}>
        <summary>How to restore</summary>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 10 }}>
          <p style={muted}>
            Run from the Praxis folder. A restore first saves the current data, checks every table of the snapshot,
            and prints the command that undoes it.{' '}
            {b.docker ? 'The script stops and restarts the app.' : 'Stop the backend in IntelliJ first, and start it afterwards.'}
            {b.latest && <> These use the newest snapshot; put another file name in its place.</>}
          </p>
          <Command label="Replace the current data (Git Bash, macOS, Linux)" text={withFile(b.commands.restore)} />
          <Command label="Look at an old snapshot without touching the current data (database praxis_restore)"
                   text={withFile(b.commands.restore_side)} />
          <Command label={`Windows PowerShell${b.docker ? ' (stop the app first)' : ''}`}
                   text={withFile(b.commands.restore_direct)} />
          <Command label="List every snapshot" text={b.commands.list} />
        </div>
      </details>
    </section>
  )
}
