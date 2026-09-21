import { useCallback, useEffect, useRef, useState } from 'react'
import { PraxAnchor } from '../prax/PraxHost'
import { praxRuntime } from '../prax/state/runtime'
import { ArtifactList } from '../prax/artifacts/ArtifactView'
import type { PraxArtifact } from '../prax/artifacts/types'
import { useWorkingWord, effortLine } from '../prax/ui/workingWords'

/**
 * Ask Prax — the workspace.
 *
 * The floating card answers a question. This answers a conversation, and gives
 * the answer somewhere to BE: a board is unreadable at 300px in a popup and
 * obvious at 400px beside the prose that describes it.
 *
 * The card is not replaced. Two entry points, one reasoning layer — a quick ask
 * anywhere, and this when the answer deserves room.
 */

interface Evidence { label: string; value: string; sample_size: number; source: string }
interface Step { tool: string; sample_size: number }
interface Source { title: string; domain: string; url: string }

interface Answer {
  answer: string
  findings: string[]
  evidence: Evidence[]
  steps: Step[]
  partial: boolean
  sources?: Source[]
  lane?: string
  grounding?: string
  artifacts?: PraxArtifact[]
  /** The thread this answer belongs to. Sent back to continue it. */
  conversation_id?: string
}

interface ConversationSummary {
  id: string
  title: string
  last_message_at: string | null
}

interface Turn {
  id: number
  question: string
  answer: Answer | null
  error: string | null
  /**
   * What Prax has done SO FAR on this question.
   *
   * The point of the whole progressive path: a board can be on screen the
   * moment analyze_position returns, well before the prose is written. The most
   * useful part of the answer arrives first, which inverts the usual chat feel.
   */
  liveSteps?: Step[]
  liveArtifacts?: PraxArtifact[]
}

interface RunView {
  run_id: string
  status: 'RUNNING' | 'DONE' | 'FAILED'
  steps: Step[]
  artifacts: PraxArtifact[]
  answer: Answer | null
  error: string | null
  elapsed_ms: number
}

/** Fast enough to feel live, slow enough not to hammer a busy backend. */
const POLL_MS = 700
/** A run that never terminates must not poll forever. */
const MAX_POLLS = 400

const SUGGESTIONS = [
  'What is my weakness?',
  'Which openings should I drill?',
  'Where do I blunder most?',
]

/**
 * Watches a run until it finishes.
 *
 * Polling rather than SSE, deliberately: it matches how analysis progress is
 * already followed in this app, and it survives a dropped connection — a
 * reconnecting client simply asks again, where an SSE stream would have to be
 * re-established and its missed events reconciled.
 */
async function pollRun(
  runId: string,
  signal: AbortSignal,
  onProgress: (view: RunView) => void,
): Promise<Answer> {
  for (let i = 0; i < MAX_POLLS; i++) {
    if (signal.aborted) throw new DOMException('aborted', 'AbortError')
    await new Promise(r => setTimeout(r, POLL_MS))
    if (signal.aborted) throw new DOMException('aborted', 'AbortError')

    const r = await fetch(`/api/prax/run/${runId}`, { signal })
    if (r.status === 404) {
      // The run was evicted, so no answer is coming. Say so rather than
      // polling an id that will never resolve.
      throw new Error('That answer is no longer available. Ask again.')
    }
    if (!r.ok) throw new Error(`Prax is unavailable (${r.status})`)

    const view: RunView = await r.json()
    onProgress(view)

    if (view.status === 'DONE' && view.answer) return view.answer
    if (view.status === 'FAILED') throw new Error(view.error || 'Prax could not finish that')
  }
  throw new Error('That is taking longer than expected. Try a narrower question.')
}

export function AskPrax() {
  const [turns, setTurns] = useState<Turn[]>([])
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)
  /** Null until the first answer names the thread. */
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [threads, setThreads] = useState<ConversationSummary[]>([])
  const [showThreads, setShowThreads] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const transcriptRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => () => abortRef.current?.abort(), [])

  // Follow the conversation as it grows. A transcript that does not scroll
  // itself makes every answer look like it never arrived.
  useEffect(() => {
    const el = transcriptRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [turns, busy])

  const ask = useCallback(async (question: string) => {
    const text = question.trim()
    if (!text || busy) return

    const id = Date.now()
    setTurns(t => [...t, { id, question: text, answer: null, error: null }])
    setQ('')
    setBusy(true)
    praxRuntime.send({ type: 'QUERY_STARTED' })

    const ctl = new AbortController()
    abortRef.current = ctl
    try {
      // Start the run and let go of the request. Holding it open for 15-40s is
      // what made the workspace look broken.
      const started = await fetch('/api/prax/ask/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: text, conversationId }),
        signal: ctl.signal,
      })
      if (!started.ok) throw new Error(`Prax is unavailable (${started.status})`)
      const { run_id: runId } = await started.json() as { run_id: string }

      const body = await pollRun(runId, ctl.signal, (view) => {
        setTurns(t => t.map(x => (x.id === id
          ? { ...x, liveSteps: view.steps, liveArtifacts: view.artifacts }
          : x)))
      })

      setTurns(t => t.map(x => (x.id === id
        ? { ...x, answer: body, liveSteps: undefined, liveArtifacts: undefined }
        : x)))
      // The backend owns thread identity; the client just carries it forward.
      if (body.conversation_id) setConversationId(body.conversation_id)
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        // Abandoning is a choice, not a failure — drop the turn entirely.
        setTurns(t => t.filter(x => x.id !== id))
      } else {
        const message = e instanceof Error ? e.message : 'Request failed'
        setTurns(t => t.map(x => (x.id === id ? { ...x, error: message } : x)))
      }
    } finally {
      abortRef.current = null
      praxRuntime.send({ type: 'QUERY_FINISHED' })
      setBusy(false)
      inputRef.current?.focus()
    }
  }, [busy, conversationId])

  /**
   * A new chat is simply forgetting the thread id.
   *
   * Nothing is deleted — the old conversation stays readable in the history
   * panel. Dropping the id is the whole operation, because the backend starts a
   * thread whenever it is asked a question without one.
   */
  function newChat() {
    abortRef.current?.abort()
    setTurns([])
    setConversationId(null)
    setShowThreads(false)
    inputRef.current?.focus()
  }

  const loadThreads = useCallback(async () => {
    try {
      const r = await fetch('/api/prax/conversations')
      if (r.ok) setThreads(await r.json())
    } catch {
      // The picker is a convenience; failing to list must not break asking.
    }
  }, [])

  /** Reopen a past thread, with everything the player saw the first time. */
  const openThread = useCallback(async (id: string) => {
    abortRef.current?.abort()
    setShowThreads(false)
    try {
      const r = await fetch(`/api/prax/conversations/${id}`)
      if (!r.ok) return
      const detail: { messages: { role: string; content: string; payload?: Answer }[] } =
        await r.json()

      // Messages alternate user/assistant; fold each pair back into one turn.
      const restored: Turn[] = []
      for (let i = 0; i < detail.messages.length; i++) {
        const m = detail.messages[i]
        if (m.role !== 'user') continue
        const reply = detail.messages[i + 1]
        restored.push({
          id: i,
          question: m.content,
          answer: reply && reply.role === 'assistant'
            ? (reply.payload && Object.keys(reply.payload).length > 0
                ? reply.payload
                : { answer: reply.content, findings: [], evidence: [], steps: [], partial: false })
            : null,
          error: null,
        })
      }
      setTurns(restored)
      setConversationId(id)
    } catch {
      // Leave the current thread alone rather than half-loading another.
    }
  }, [])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 160px)', minHeight: 420 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <div>
          <h2 style={{ fontSize: '1.2rem', fontWeight: 700, margin: 0 }}>Ask Prax</h2>
          <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 4 }}>
            Everything here is measured from your games, or cited to a source.
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button
            onClick={() => { setShowThreads(v => !v); if (!showThreads) loadThreads() }}
            style={ghostBtn}
            aria-expanded={showThreads}
          >
            History
          </button>
          {turns.length > 0 && (
            <button onClick={newChat} style={ghostBtn}>New chat</button>
          )}
        </div>
      </div>

      {showThreads && (
        <div
          role="region"
          aria-label="Earlier conversations"
          style={{
            marginTop: 12, padding: '10px 12px',
            border: '1px solid var(--hairline)', borderRadius: 5,
            background: 'var(--surface-2, #1B1920)', maxHeight: 200, overflowY: 'auto',
          }}
        >
          {threads.length === 0 ? (
            <p style={{ margin: 0, fontSize: '0.76rem', color: 'var(--text-muted)' }}>
              No earlier conversations.
            </p>
          ) : (
            threads.map(t => (
              <button
                key={t.id}
                onClick={() => openThread(t.id)}
                style={{
                  display: 'block', width: '100%', textAlign: 'left',
                  background: 'transparent', border: 'none', cursor: 'pointer',
                  color: t.id === conversationId
                    ? 'var(--orchid, #E7A6D6)'
                    : 'var(--text-secondary, #B4AEBE)',
                  fontSize: '0.78rem', padding: '5px 0', fontFamily: 'inherit',
                }}
              >
                {t.title || 'Untitled'}
              </button>
            ))
          )}
        </div>
      )}

      {/*
        Prax is anchored at 90% of the viewport width. A full-width transcript
        ran its right-hand column straight under the particle body — evidence
        VALUES were rendered at the far right and became unreadable, so the rows
        looked like bare labels with nothing beside them.

        The gutter is claimed here rather than by moving Prax: the anchor
        position is part of the page's composition, and content should respect
        it the way it respects any other fixed element.
      */}
      <div
        ref={transcriptRef}
        style={{
          flex: 1, overflowY: 'auto', marginTop: 16,
          // Clearance from the ORGANISM, which is always on this page. <main>
          // separately reserves --prax-gutter when a card is up; subtract it so
          // the two reserves do not stack into a hard-left column of text.
          paddingRight: 'max(0px, calc(clamp(4px, 22vw, 360px) - var(--prax-gutter, 0px)))',
        }}
      >
        {turns.length === 0 && !busy && <EmptyState onPick={ask} />}

        <div style={{ display: 'flex', flexDirection: 'column', gap: 26 }}>
          {turns.map(turn => (
            <TurnView key={turn.id} turn={turn} />
          ))}
        </div>

      </div>

      <form
        onSubmit={e => { e.preventDefault(); ask(q) }}
        style={{
          display: 'flex', gap: 8, alignItems: 'flex-end', marginTop: 14,
          borderTop: '1px solid var(--hairline)', paddingTop: 14,
        }}
      >
        <textarea
          ref={inputRef}
          value={q}
          rows={1}
          onChange={e => setQ(e.target.value)}
          onKeyDown={e => {
            // Enter asks, Shift+Enter adds a line — the same contract as the card.
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ask(q) }
          }}
          placeholder="Ask about your games…"
          aria-label="Ask Prax a question"
          style={{
            flex: 1, minWidth: 0, resize: 'none',
            background: 'var(--surface-2, #1B1920)',
            border: '1px solid var(--hairline)', borderRadius: 4,
            color: 'var(--text)', padding: '9px 11px', fontSize: '0.85rem',
            fontFamily: 'inherit', lineHeight: 1.45, maxHeight: 120,
          }}
        />
        {busy ? (
          <button type="button" onClick={() => abortRef.current?.abort()} style={ghostBtn}>
            Stop
          </button>
        ) : (
          <button type="submit" className="btn-primary" disabled={!q.trim()}
                  style={{ padding: '9px 18px', fontSize: '0.82rem' }}>
            Ask
          </button>
        )}
      </form>

      <PraxAnchor x={0.9} y={0.42} />
    </div>
  )
}

/**
 * One exchange.
 *
 * Two panes on a wide screen, stacked below ~1100px. The split is a layout
 * choice, not the data model — artifacts belong to a MESSAGE, so stacking needs
 * no restructuring.
 */
function TurnView({ turn }: { turn: Turn }) {
  const hasArtifacts = !!turn.answer?.artifacts?.length

  return (
    <div>
      <p style={{
        margin: 0, fontSize: '0.85rem', fontWeight: 600,
        color: 'var(--text-secondary, #B4AEBE)',
      }}>
        {turn.question}
      </p>

      {turn.error && (
        <p style={{ margin: '8px 0 0', fontSize: '0.8rem', color: 'var(--loss, #E2664A)' }}>
          {turn.error}
        </p>
      )}

      {/* Work in progress. Boards appear here the moment the engine returns one
          — typically 10-20s before the prose exists. */}
      {!turn.answer && !turn.error && <Working artifacts={turn.liveArtifacts} />}

      {turn.answer && (
        <div
          style={{
            marginTop: 10,
            display: 'grid',
            // Artifacts get their own column only when there are any; a permanent
            // empty right-hand pane reads as broken.
            gridTemplateColumns: hasArtifacts ? 'minmax(0, 1fr) minmax(300px, 360px)' : '1fr',
            gap: 20,
            alignItems: 'start',
          }}
        >
          <div>
            {turn.answer.findings.length > 0 && (
              <ul style={{
                margin: '0 0 11px', padding: '0 0 0 15px',
                fontSize: '0.84rem', lineHeight: 1.55,
              }}>
                {turn.answer.findings.map((f, i) => <li key={i}>{f}</li>)}
              </ul>
            )}

            <p style={{ margin: 0, fontSize: '0.86rem', lineHeight: 1.6 }}>
              {turn.answer.answer}
            </p>

            {turn.answer.evidence.length > 0 && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 5, marginTop: 16 }}>
                {turn.answer.evidence.map((e, i) => (
                  <div key={i} style={{
                    display: 'flex', justifyContent: 'space-between', gap: 12,
                    fontSize: '0.74rem', fontFamily: 'var(--font-mono, monospace)',
                  }}>
                    <span>
                      {e.label}
                      {e.source === 'ENGINE' && <span style={tag}>engine</span>}
                      {e.source === 'KNOWLEDGE' && <span style={tag}>general</span>}
                    </span>
                    <span style={{ color: 'var(--text-secondary, #B4AEBE)' }}>{e.value}</span>
                  </div>
                ))}
              </div>
            )}

            <AnswerMeta answer={turn.answer} />
          </div>

          {hasArtifacts && (
            <div><ArtifactList artifacts={turn.answer.artifacts} /></div>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * The wait, made legible.
 *
 * Shows a rotating word rather than the tool trace: `get_mistake_patterns · 274`
 * is a function name, and reading one is not the same as being told something is
 * happening. The tools still appear in the finished answer, where they are
 * provenance rather than a progress bar.
 *
 * Artifacts render here too, so a board is on screen while Prax is still
 * writing about it.
 */
function Working({ artifacts }: { artifacts?: PraxArtifact[] }) {
  const word = useWorkingWord(true)

  return (
    <div style={{ marginTop: 10 }}>
      <p style={{
        margin: 0, fontSize: '0.82rem', color: 'var(--text-muted)',
        fontStyle: 'italic',
      }}>
        {word}
        <span aria-hidden="true" style={{ opacity: 0.55 }}>…</span>
      </p>
      <ArtifactList artifacts={artifacts} />
    </div>
  )
}

/**
 * Everything under the answer: where it came from, what it read, how far it looked.
 *
 * These were three consecutive blocks with identical margins, identical hairline
 * rules and near-identical colours, so they read as one striped element rather
 * than three separate facts.
 *
 * The rebuild uses ONE divider — between the answer and its provenance — and
 * whitespace for everything after it. Each block then gets a distinct SHAPE
 * rather than a distinct border: a pill, a numbered list, a plain line. Shape
 * separates where a repeated rule only stacks.
 */
function AnswerMeta({ answer }: { answer: Answer }) {
  const escalated = answer.grounding === 'ESCALATED_TO_WEB'
  const sources = answer.sources ?? []
  const effort = effortLine(answer.steps)

  if (!escalated && sources.length === 0 && !effort) return null

  return (
    <div style={{
      marginTop: 22,
      paddingTop: 18,
      // The only rule in the group. One divider says "the answer ends here";
      // three said "these lines belong together".
      borderTop: '1px solid var(--hairline)',
      display: 'flex',
      flexDirection: 'column',
      // Even and generous. Consistent rhythm reads as separate items; the old
      // 12px gaps read as line spacing within one paragraph.
      gap: 18,
    }}>
      {escalated && (
        <div style={{
          display: 'inline-flex', alignItems: 'center', gap: 7, alignSelf: 'flex-start',
          padding: '5px 11px', borderRadius: 999,
          background: 'var(--surface-2, #1B1920)',
          border: '1px solid var(--hairline)',
          fontSize: '0.68rem', color: 'var(--text-secondary, #B4AEBE)',
        }}>
          <span aria-hidden="true" style={{
            width: 5, height: 5, borderRadius: '50%',
            background: 'var(--orchid, #E7A6D6)', flexShrink: 0,
          }} />
          Your games had nothing on this, so Prax looked it up
        </div>
      )}

      {sources.length > 0 && (
        <div>
          <div style={{
            fontSize: '0.6rem', letterSpacing: '0.09em', textTransform: 'uppercase',
            color: 'var(--text-tertiary, #625C6D)', marginBottom: 10,
          }}>
            {sources.length} web {sources.length === 1 ? 'source' : 'sources'}
          </div>
          <ol style={{
            margin: 0, padding: 0, listStyle: 'none',
            display: 'flex', flexDirection: 'column', gap: 10,
          }}>
            {sources.map((s, i) => (
              <li key={s.url + i} style={{ display: 'flex', gap: 10, alignItems: 'baseline' }}>
                {/* Numbered, so a citation is something the reader can point at. */}
                <span aria-hidden="true" style={{
                  fontFamily: 'var(--font-mono, monospace)', fontSize: '0.64rem',
                  color: 'var(--text-tertiary, #625C6D)', minWidth: 11, flexShrink: 0,
                }}>
                  {i + 1}
                </span>
                <a
                  href={s.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  style={{
                    fontSize: '0.76rem', lineHeight: 1.35, textDecoration: 'none',
                    color: 'var(--text-secondary, #B4AEBE)',
                  }}
                >
                  {s.title}
                  <span style={{
                    display: 'block', fontSize: '0.66rem',
                    color: 'var(--text-tertiary, #625C6D)', marginTop: 2,
                  }}>
                    {s.domain}
                  </span>
                </a>
              </li>
            ))}
          </ol>
        </div>
      )}

      {effort && (
        <div style={{
          fontFamily: 'var(--font-mono, monospace)', fontSize: '0.62rem',
          color: 'var(--text-tertiary, #625C6D)',
        }}>
          {effort}
        </div>
      )}
    </div>
  )
}

function EmptyState({ onPick }: { onPick: (q: string) => void }) {
  return (
    <div style={{ maxWidth: 520 }}>
      <p style={{ margin: '0 0 12px', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
        Ask about your own chess and Prax answers from your analysed games. Ask about
        chess in general and it cites where the answer came from.
      </p>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'flex-start' }}>
        {SUGGESTIONS.map(s => (
          <button key={s} onClick={() => onPick(s)} style={{ ...ghostBtn, fontSize: '0.78rem' }}>
            {s}
          </button>
        ))}
      </div>
    </div>
  )
}

const ghostBtn: React.CSSProperties = {
  background: 'transparent',
  border: '1px solid var(--hairline)',
  color: 'var(--text-secondary)',
  borderRadius: 4,
  padding: '6px 12px',
  fontSize: '0.78rem',
  cursor: 'pointer',
}

const tag: React.CSSProperties = {
  marginLeft: 6,
  fontSize: '0.58rem',
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: 'var(--text-tertiary, #625C6D)',
  border: '1px solid var(--hairline)',
  borderRadius: 3,
  padding: '0 4px',
}
