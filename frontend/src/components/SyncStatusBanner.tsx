import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { useSyncStatus } from '../hooks/useSyncStatus'
import { useAnalysisProgress } from '../hooks/useAnalysisProgress'

function fmtEta(secs: number): string {
  if (secs < 0) return ''
  if (secs < 60) return `~${secs}s`
  const m = Math.round(secs / 60)
  return `~${m} min`
}

export function SyncStatusBanner() {
  const { data: status } = useSyncStatus()
  const { data: progress, startWarmup } = useAnalysisProgress()

  // Check Chess.com for games not yet in our DB.
  // Runs once per app session (staleTime = Infinity so React Query never auto-refetches).
  // Server also caches for 10 hours — this fires exactly 1 Chess.com call per server restart.
  const { data: newCountData } = useQuery({
    queryKey: ['sync-new-count'],
    queryFn: () => api.sync.newCount(),
    staleTime: Infinity,    // never auto-refetch within this session
    gcTime: Infinity,
    retry: false,           // don't retry — the user can sync manually if they want fresh data
  })
  const newGamesCount = newCountData?.count ?? 0
  const queryClient = useQueryClient()
  const [toast, setToast] = useState<string | null>(null)
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const wasRunning = useRef(false)

  // Show toast when analysis pipeline finishes
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

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['sync-status'] })
    queryClient.invalidateQueries({ queryKey: ['games'] })
    queryClient.invalidateQueries({ queryKey: ['dashboard-stats'] })
    queryClient.invalidateQueries({ queryKey: ['analysis-progress'] })
  }

  const syncMutation = useMutation({
    mutationFn: () => api.sync.trigger(3),
    onSuccess: () => { startWarmup(); invalidate() },
  })

  const forceResyncMutation = useMutation({
    mutationFn: () => api.sync.forceResync(3),
    onSuccess: () => { startWarmup(); invalidate() },
  })

  const analyzePendingMutation = useMutation({
    mutationFn: () => api.analysis.analyzePending(),
    onSuccess: () => { startWarmup(); invalidate() },
  })

  const reanalyzeAllMutation = useMutation({
    mutationFn: () => api.analysis.reanalyzeAll(),
    onSuccess: () => { startWarmup(); invalidate() },
  })

  const isAnalyzing = progress?.running || progress?.pattern_generating
  const isActive = status?.state === 'SYNCING' || isAnalyzing
  const isBusy = isActive || syncMutation.isPending || forceResyncMutation.isPending
    || analyzePendingMutation.isPending || reanalyzeAllMutation.isPending

  const hasPending = (status?.games_pending ?? 0) > 0

  const pct = progress?.percent_complete ?? 0
  const eta = progress ? fmtEta(progress.eta_seconds) : ''

  return (
    <>
      <div style={{
        background: isActive ? 'var(--accent-dim)' : 'var(--surface)',
        borderBottom: '1px solid var(--border)',
        padding: '6px 24px',
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
        fontSize: '0.78rem',
        color: 'var(--text-muted)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <span style={{ flex: 1 }}>
            {status?.state === 'SYNCING' && '⟳ Fetching games from Chess.com...'}

            {progress?.running && (
              <>
                <span style={{ color: 'var(--accent)', fontWeight: 600 }}>
                  ⟳ Analyzing {progress.completed} / {progress.total} games
                </span>
                {eta && <span style={{ marginLeft: 8 }}>{eta} remaining</span>}
              </>
            )}

            {progress?.pattern_generating && !progress.running && (
              <span style={{ color: 'var(--yellow)' }}>
                ⟳ Generating Pattern Report...
              </span>
            )}

            {!isActive && status?.state === 'IDLE' && (
              <>
                {status?.games_analyzed ?? 0} analyzed
                {(status?.games_pending ?? 0) > 0 && (
                  <span style={{ color: 'var(--yellow)', marginLeft: 6 }}>
                    · {status!.games_pending} pending
                  </span>
                )}
                {newGamesCount > 0 && (
                  <span style={{ color: 'var(--orchid)', marginLeft: 6 }}>
                    · {newGamesCount} new on Chess.com
                  </span>
                )}
                {' · Last sync: '}
                {status?.last_synced_at === 'Never'
                  ? 'Never'
                  : new Date(status.last_synced_at).toLocaleString()}
              </>
            )}

            {!status && 'Loading...'}
          </span>

          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
            <button
              onClick={() => analyzePendingMutation.mutate()}
              disabled={isBusy || !hasPending}
              className="secondary"
              style={{ padding: '4px 10px', fontSize: '0.72rem', color: hasPending ? 'var(--accent)' : undefined }}
              title={hasPending ? `Analyze ${status?.games_pending} pending game(s)` : 'No pending games'}
            >
              {analyzePendingMutation.isPending ? 'Queuing...' : `▶ Analyze Pending${hasPending ? ` (${status?.games_pending})` : ''}`}
            </button>
            <button
              onClick={() => reanalyzeAllMutation.mutate()}
              disabled={isBusy}
              className="secondary"
              style={{ padding: '4px 10px', fontSize: '0.72rem' }}
              title="Reset and re-analyze all games from scratch"
            >
              {reanalyzeAllMutation.isPending ? 'Queuing...' : '⟳ Re-analyze All'}
            </button>
            <button
              onClick={() => forceResyncMutation.mutate()}
              disabled={isBusy}
              className="secondary"
              style={{ padding: '4px 10px', fontSize: '0.72rem' }}
              title="Re-fetch last 3 months from Chess.com and update accuracy data"
            >
              {forceResyncMutation.isPending ? 'Re-syncing...' : '↻ Re-Sync'}
            </button>
            <button
              className="solid"
              onClick={() => syncMutation.mutate()}
              disabled={isBusy}
              style={{ padding: '4px 12px', fontSize: '0.75rem', position: 'relative' }}
              title={newGamesCount > 0 ? `${newGamesCount} new game${newGamesCount !== 1 ? 's' : ''} available on Chess.com` : undefined}
            >
              {syncMutation.isPending ? 'Syncing...' : 'Sync Now'}
              {newGamesCount > 0 && !isBusy && (
                <span style={{
                  position: 'absolute', top: -6, right: -6,
                  background: 'var(--orchid)', color: '#fff',
                  borderRadius: 10, fontSize: '0.6rem', fontWeight: 700,
                  padding: '1px 5px', lineHeight: '1.4',
                  pointerEvents: 'none',
                }}>
                  {newGamesCount}
                </span>
              )}
            </button>
          </div>
        </div>

        {/* Progress bar */}
        {progress?.running && (
          <div style={{ height: 3, background: 'var(--surface-2)', borderRadius: 2, overflow: 'hidden' }}>
            <div style={{
              height: '100%',
              width: `${pct}%`,
              background: 'var(--accent)',
              borderRadius: 2,
              transition: 'width 0.4s ease',
            }} />
          </div>
        )}
      </div>

      {/* Bottom-right toast */}
      {toast && (
        <div
          onClick={() => setToast(null)}
          style={{
            position: 'fixed',
            bottom: 24,
            right: 24,
            zIndex: 9999,
            background: 'rgba(18,17,16,0.92)',
            backdropFilter: 'blur(18px) saturate(115%)',
            border: '1px solid var(--hairline-lit)',
            borderRadius: 4,
            padding: '12px 18px',
            fontSize: '0.82rem',
            color: 'var(--text)',
            boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.05)',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            maxWidth: 320,
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
