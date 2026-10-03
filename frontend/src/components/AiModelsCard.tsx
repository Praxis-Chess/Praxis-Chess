import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.78rem', lineHeight: 1.5 }

/**
 * Settings → AI models: where each AI feature runs. Read-only by design: the
 * cloud provider and its API key are set in application.yml (praxis-chess.ai),
 * so a key never passes through the browser.
 */
export function AiModelsCard() {
  const { data: ai } = useQuery({ queryKey: ['settings-ai'], queryFn: api.settings.ai })
  if (!ai) return null

  const cloudFeatures = ai.features.filter(f => f.cloud)
  const waiting = ai.features.filter(f => f.requested && !f.cloud)

  return (
    <section className="card" aria-labelledby="ai-title">
      <h2 id="ai-title" style={{ fontSize: '1rem', marginBottom: 10 }}>
        AI models
        <span title="Ollama on this machine by default. A cloud provider is set in application.yml under praxis-chess.ai, per feature."
              style={{ marginLeft: 6, color: 'var(--text-tertiary)', cursor: 'help', fontWeight: 400 }}>ⓘ</span>
      </h2>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto', gap: '6px 16px', fontSize: '0.8rem' }}>
        {ai.features.map(f => (
          <div key={f.feature} style={{ display: 'contents' }}>
            <span style={{ minWidth: 0 }}>{f.label}</span>
            <span style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
              <span className="badge" style={{
                marginRight: 8,
                color: f.cloud ? 'var(--orchid)' : 'var(--text-muted)',
                border: `1px solid ${f.cloud ? 'var(--orchid)' : 'var(--border)'}`,
              }}>
                {f.cloud ? ai.cloud_host ?? 'cloud' : 'this laptop'}
              </span>
              <span className="mono" style={{ color: 'var(--text-secondary)' }}>{f.model ?? 'off'}</span>
            </span>
          </div>
        ))}
      </div>

      {cloudFeatures.length > 0 && (
        <p role="note" style={{
          ...muted, marginTop: 12, padding: '8px 12px',
          borderLeft: '2px solid var(--warn)', background: 'rgba(229, 176, 75, 0.08)',
        }}>
          {cloudFeatures.map(f => f.label.split(',')[0]).join(', ')} send your positions and game data to {ai.cloud_host}.
          {!ai.key_set && ' No API key is set.'}
        </p>
      )}
      {waiting.length > 0 && (
        <p role="note" style={{ ...muted, marginTop: 8, color: 'var(--yellow)' }}>
          Listed for the cloud but no provider is configured, so these run locally: {waiting.map(f => f.label.split(',')[0]).join(', ')}.
        </p>
      )}
    </section>
  )
}
