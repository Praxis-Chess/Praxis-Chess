import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { SetupCheck, SetupModel, SetupState } from '../api/types'

const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.78rem', lineHeight: 1.5 }

const MARK: Record<SetupState, { glyph: string; color: string; label: string }> = {
  OK: { glyph: '✓', color: 'var(--green)', label: 'ready' },
  WARN: { glyph: '!', color: 'var(--yellow)', label: 'needs attention' },
  FAIL: { glyph: '✕', color: 'var(--red)', label: 'missing' },
  SKIP: { glyph: '–', color: 'var(--text-tertiary)', label: 'off' },
}

const BUSY = new Set(['downloading', 'creating', 'waiting'])

function gb(bytes: number): string {
  return (bytes / 1e9).toFixed(bytes >= 1e10 ? 0 : 1)
}

/**
 * What this install needs and what is missing — the same checks the backend
 * logs at startup, shown where a new user will see them.
 *
 * "attention" (Today): renders only when something needs doing, so a working
 * install never sees it. "full" (Settings): always, as the place to look.
 * Polls every 2 s while the Docker models service is downloading, so the bars
 * move; otherwise every 30 s.
 */
export function SetupChecklist({ mode }: { mode: 'attention' | 'full' }) {
  const { data: s } = useQuery({
    queryKey: ['setup-status'],
    queryFn: api.setup.status,
    refetchInterval: q => (q.state.data?.models.some(m => BUSY.has(m.state)) ? 2000 : 30_000),
  })
  if (!s) return null

  const modelsMissing = s.models.some(m => m.state !== 'present')
  const needsAttention = !s.ready || modelsMissing || s.checks.some(c => c.state === 'WARN')
  if (mode === 'attention' && !needsAttention) return null

  const downloading = s.models.filter(m => BUSY.has(m.state))
  const title = !s.ready ? 'Finish setting up Praxis'
    : downloading.length ? 'Downloading AI models'
    : needsAttention ? 'Almost ready' : 'Setup'

  return (
    <section className="card" aria-labelledby={`setup-title-${mode}`}
             style={mode === 'attention' ? { maxWidth: 540, marginBottom: 28 } : undefined}>
      <h2 id={`setup-title-${mode}`} style={{ fontSize: '1rem', marginBottom: 10 }}>{title}</h2>

      <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
        {s.checks.filter(c => mode === 'full' || c.state !== 'SKIP').map(c => (
          <CheckRow key={c.key} c={c} />
        ))}
      </ul>

      {s.models.length > 0 && (mode === 'full' || modelsMissing) && (
        <div style={{ marginTop: 14, borderTop: '1px solid var(--hairline)', paddingTop: 12 }}>
          <div style={{ ...muted, fontWeight: 600, marginBottom: 8 }}>AI models</div>
          <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
            {s.models.map(m => <ModelRow key={m.name} m={m} />)}
          </ul>
          {modelsMissing && (
            <p style={{ ...muted, marginTop: 10 }}>
              Syncing and engine analysis work already; explanations, reports and Ask Prax wait for their model.
            </p>
          )}
        </div>
      )}
    </section>
  )
}

function CheckRow({ c }: { c: SetupCheck }) {
  const mark = MARK[c.state]
  return (
    <li style={{ display: 'grid', gridTemplateColumns: '18px minmax(0, 1fr)', gap: 8, fontSize: '0.82rem' }}>
      <span aria-label={mark.label} style={{ color: mark.color, fontWeight: 700, textAlign: 'center' }}>{mark.glyph}</span>
      <div style={{ minWidth: 0 }}>
        <span style={{ fontWeight: 600 }}>{c.label}</span>
        {c.detail && <span style={{ color: 'var(--text-secondary)' }}> — {c.detail}</span>}
        {c.fix && (
          <div style={{ ...muted, marginTop: 2, overflowWrap: 'anywhere' }}>
            {c.key === 'username'
              ? <Link to="/settings#player" style={{ color: 'var(--orchid)' }}>Enter it in Settings →</Link>
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
                        background: 'var(--orchid)', transition: 'width 0.6s ease' }} />
        </div>
      )}
      {m.state === 'failed' && m.message && (
        <div style={{ ...muted, color: 'var(--red)', marginTop: 2 }}>{m.message}</div>
      )}
    </li>
  )
}
