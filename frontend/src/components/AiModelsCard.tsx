import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { useHelp } from './report/ReportParts'

const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.78rem', lineHeight: 1.5 }

/**
 * Settings → AI models: where each AI feature runs. Read-only by design: the
 * cloud provider and its API key are set in application.yml (praxis-chess.ai),
 * so a key never passes through the browser.
 */
export function AiModelsCard() {
  const { data: ai } = useQuery({ queryKey: ['settings-ai'], queryFn: api.settings.ai })
  const [help, helpPanel] = useHelp('AI models',
    'Ollama on this machine by default. A cloud provider is set in application.yml under praxis-chess.ai, per feature.')
  if (!ai) return null

  const cloudFeatures = ai.features.filter(f => f.cloud)
  const waiting = ai.features.filter(f => f.requested && !f.cloud)
  // The trained model is the one feature a user cannot pull by name from Ollama:
  // without this line, Settings is a dead end that just says "off".
  const commentaryOff = ai.features.some(f => f.feature === 'commentary' && !f.model)

  return (
    <section className="card" aria-labelledby="ai-title">
      <h2 id="ai-title" style={{ fontSize: 'var(--fs-section)', marginBottom: 10 }}>
        AI models{help}
      </h2>
      {helpPanel && <div style={{ marginTop: -4, marginBottom: 10 }}>{helpPanel}</div>}

      {/* Three columns, so the badges line up whatever the model name's length:
          feature | where (left-aligned) | model (left-aligned). */}
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto auto', gap: '6px 12px',
                    alignItems: 'center', fontSize: '0.8rem' }}>
        {ai.features.map(f => (
          <div key={f.feature} style={{ display: 'contents' }}>
            <span style={{ minWidth: 0 }}>{f.label}</span>
            <span className="badge" style={{
              justifySelf: 'start', whiteSpace: 'nowrap',
              color: f.cloud ? 'var(--orchid)' : 'var(--text-muted)',
              border: `1px solid ${f.cloud ? 'var(--orchid)' : 'var(--border)'}`,
            }}>
              {f.cloud ? ai.cloud_host ?? 'cloud' : 'this laptop'}
            </span>
            <span className="mono" style={{ color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>{f.model ?? 'off'}</span>
          </div>
        ))}
      </div>

      {commentaryOff && (
        <p style={{ ...muted, marginTop: 12 }}>
          Checked commentary is off. The trained model is on{' '}
          <a href="https://huggingface.co/praxis-chess/praxis-chess-reasoner-qwen3.5-2b-GGUF"
             target="_blank" rel="noopener noreferrer">Hugging Face</a>
          ; set it up with step 3b of the README, then <span className="mono">commentary-model</span> in application.yml.
        </p>
      )}
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
