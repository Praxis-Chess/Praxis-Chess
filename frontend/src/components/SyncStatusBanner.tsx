import { useEffect, useRef, useState } from 'react'
import { praxInteract } from '../prax/PraxHost'
import { useLibraryActions, fmtEta } from '../hooks/useLibraryActions'

type Library = ReturnType<typeof useLibraryActions>

/** The failure to report, if a library action just failed. */
function failure(lib: Library): { text: string; retry: () => void } | null {
  const msg = (e: unknown) => (e instanceof Error ? e.message : 'unknown error')
  if (lib.sync.isError) return { text: `Couldn't sync with Chess.com: ${msg(lib.sync.error)}`, retry: () => lib.sync.mutate() }
  if (lib.analyzePending.isError) return { text: `Couldn't queue the analysis: ${msg(lib.analyzePending.error)}`, retry: () => lib.analyzePending.mutate() }
  return null
}

/**
 * Idle library news, in the nav (POLISH_PLAN.md D2): ghost chips for new games
 * on Chess.com and games waiting for analysis. Never solid: the page's one solid
 * button belongs to the page.
 */
export function SyncChips({ lib }: { lib: Library }) {
  const { isActive, isBusy, pending, newGames } = lib
  if (isActive || failure(lib)) return null
  return (
    <>
      {newGames > 0 && (
        <button type="button" className="nav-chip" disabled={isBusy}
                aria-label={`Sync ${newGames} new game${newGames === 1 ? '' : 's'} from Chess.com`}
                onClick={() => { praxInteract('SYNC_STARTED'); lib.sync.mutate() }}>
          <span className="mono">{newGames}</span> new<span className="chip-verb"> · {lib.sync.isPending ? 'Syncing…' : 'Sync'}</span>
        </button>
      )}
      {pending > 0 && (
        <button type="button" className="nav-chip" disabled={isBusy}
                aria-label={`Analyse ${pending} waiting game${pending === 1 ? '' : 's'}`}
                onClick={() => { praxInteract('PRIMARY_ACTION'); lib.analyzePending.mutate() }}>
          <span className="mono">{pending}</span> waiting<span className="chip-verb"> · {lib.analyzePending.isPending ? 'Queuing…' : 'Analyse'}</span>
        </button>
      )}
    </>
  )
}

/**
 * The glass strip under the nav, only while a sync or analysis runs (with its
 * progress) or when a library action failed (an alert). Idle news is a nav chip
 * (SyncChips); every library action stays in Settings → Library.
 */
export function SyncStatusBanner({ lib }: { lib: Library }) {
  const { status, progress, isActive } = lib
  const failed = failure(lib)

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
      {(isActive || failed) && (
        // Carries the run's controls, so the Prax card measures this
        // rather than guessing a header height and covering them.
        <div data-prax-avoid="" aria-label="Library status" role={failed ? 'alert' : undefined}
             className="glass" data-active={isActive || undefined} style={{
          margin: '12px 24px 0',
          padding: '10px 14px 10px 18px',
          display: 'flex', flexDirection: 'column', gap: 6,
          fontSize: '0.78rem', color: 'var(--text-muted)',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <span style={{ flex: 1 }}>
              {status?.state === 'SYNCING' && '⟳ Fetching games from Chess.com…'}
              {progress?.running && (
                <>
                  <span style={{ color: 'var(--accent)', fontWeight: 600 }}>
                    ⟳ Analysing {progress.completed} / {progress.total} games
                  </span>
                  {eta && <span style={{ marginLeft: 8 }}>{eta} remaining</span>}
                </>
              )}
              {progress?.pattern_generating && !progress.running && (
                <span style={{ color: 'var(--yellow)' }}>⟳ Generating Pattern Report…</span>
              )}
              {!isActive && failed && <span style={{ color: 'var(--loss)' }}>{failed.text}</span>}
            </span>

            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
              {progress?.running && (
                <button className="secondary" style={btn} disabled={lib.stop.isPending}
                        onClick={() => lib.stop.mutate()}>
                  Stop analysis
                </button>
              )}
              {!isActive && failed && (
                <button className="secondary" style={btn} onClick={failed.retry}>Try again</button>
              )}
            </div>
          </div>

          {progress?.running && (
            <div style={{ height: 3, background: 'var(--surface-2)', borderRadius: 2, overflow: 'hidden' }}>
              <div style={{
                height: '100%', width: `${progress.percent_complete ?? 0}%`,
                background: 'var(--accent)', borderRadius: 2, transition: 'width var(--dur-fill) var(--ease-out)',
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
            background: 'rgba(0,0,0,0.88)', backdropFilter: 'blur(18px) saturate(115%)',
            border: '1px solid var(--hairline-strong)', borderRadius: 4, padding: '12px 18px',
            fontSize: '0.82rem', color: 'var(--text)', boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.05)',
            cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 10, maxWidth: 320,
            animation: 'slideIn var(--dur-slow) var(--ease-out)',
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
        @media (prefers-reduced-motion: reduce) {
          @keyframes slideIn { from { opacity: 0; } to { opacity: 1; } }
        }
      `}</style>
    </>
  )
}
