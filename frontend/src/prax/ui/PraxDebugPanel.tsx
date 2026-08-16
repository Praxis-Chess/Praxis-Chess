import { useState, useSyncExternalStore } from 'react'
import { praxBus } from '../core/events'
import { praxRuntime } from '../state/runtime'
import { STATE_TARGETS, type PraxState } from '../motion/model'

const STATES: PraxState[] = ['dormant', 'aware', 'thinking', 'insight', 'speaking']
const PARAMS = ['energy', 'turbulence', 'coherence', 'breathing', 'expansion'] as const

/**
 * DEV only. Contract §9 Phase 2 — every number in the target table gets tuned
 * here, in isolation, before a single real event exists. Kept permanently:
 * it is wanted every time the shader is touched.
 */
export function PraxDebugPanel() {
  const snap = useSyncExternalStore(praxRuntime.subscribe, praxRuntime.getSnapshot)
  const [open, setOpen] = useState(false)
  const [overrides, setOverrides] = useState<Partial<Record<(typeof PARAMS)[number], number>>>({})

  if (!import.meta.env.DEV) return null

  const base = STATE_TARGETS[snap.state]

  return (
    <div
      style={{
        position: 'fixed',
        left: 12,
        bottom: 12,
        zIndex: 200,
        fontFamily: 'var(--font-mono, monospace)',
        fontSize: '0.7rem',
        background: 'rgba(10,9,12,0.92)',
        border: '1px solid var(--hairline, #26232B)',
        borderRadius: 6,
        padding: open ? '10px 12px' : '6px 10px',
        color: 'var(--text-muted, #8A8494)',
        minWidth: open ? 250 : undefined,
        backdropFilter: 'blur(6px)',
      }}
    >
      <button
        onClick={() => setOpen((o) => !o)}
        style={{
          background: 'transparent',
          border: 'none',
          color: 'var(--orchid, #E7A6D6)',
          cursor: 'pointer',
          padding: 0,
          font: 'inherit',
          letterSpacing: '0.08em',
        }}
      >
        prax · {snap.state} · {snap.presence} {open ? '▾' : '▸'}
      </button>

      {open && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 10 }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
            {STATES.map((s) => (
              <button
                key={s}
                onClick={() => {
                  setOverrides({})
                  praxRuntime.motion.setState(s)
                  praxRuntime.send({ type: 'NAVIGATION_START', from: '', to: '' })
                  if (s !== 'dormant') forceState(s)
                }}
                style={btn(snap.state === s)}
              >
                {s}
              </button>
            ))}
          </div>

          <div style={{ display: 'flex', gap: 4 }}>
            <button onClick={() => praxRuntime.motion.fireInsight(1)} style={btn(false)}>
              insight!
            </button>
            <button onClick={() => praxBus.emit({ type: 'DRILL_CORRECT' })} style={btn(false)}>
              correct
            </button>
            <button onClick={() => praxBus.emit({ type: 'DRILL_WRONG' })} style={btn(false)}>
              wrong
            </button>
          </div>

          {PARAMS.map((k) => {
            const v = overrides[k] ?? base[k]
            return (
              <label key={k} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ width: 74 }}>{k}</span>
                <input
                  type="range"
                  min={k === 'expansion' ? -1 : 0}
                  max={1}
                  step={0.01}
                  value={v}
                  onChange={(e) => {
                    const nv = parseFloat(e.target.value)
                    setOverrides((o) => ({ ...o, [k]: nv }))
                    praxRuntime.motion.setTarget(k, nv)
                  }}
                  style={{ flex: 1, accentColor: 'var(--orchid, #E7A6D6)' }}
                />
                <span style={{ width: 34, textAlign: 'right', color: 'var(--text, #EDEAF0)' }}>
                  {v.toFixed(2)}
                </span>
              </label>
            )
          })}
        </div>
      )}
    </div>
  )
}

/** Drives the FSM into a state directly, for isolated tuning. */
function forceState(s: PraxState) {
  if (s === 'aware') praxBus.emit({ type: 'USER_FOCUS', target: 'insight' })
  else if (s === 'thinking') praxBus.emit({ type: 'ANALYSIS_STARTED' })
  else if (s === 'insight')
    praxBus.emit({ type: 'INSIGHT_FOUND', insightId: 'debug', confidence: 1, importance: 'high' })
  else if (s === 'speaking') {
    praxBus.emit({ type: 'INSIGHT_FOUND', insightId: 'debug', confidence: 1, importance: 'high' })
    praxBus.emit({ type: 'RESPONSE_STARTED' })
  }
}

const btn = (active: boolean): React.CSSProperties => ({
  background: active ? 'rgba(231,166,214,0.16)' : 'transparent',
  border: `1px solid ${active ? 'var(--orchid, #E7A6D6)' : 'var(--hairline, #26232B)'}`,
  color: active ? 'var(--orchid, #E7A6D6)' : 'var(--text-muted, #8A8494)',
  borderRadius: 4,
  padding: '2px 7px',
  cursor: 'pointer',
  font: 'inherit',
})
