import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { SetupCheck, SetupModel, SetupState, SetupStatus } from '../api/types'

const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.78rem', lineHeight: 1.5 }

const MARK: Record<SetupState, { glyph: string; color: string; label: string }> = {
  OK: { glyph: '✓', color: 'var(--green)', label: 'ready' },
  WARN: { glyph: '…', color: 'var(--yellow)', label: 'needs attention' },
  FAIL: { glyph: '✕', color: 'var(--red)', label: 'missing' },
  SKIP: { glyph: '–', color: 'var(--text-tertiary)', label: 'off' },
}

const BUSY = new Set(['downloading', 'creating', 'waiting'])

function gb(bytes: number): string {
  return (bytes / 1e9).toFixed(bytes >= 1e10 ? 0 : 1)
}

/**
 * What this install needs and what is missing — the same checks the backend
 * logs at startup.
 *
 * "attention" (Today): a card, only while something blocks the app (a FAIL,
 * such as no username yet) or models are downloading. Warnings alone (voice
 * off, no web research) never put a card on Today.
 * "inline" (Settings → Library, under Sync Now): one summary line that expands
 * to every check and model.
 * Polls every 2 s while the Docker models service is downloading, so the bars
 * move; otherwise every 30 s.
 */
export function SetupChecklist({ mode }: { mode: 'attention' | 'inline' }) {
  const { data: s } = useQuery({
    queryKey: ['setup-status'],
    queryFn: api.setup.status,
    refetchInterval: q => (q.state.data?.models.some(m => BUSY.has(m.state)) ? 2000 : 30_000),
  })
  const [open, setOpen] = useState(false)
  if (!s) return null

  const downloading = s.models.filter(m => BUSY.has(m.state))

  if (mode === 'attention') {
    if (s.ready && downloading.length === 0) return null
    const title = !s.ready ? 'Finish setting up Praxis' : 'Downloading AI models'
    return (
      <section className="card" aria-labelledby="setup-title-attention" style={{ maxWidth: 540, marginBottom: 28 }}>
        <h2 id="setup-title-attention" style={{ fontSize: 'var(--fs-section)', marginBottom: 10 }}>{title}</h2>
        <ChecklistBody s={s} hideSkipped readyFolded modelsFolded={!s.ready} />
      </section>
    )
  }

  const count = (st: SetupState) => s.checks.filter(c => c.state === st).length
  const fail = count('FAIL'), warn = count('WARN'), ok = count('OK'), off = count('SKIP')
  const summary = [
    fail > 0 && `${fail} missing`,
    warn > 0 && `${warn} need${warn === 1 ? 's' : ''} attention`,
    `${ok} ready`,
    off > 0 && `${off} off`,
  ].filter(Boolean).join(' · ')
  const mark = MARK[fail > 0 ? 'FAIL' : warn > 0 ? 'WARN' : 'OK']

  return (
    <div style={{ marginTop: 12, borderTop: '1px solid var(--hairline)', paddingTop: 10 }}>
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} aria-controls="setup-details"
              style={{
                all: 'unset', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8,
                fontSize: '0.8rem', color: 'var(--text-secondary)',
              }}>
        <span aria-hidden="true" style={{ color: mark.color, fontWeight: 700, width: 14, textAlign: 'center' }}>{mark.glyph}</span>
        <span><span style={{ fontWeight: 600, color: 'var(--text)' }}>Setup</span> · {summary}</span>
        <span aria-hidden="true" style={{ fontSize: '0.7rem' }}>{open ? '▴' : '▾'}</span>
      </button>
      {open && (
        <div id="setup-details" role="region" aria-label="Setup" style={{ marginTop: 12 }}>
          <ChecklistBody s={s} />
        </div>
      )}
    </div>
  )
}

function ChecklistBody({ s, hideSkipped, readyFolded, modelsFolded }: {
  s: SetupStatus; hideSkipped?: boolean; readyFolded?: boolean
  /** While something else blocks (no username), the model list is one tap away under its own check. */
  modelsFolded?: boolean
}) {
  const modelsMissing = s.models.some(m => m.state !== 'present')
  const [showReady, setShowReady] = useState(false)
  const [showModels, setShowModels] = useState(false)
  const checks = s.checks.filter(c => !hideSkipped || c.state !== 'SKIP')
  // On Today, what blocks comes first; what is ready is one tap away.
  const ready = readyFolded ? checks.filter(c => c.state === 'OK') : []
  const shown = readyFolded && !showReady ? checks.filter(c => c.state !== 'OK') : checks
  return (
    <>
      <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
        {shown.map(c => <CheckRow key={c.key} c={c} />)}
      </ul>
      {ready.length > 0 && (
        <button type="button" data-disclose aria-expanded={showReady} onClick={() => setShowReady(!showReady)}
                className="link" style={{ marginTop: 10 }}>
          {showReady ? 'Hide the ready ones' : `${ready.length} ready ▾`}
        </button>
      )}

      {s.models.length > 0 && modelsFolded && (
        <button type="button" data-disclose aria-expanded={showModels} onClick={() => setShowModels(!showModels)}
                className="link" style={{ marginTop: 10, marginLeft: 14 }}>
          {showModels ? 'Hide models' : `Models (${s.models.length}) ▾`}
        </button>
      )}
      {s.models.length > 0 && (!modelsFolded || showModels) && (
        <div style={{ marginTop: 14, borderTop: '1px solid var(--hairline)', paddingTop: 12 }}>
          <div style={{ ...muted, fontWeight: 600, marginBottom: 8 }}>AI models</div>
          <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
            {s.models.map(m => <ModelRow key={m.name} m={m} />)}
          </ul>
          {modelsMissing && (
            <p style={{ ...muted, marginTop: 10 }}>
              Engine analysis works now; AI features wait for their model.
            </p>
          )}
        </div>
      )}
    </>
  )
}

function CheckRow({ c }: { c: SetupCheck }) {
  const mark = MARK[c.state]
  return (
    <li style={{ display: 'grid', gridTemplateColumns: '18px minmax(0, 1fr)', gap: 8, fontSize: '0.82rem' }}>
      <span aria-label={mark.label} style={{ color: mark.color, fontWeight: 700, textAlign: 'center' }}>{mark.glyph}</span>
      <div style={{ minWidth: 0 }}>
        <span style={{ fontWeight: 600 }}>{c.label}</span>
        {c.detail && <span style={{ color: 'var(--text-secondary)' }}> · {c.detail}</span>}
        {c.fix && (
          <div style={{ ...muted, marginTop: 2, overflowWrap: 'anywhere' }}>
            {c.key === 'username'
              ? <Link to="/settings#player">Enter it in Settings →</Link>
              : <span className="mono" style={{ userSelect: 'all' }}>{c.fix}</span>}
          </div>
        )}
      </div>
    </li>
  )
}

function ModelRow({ m }: { m: SetupModel }) {
  const pct = m.total > 0 ? Math.min(100, Math.round((m.completed / m.total) * 100)) : null
  const label = m.state === 'present' ? 'ready'
    : m.state === 'downloading' ? (pct != null ? `${gb(m.completed)} / ${gb(m.total)} GB` : 'downloading')
    : m.state === 'creating' ? 'building'
    : m.state === 'waiting' ? 'queued'
    : m.state === 'failed' ? 'failed'
    : 'not downloaded'
  const color = m.state === 'present' ? 'var(--green)' : m.state === 'failed' ? 'var(--red)' : 'var(--text-secondary)'

  return (
    <li style={{ fontSize: '0.8rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <span style={{ minWidth: 0 }}>
          <span className="mono">{m.name}</span>
          <span style={{ color: 'var(--text-tertiary)' }}> · {m.role}</span>
        </span>
        <span className="mono" style={{ color, whiteSpace: 'nowrap' }}>{label}</span>
      </div>
      {(m.state === 'downloading' || m.state === 'creating') && (
        <div role="progressbar" aria-label={`${m.name} download`} aria-valuemin={0} aria-valuemax={100}
             aria-valuenow={pct ?? undefined}
             style={{ height: 4, borderRadius: 2, background: 'var(--hairline)', marginTop: 5, overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${m.state === 'creating' ? 100 : pct ?? 0}%`,
                        background: 'var(--grey-1)', transition: 'width var(--dur-fill) var(--ease-out)' }} />
        </div>
      )}
      {m.state === 'failed' && m.message && (
        <div style={{ ...muted, color: 'var(--red)', marginTop: 2 }}>{m.message}</div>
      )}
    </li>
  )
}
