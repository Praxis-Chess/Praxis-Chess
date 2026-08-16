import { useState, useSyncExternalStore, useRef, useEffect, useLayoutEffect } from 'react'
import { praxInteract } from '../interaction/interactions'
import { praxRuntime } from '../state/runtime'

/** Tiny open/closed store so anything can raise the card without prop-drilling. */
type Listener = () => void
let open = false
const listeners = new Set<Listener>()
export const praxAsk = {
  open: () => {
    if (open) return
    open = true
    listeners.forEach((f) => f())
  },
  close: () => {
    if (!open) return
    open = false
    listeners.forEach((f) => f())
  },
  toggle: () => (open ? praxAsk.close() : praxAsk.open()),
  get: () => open,
  subscribe: (fn: Listener) => {
    listeners.add(fn)
    return () => listeners.delete(fn)
  },
}

interface Evidence {
  label: string
  value: string
  sample_size: number
  source: string
}
interface Step {
  tool: string
  sample_size: number
}
interface Answer {
  answer: string
  /** Verified chess statements, rendered by the backend. Never model prose. */
  findings: string[]
  evidence: Evidence[]
  steps: Step[]
  partial: boolean
}

/**
 * Asking Prax a question — the same card language as the progress and thought
 * cards, positioned by PraxStack rather than floating on its own.
 *
 * Deliberately not a chat transcript: one question, one grounded answer, the
 * tools it consulted, and only the evidence it could actually cite.
 */
export function PraxAsk() {
  const isOpen = useSyncExternalStore(praxAsk.subscribe, praxAsk.get)
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)
  const [res, setRes] = useState<Answer | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  /** Live request, so closing the card or pressing Stop can cancel it. */
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    if (isOpen) inputRef.current?.focus()
  }, [isOpen])

  // Closing the card abandons the question. Without this the fetch kept running,
  // Prax kept bristling, and the answer landed in a card nobody was looking at.
  useEffect(() => {
    if (!isOpen) abortRef.current?.abort()
  }, [isOpen])

  // Unmounting has to cancel too, or the request outlives the component.
  useEffect(() => () => abortRef.current?.abort(), [])

  // Grow the box to fit what has been typed. Reset to auto first, or the
  // scrollHeight read is the previous (larger) height and it only ever grows.
  useLayoutEffect(() => {
    const el = inputRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }, [q, isOpen])

  if (!isOpen) return null

  async function ask() {
    const question = q.trim()
    if (!question || busy) return
    praxInteract('PRIMARY_ACTION')
    // Prax should look like it is working for as long as it actually is —
    // the quills run until the response lands, however long that takes.
    praxRuntime.send({ type: 'QUERY_STARTED' })
    setBusy(true)
    setErr(null)
    setRes(null)
    const ctl = new AbortController()
    abortRef.current = ctl
    try {
      const r = await fetch('/api/prax/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
        signal: ctl.signal,
      })
      if (!r.ok) throw new Error(`Prax is unavailable (${r.status})`)
      setRes(await r.json())
    } catch (e) {
      // A cancelled request is a choice, not a failure — no error message.
      if (!(e instanceof DOMException && e.name === 'AbortError')) {
        setErr(e instanceof Error ? e.message : 'Request failed')
      }
    } finally {
      abortRef.current = null
      // In `finally` so a failed request settles the body too — otherwise Prax
      // would keep bristling over an error message.
      praxRuntime.send({ type: 'QUERY_FINISHED' })
      setBusy(false)
    }
  }

  /** Abandon the question in flight but keep the card open. */
  function stop() {
    praxInteract('SECONDARY_ACTION')
    abortRef.current?.abort()
  }

  return (
    <div
      style={{
        // Positioned by PraxStack — this card only styles itself.
        pointerEvents: 'auto',
        // --surface is rgba(255,255,255,0.035): a tint meant to sit ON the page.
        // This card floats OVER it, so the tint alone let charts and game rows
        // read straight through the text. Same tint, composited onto an opaque
        // base — identical appearance, no bleed-through.
        background: 'var(--canvas, #121110)',
        backgroundImage: 'linear-gradient(var(--surface, #141317), var(--surface, #141317))',
        border: '1px solid var(--hairline, #26232B)',
        borderLeft: '2px solid var(--orchid, #E7A6D6)',
        borderRadius: '0 6px 6px 0',
        padding: '13px 15px',
        animation: 'prax-thought-in 320ms cubic-bezier(0.2, 0.8, 0.3, 1)',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span
          style={{
            fontFamily: 'var(--font-mono, monospace)',
            fontSize: '0.64rem',
            letterSpacing: '0.12em',
            textTransform: 'uppercase',
            color: 'var(--orchid, #E7A6D6)',
          }}
        >
          Ask Prax
        </span>
        <button onClick={praxAsk.close} aria-label="Close" style={iconBtn}>
          ✕
        </button>
      </div>

      <div style={{ display: 'flex', gap: 6, marginTop: 11, alignItems: 'flex-start' }}>
        {/* A single-line input scrolled long questions out of sight behind the
            caret — you could not read back what you had typed. A textarea
            wraps and grows with the text instead, capped so it never takes
            over the card. Enter asks; Shift+Enter adds a line. */}
        <textarea
          ref={inputRef}
          value={q}
          rows={1}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              ask()
            }
          }}
          placeholder="Ask about your games…"
          style={{
            flex: 1,
            minWidth: 0,
            background: 'var(--surface-2, #1B1920)',
            border: '1px solid var(--hairline, #26232B)',
            color: 'var(--text, #EDEAF0)',
            borderRadius: 4,
            padding: '6px 9px',
            fontSize: '0.8rem',
            fontFamily: 'inherit',
            lineHeight: 1.45,
            outline: 'none',
            resize: 'none',
            overflowY: 'auto',
            maxHeight: 96,
            // Height is driven by content in the layout effect below.
            height: 'auto',
          }}
        />

        {/* Voice input is wired for V5 — the TTS pipeline exists, capture does not. */}
        <button
          disabled
          aria-label="Voice input (not yet available)"
          title="Voice input — coming soon"
          style={{
            background: 'transparent',
            border: '1px solid var(--hairline, #26232B)',
            color: 'var(--text-tertiary, #625C6D)',
            borderRadius: 4,
            padding: '4px 8px',
            fontSize: '0.8rem',
            cursor: 'not-allowed',
            opacity: 0.5,
          }}
        >
          🎙
        </button>

        {/* Only while a question is in flight — a permanent dead Stop would be
            one more disabled control in a row that already has one. */}
        {busy && (
          <button
            onClick={stop}
            aria-label="Stop this question"
            title="Stop"
            style={{
              background: 'transparent',
              border: '1px solid var(--hairline, #26232B)',
              color: 'var(--text-secondary, #B4AEBE)',
              borderRadius: 4,
              padding: '4px 10px',
              fontSize: '0.74rem',
              cursor: 'pointer',
              whiteSpace: 'nowrap',
            }}
          >
            Stop
          </button>
        )}

        <button
          onClick={ask}
          disabled={busy || !q.trim()}
          style={{
            background: 'transparent',
            border: '1px solid var(--orchid, #E7A6D6)',
            color: 'var(--orchid, #E7A6D6)',
            borderRadius: 4,
            padding: '4px 11px',
            fontSize: '0.74rem',
            cursor: busy || !q.trim() ? 'default' : 'pointer',
            opacity: busy || !q.trim() ? 0.4 : 1,
          }}
        >
          {busy ? '…' : 'Ask'}
        </button>
      </div>

      {busy && (
        <p style={{ margin: '11px 0 0', fontSize: '0.78rem', color: 'var(--text-muted, #8A8494)' }}>
          Checking your games.
        </p>
      )}

      {err && (
        <p style={{ margin: '11px 0 0', fontSize: '0.78rem', color: 'var(--loss, #E2664A)' }}>{err}</p>
      )}

      {res && (
        <div style={{ marginTop: 12 }}>
          {/* The verified account of the position, exactly as the engine and the
              board produced it. Above the prose because it is the answer — the
              model chose which of these appear but wrote none of them. */}
          {res.findings?.length > 0 && (
            <ul
              style={{
                margin: '0 0 11px',
                padding: '0 0 0 13px',
                listStyle: 'none',
                borderLeft: '2px solid var(--accent-soft, #E7A6D6)',
                display: 'flex',
                flexDirection: 'column',
                gap: 6,
              }}
            >
              {res.findings.map((f, i) => (
                <li
                  key={i}
                  style={{
                    fontSize: '0.83rem',
                    lineHeight: 1.5,
                    color: 'var(--text, #EDEAF0)',
                  }}
                >
                  {f}
                </li>
              ))}
            </ul>
          )}

          <p
            style={{
              margin: 0,
              fontSize: '0.83rem',
              lineHeight: 1.55,
              color: res.findings?.length > 0
                ? 'var(--text-secondary, #B4AEBE)'
                : 'var(--text, #EDEAF0)',
            }}
          >
            {res.answer}
          </p>

          {res.evidence.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginTop: 11 }}>
              {res.evidence.map((e, i) => (
                <div key={`${e.label}-${i}`} style={evRow}>
                  <span>
                    {e.label}
                    {/* The three claim classes must stay distinguishable.
                        PLAYER_DATA is the default and carries no tag; anything
                        else is marked, so an engine number is never mistaken
                        for something measured about the player. */}
                    {e.source === 'ENGINE' && <span style={tag}>engine</span>}
                    {e.source === 'KNOWLEDGE' && <span style={tag}>general</span>}
                  </span>
                  <span style={{ color: 'var(--text-secondary, #B4AEBE)' }}>{e.value}</span>
                </div>
              ))}
            </div>
          )}

          {/* What it actually consulted — investigating made visible. */}
          {res.steps.length > 0 && (
            <div
              style={{
                marginTop: 11,
                paddingTop: 9,
                borderTop: '1px solid var(--hairline, #26232B)',
                fontFamily: 'var(--font-mono, monospace)',
                fontSize: '0.62rem',
                color: 'var(--text-tertiary, #625C6D)',
              }}
            >
              {res.steps.map((s, i) => (
                <div key={i}>
                  {s.tool} · {s.sample_size}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

const iconBtn: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: 'var(--text-muted, #8A8494)',
  cursor: 'pointer',
  fontSize: '0.8rem',
  padding: 0,
  lineHeight: 1,
}

const tag: React.CSSProperties = {
  marginLeft: 6,
  fontSize: '0.58rem',
  letterSpacing: '0.09em',
  textTransform: 'uppercase',
  color: 'var(--text-tertiary, #625C6D)',
  border: '1px solid var(--hairline, #26232B)',
  borderRadius: 2,
  padding: '0 4px',
}

const evRow: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  gap: 12,
  fontFamily: 'var(--font-mono, monospace)',
  fontSize: '0.68rem',
  color: 'var(--text-muted, #8A8494)',
}
