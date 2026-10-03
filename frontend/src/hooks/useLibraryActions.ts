import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { useSyncStatus } from './useSyncStatus'
import { useAnalysisProgress, markStopping, markStopped } from './useAnalysisProgress'

/**
 * Sync and analysis state plus the four library actions, in one place.
 *
 * Two surfaces use it: the banner under the nav, which appears only when there
 * is something to do (new games, pending games, a run in flight), and the
 * Library card in Settings, which always offers every action.
 */
export function useLibraryActions() {
  const { data: status } = useSyncStatus()
  const { data: progress, startWarmup } = useAnalysisProgress()
  const queryClient = useQueryClient()

  // Check Chess.com for games not yet in our DB, once per app session; the
  // server also caches it for 10 hours, so this is one Chess.com call per restart.
  const { data: newCountData } = useQuery({
    queryKey: ['sync-new-count'],
    queryFn: () => api.sync.newCount(),
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['sync-status'] })
    queryClient.invalidateQueries({ queryKey: ['sync-new-count'] })
    queryClient.invalidateQueries({ queryKey: ['games'] })
    queryClient.invalidateQueries({ queryKey: ['dashboard-stats'] })
    queryClient.invalidateQueries({ queryKey: ['analysis-progress'] })
    queryClient.invalidateQueries({ queryKey: ['settings-coverage'] })
  }
  const onSuccess = () => { startWarmup(); invalidate() }

  // When a sync finishes, the "new on Chess.com" count and the game list are out
  // of date: refetch both. (Without this the count stayed at its pre-sync value.)
  const wasSyncing = useRef(false)
  useEffect(() => {
    const syncing = status?.state === 'SYNCING'
    if (wasSyncing.current && !syncing) {
      queryClient.invalidateQueries({ queryKey: ['sync-new-count'] })
      queryClient.invalidateQueries({ queryKey: ['games'] })
    }
    wasSyncing.current = syncing
  }, [status?.state, queryClient])

  const sync = useMutation({ mutationFn: () => api.sync.trigger(3), onSuccess })
  const resync = useMutation({ mutationFn: () => api.sync.forceResync(3), onSuccess })
  const analyzePending = useMutation({ mutationFn: () => api.analysis.analyzePending(), onSuccess })
  const reanalyzeAll = useMutation({ mutationFn: () => api.analysis.reanalyzeAll(), onSuccess })
  const stop = useMutation({
    mutationFn: () => {
      markStopping(progress?.completed ?? 0, progress?.total ?? 0)
      return api.analysis.stop()
    },
    onSuccess: (r) => markStopped(r.completed, r.total),
  })

  const isAnalyzing = !!(progress?.running || progress?.pattern_generating)
  const isActive = status?.state === 'SYNCING' || isAnalyzing
  const isBusy = isActive || sync.isPending || resync.isPending || analyzePending.isPending || reanalyzeAll.isPending
  const pending = status?.games_pending ?? 0
  const newGames = newCountData?.count ?? 0

  return {
    status, progress, isAnalyzing, isActive, isBusy, pending, newGames,
    /** Something worth a banner: new games, pending games, or a run in flight. */
    needsAttention: isActive || pending > 0 || newGames > 0,
    sync, resync, analyzePending, reanalyzeAll, stop,
  }
}

export function fmtEta(secs: number): string {
  if (secs < 0) return ''
  if (secs < 60) return `~${secs}s`
  return `~${Math.round(secs / 60)} min`
}
