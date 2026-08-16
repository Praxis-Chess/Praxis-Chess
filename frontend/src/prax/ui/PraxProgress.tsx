import { useSyncExternalStore } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { narrationStore } from '../state/narrationStore'
import { markStopping, markStopped } from '../../hooks/useAnalysisProgress'
import { praxInteract } from '../interaction/interactions'
import { praxAsk } from './PraxAsk'

/**
 * Prax's running commentary during a long operation.
 *
 * A status from the organism, not a chat window. It replaces itself as work
 * proceeds, offers a way out, and disappears when there is nothing left to say.
 * Every line comes from the narrator, which reads application state — the model
 * is not involved until there is something real to interpret.
 */
export function PraxProgress() {
  const n = useSyncExternalStore(narrationStore.subscribe, narrationStore.get)
  const navigate = useNavigate()

  if (!n) return null

  const pct = n.total > 0 ? Math.round((n.completed / n.total) * 100) : 0
  const showProgress = n.kind === 'operational' && n.total > 0 && !n.stopping

  async function stop() {
    praxInteract('SECONDARY_ACTION')
    markStopping(n!.completed, n!.total)
    try {
      const r = await api.analysis.stop()
      markStopped(r.completed, r.total)
    } catch {
      /* the poll will catch up regardless */
    }
  }

  return (
    <div
      style={{
        // Positioned by PraxStack — this card only styles itself.
        pointerEvents: 'auto',
        // Opaque base under the tint — this card floats over page content.
        background: 'var(--canvas, #121110)',
        backgroundImage: 'linear-gradient(var(--surface, #141317), var(--surface, #141317))',
        border: '1px solid var(--hairline, #26232B)',
        borderLeft: `2px solid ${n.kind === 'resolved' ? 'var(--orchid, #E7A6D6)' : 'var(--hairline-lit, #3a3540)'}`,
        borderRadius: '0 6px 6px 0',
        padding: '13px 15px',
        animation: 'prax-thought-in 320ms cubic-bezier(0.2, 0.8, 0.3, 1)',
      }}
    >
      <p style={{ margin: 0, fontSize: '0.84rem', lineHeight: 1.5, color: 'var(--text, #EDEAF0)' }}>
        {n.text}
      </p>

      {showProgress && (
        <div style={{ marginTop: 10 }}>
          <div
            style={{
              height: 2,
              background: 'var(--surface-2, #1B1920)',
              borderRadius: 1,
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                height: '100%',
                width: `${pct}%`,
                background: 'var(--orchid, #E7A6D6)',
                transition: 'width 0.5s ease',
              }}
            />
          </div>
          <div
            style={{
              fontFamily: 'var(--font-mono, monospace)',
              fontSize: '0.68rem',
              color: 'var(--text-muted, #8A8494)',
              marginTop: 5,
            }}
          >
            {n.completed} / {n.total}
          </div>
        </div>
      )}

      {n.evidence && n.evidence.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginTop: 10 }}>
          {n.evidence.map((e) => (
            <div
              key={e.label}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                gap: 12,
                fontFamily: 'var(--font-mono, monospace)',
                fontSize: '0.68rem',
                color: 'var(--text-muted, #8A8494)',
              }}
            >
              <span>{e.label}</span>
              <span style={{ color: 'var(--text-secondary, #B4AEBE)' }}>{e.value}</span>
            </div>
          ))}
        </div>
      )}

      <div style={{ display: 'flex', gap: 7, marginTop: 12 }}>
        {/* Interruptible, not conversational by default. */}
        {showProgress && (
          <button onClick={stop} style={btn(false)}>
            Stop
          </button>
        )}
        {n.examineHref && (
          <button
            onClick={() => {
              praxInteract('EXAMINE')
              navigate(n.examineHref!)
            }}
            style={btn(true)}
          >
            Examine
          </button>
        )}
        {/* Available while working, without disrupting the run. */}
        <button
          onClick={() => {
            praxInteract('SECONDARY_ACTION')
            praxAsk.open()
          }}
          style={btn(false)}
        >
          Ask Prax
        </button>
      </div>
    </div>
  )
}

const btn = (accent: boolean): React.CSSProperties => ({
  background: 'transparent',
  border: `1px solid ${accent ? 'var(--orchid, #E7A6D6)' : 'var(--hairline, #26232B)'}`,
  color: accent ? 'var(--orchid, #E7A6D6)' : 'var(--text-muted, #8A8494)',
  borderRadius: 4,
  padding: '3px 9px',
  fontSize: '0.72rem',
  cursor: 'pointer',
})
