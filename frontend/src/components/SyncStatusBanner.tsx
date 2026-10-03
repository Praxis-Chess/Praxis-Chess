import { useEffect, useRef, useState } from 'react'
import { praxInteract } from '../prax/PraxHost'
import { useLibraryActions, fmtEta } from '../hooks/useLibraryActions'

/**
 * The strip under the nav, shown only when there is something to act on:
 * new games on Chess.com, games waiting for analysis, or a sync or analysis run
 * in flight. An idle, up-to-date library shows nothing here; every library
 * action stays available in Settings → Library.
 */
export function SyncStatusBanner() {
  const lib = useLibraryActions()
  const { status, progress, isActive, isBusy, pending, newGames } = lib

  const [toast, setToast] = useState<string | null>(null)
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const wasRunning = useRef(false)

  // Toast when the analysis pipeline finishes, whether or not the strip is showing.
  useEffect(() => {
    if (!progress) return
    const justFinished = wasRunning.current && !progress.running && !progress.pattern_generating
    if (justFinished) {
      setToast('Analysis complete — Pattern Report updated')
      if (toastTimer.current) clearTimeout(toastTimer.current)
      toastTimer.current = setTimeout(() => setToast(null), 5000)
    }
    wasRunning.current = progress.running || progress.pattern_generating
  }, [progress])

  const eta = progress ? fmtEta(progress.eta_seconds) : ''
  const btn = { padding: '4px 10px', fontSize: '0.72rem' }

  return (
    <>
      {lib.needsAttention && (
        // Carries the sync/analyze controls, so the Prax card measures this
        // rather than guessing a header height and covering them.
        <div data-prax-avoid="" aria-label="Library status" style={{
          background: isActive ? 'var(--accent-dim)' : 'var(--surface)',
          borderBottom: '1px solid var(--border)',
          padding: '6px 24px',
          display: 'flex', flexDirection: 'column', gap: 6,
          fontSize: '0.78rem', color: 'var(--text-muted)',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <span style={{ flex: 1 }}>
              {status?.state === 'SYNCING' && '⟳ Fetching games from Chess.com…'}
              {progress?.running && (
                <>
                  <span style={{ color: 'var(--accent)', fontWeight: 600 }}>
                    ⟳ Analyzing {progress.completed} / {progress.total} games
                  </span>
                  {eta && <span style={{ marginLeft: 8 }}>{eta} remaining</span>}
                </>
              )}
              {progress?.pattern_generating && !progress.running && (
                <span style={{ color: 'var(--yellow)' }}>⟳ Generating Pattern Report…</span>
              )}
              {!isActive && (
                <>
                  {newGames > 0 && (
                    <span style={{ color: 'var(--orchid)' }}>
                      {newGames} new game{newGames === 1 ? '' : 's'} on Chess.com
                    </span>
                  )}
                  {newGames > 0 && pending > 0 && ' · '}
                  {pending > 0 && (
                    <span style={{ color: 'var(--yellow)' }}>
                      {pending} game{pending === 1 ? '' : 's'} waiting for analysis
                    </span>
                  )}
                </>
              )}
            </span>

            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
              {progress?.running && (
                <button className="secondary" style={btn} disabled={lib.stop.isPending}
                        onClick={() => lib.stop.mutate()}>
                  Stop analysis
                </button>
              )}
              {!isActive && pending > 0 && (
                <button className="secondary" style={{ ...btn, color: 'var(--accent)' }} disabled={isBusy}
                        onClick={() => { praxInteract('PRIMARY_ACTION'); lib.analyzePending.mutate() }}>
                  {lib.analyzePending.isPending ? 'Queuing…' : `▶ Analyze ${pending}`}
                </button>
              )}
              {!isActive && newGames > 0 && (
                <button className="solid" style={{ ...btn, padding: '4px 12px' }} disabled={isBusy}
                        onClick={() => { praxInteract('SYNC_STARTED'); lib.sync.mutate() }}>
                  {lib.sync.isPending ? 'Syncing…' : 'Sync Now'}
                </button>
              )}
            </div>
          </div>

          {progress?.running && (
            <div style={{ height: 3, background: 'var(--surface-2)', borderRadius: 2, overflow: 'hidden' }}>
              <div style={{
                height: '100%', width: `${progress.percent_complete ?? 0}%`,
                background: 'var(--accent)', borderRadius: 2, transition: 'width 0.4s ease',
              }} />
            </div>
          )}
        </div>
      )}

      {toast && (
        <div
          onClick={() => setToast(null)}
          style={{
            position: 'fixed', bottom: 24, right: 24, zIndex: 9999,
            background: 'rgba(18,17,16,0.92)', backdropFilter: 'blur(18px) saturate(115%)',
            border: '1px solid var(--hairline-lit)', borderRadius: 4, padding: '12px 18px',
            fontSize: '0.82rem', color: 'var(--text)', boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.05)',
            cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 10, maxWidth: 320,
            animation: 'slideIn 0.2s ease',
          }}
        >
          <span style={{ fontSize: '1rem' }}>✓</span>
          <span>{toast}</span>
        </div>
      )}

      <style>{`
        @keyframes slideIn {
          from { transform: translateY(12px); opacity: 0; }
          to   { transform: translateY(0);   opacity: 1; }
        }
      `}</style>
    </>
  )
}
