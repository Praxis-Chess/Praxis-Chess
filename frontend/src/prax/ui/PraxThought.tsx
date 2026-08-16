import { useSyncExternalStore, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { praxRuntime } from '../state/runtime'
import { praxInteract } from '../interaction/interactions'
import { praxSpeak, stopSpeaking, praxVoiceAvailable, isPraxSpeaking } from '../voice'
import { praxThoughts } from './thoughts'

/**
 * Contract §6 — the canvas is pointer-events:none permanently, so Prax cannot
 * be clicked through it. Interaction lives here, in the DOM, positioned to
 * track Prax's projected screen position.
 *
 * Contract §10 — this is the ONE place Prax reaches back into the app, and it
 * does so as a React component calling navigate(), not through the event bus.
 */
export function PraxThought() {
  const snap = useSyncExternalStore(praxRuntime.subscribe, praxRuntime.getSnapshot)
  const navigate = useNavigate()
  // Speaking state lives outside React; this only forces the label to re-read it.
  const [, setTick] = useState(0)

  const content = praxThoughts.get(snap.insightId)
  const visible = snap.state === 'insight' || snap.state === 'speaking'

  if (!visible || !content) return null

  return (
    <div
      style={{
        // Positioned by PraxStack — this card only styles itself.
        pointerEvents: 'auto',
        // Opaque base under the tint — this card floats over page content.
        background: 'var(--canvas, #121110)',
        backgroundImage: 'linear-gradient(var(--surface, #141317), var(--surface, #141317))',
        border: '1px solid var(--hairline, #26232B)',
        borderLeft: '2px solid var(--orchid, #E7A6D6)',
        borderRadius: '0 6px 6px 0',
        padding: '14px 16px',
        animation: 'prax-thought-in 320ms cubic-bezier(0.2, 0.8, 0.3, 1)',
      }}
      onMouseEnter={() => praxRuntime.setPresence('engaged')}
      onMouseLeave={() => praxRuntime.setPresence('focused')}
    >
      <p
        style={{
          margin: 0,
          fontSize: '0.86rem',
          lineHeight: 1.55,
          color: 'var(--text, #EDEAF0)',
        }}
      >
        {content.text}
      </p>

      {content.evidence && content.evidence.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginTop: 10 }}>
          {content.evidence.map((e) => (
            <div
              key={e.label}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                gap: 12,
                fontFamily: 'var(--font-mono, monospace)',
                fontSize: '0.7rem',
                color: 'var(--text-muted, #8A8494)',
              }}
            >
              <span>{e.label}</span>
              <span style={{ color: 'var(--text-secondary, #B4AEBE)' }}>{e.value}</span>
            </div>
          ))}
        </div>
      )}

      <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
        {praxVoiceAvailable() && (
          <button
            onClick={() => {
              if (isPraxSpeaking()) {
                stopSpeaking()
              } else {
                // Called inside the click handler so the AudioContext resume
                // satisfies the browser's autoplay policy.
                const lines = [content.text, ...(content.evidence ?? []).map((e) => `${e.label}: ${e.value}`)]
                void praxSpeak(lines.join('. '))
              }
              setTick((t) => t + 1)
            }}
            style={{
              background: 'transparent',
              border: '1px solid var(--hairline, #26232B)',
              color: 'var(--text-secondary, #B4AEBE)',
              borderRadius: 4,
              padding: '4px 10px',
              fontSize: '0.74rem',
              cursor: 'pointer',
            }}
          >
            {isPraxSpeaking() ? 'Stop' : 'Listen'}
          </button>
        )}
        {content.examineHref && (
          <button
            onClick={() => {
              praxInteract('EXAMINE')
              navigate(content.examineHref!)
            }}
            style={{
              background: 'transparent',
              border: '1px solid var(--orchid, #E7A6D6)',
              color: 'var(--orchid, #E7A6D6)',
              borderRadius: 4,
              padding: '4px 10px',
              fontSize: '0.74rem',
              cursor: 'pointer',
            }}
          >
            Examine
          </button>
        )}
        <button
          onClick={() => praxInteract('DISMISS', { insightId: snap.insightId ?? '' })}
          style={{
            background: 'transparent',
            border: '1px solid var(--hairline, #26232B)',
            color: 'var(--text-muted, #8A8494)',
            borderRadius: 4,
            padding: '4px 10px',
            fontSize: '0.74rem',
            cursor: 'pointer',
          }}
        >
          Dismiss
        </button>
      </div>
    </div>
  )
}
