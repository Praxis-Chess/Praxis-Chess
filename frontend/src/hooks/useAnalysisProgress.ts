import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { api } from '../api/client'
import { praxBus } from '../prax/core/events'
import { narrationStore } from '../prax/state/narrationStore'
import {
  narrateStart,
  narrateProgress,
  narratePatternPhase,
  narrateStopping,
  narrateStopped,
  narrateObservation,
  narrateComplete,
} from '../prax/state/progressNarrator'

/** Module-level: the hook mounts in more than one component, and each copy
 *  having its own edge-detector meant duplicate and remount-triggered events. */
let analysisWasBusy = false
/** One observation per run, and only once there is enough data to mean anything. */
let observedThisRun = false

export function useAnalysisProgress() {
  const queryClient = useQueryClient()
  const wasPatternGenerating = useRef(false)
  const forcePollUntil = useRef(0)

  const startWarmup = () => { forcePollUntil.current = Date.now() + 20_000 }

  const query = useQuery({
    queryKey: ['analysis-progress'],
    queryFn: api.analysis.progress,
    refetchInterval: (q) => {
      const d = q.state.data
      if (d?.running || d?.pattern_generating || d?.queued) return 2000
      if (Date.now() < forcePollUntil.current) return 2000
      return false
    },
  })

  useEffect(() => {
    const d = query.data
    if (!d) return

    // When pattern generation finishes, refresh data across all tabs
    if (wasPatternGenerating.current && !d.pattern_generating && !d.running) {
      queryClient.invalidateQueries({ queryKey: ['dashboard-stats'] })
      queryClient.invalidateQueries({ queryKey: ['games'] })
      queryClient.invalidateQueries({ queryKey: ['patterns'] })
      queryClient.invalidateQueries({ queryKey: ['sync-status'] })
    }

    // Prax signals — Contract §1. The hook owns ANALYSIS_* and nothing else.
    const busy = d.running || d.pattern_generating || d.queued

    if (busy && !analysisWasBusy) {
      observedThisRun = false
      praxBus.emit({ type: 'ANALYSIS_STARTED' })
      narrationStore.set(narrateStart(d.total), d.completed, d.total)
    } else if (!busy && analysisWasBusy) {
      praxBus.emit({ type: 'ANALYSIS_FINISHED' })
      void resolveCompletion(d.total)
    } else if (busy) {
      if (d.total > 0) {
        praxBus.emit({ type: 'ANALYSIS_PROGRESS', completed: d.completed, total: d.total })
      }

      // Narration ladder — each line derived from state, never invented.
      const line = d.stopping
        ? narrateStopping()
        : d.pattern_generating
          ? narratePatternPhase()
          : narrateProgress(d.completed, d.total)
      narrationStore.set(line, d.completed, d.total, !!d.stopping)

      // The single mid-run observation, once there is enough analysed to mean
      // something. One extra request per run, not per poll.
      if (!observedThisRun && d.completed >= 20 && !d.pattern_generating) {
        observedThisRun = true
        void observe(d.completed)
      }
    }

    analysisWasBusy = busy
    wasPatternGenerating.current = d.pattern_generating
  }, [query.data, queryClient])

  return { ...query, startWarmup }
}

/** Reads real phase counts; says nothing if the lead isn't meaningful. */
async function observe(completed: number) {
  try {
    const insights = await api.insights.get()
    const p = insights.phase_accuracy
    if (!p) return
    const counts = [
      { phase: 'OPENING', count: p.opening },
      { phase: 'MIDDLEGAME', count: p.middlegame },
      { phase: 'ENDGAME', count: p.endgame },
    ]
    const n = narrateObservation(counts, completed)
    if (!n) return
    const top = [...counts].sort((a, b) => b.count - a.count)[0]
    praxBus.emit({ type: 'PATTERN_DETECTED', phase: top.phase, count: top.count })
    narrationStore.set(n, completed, completed)
  } catch {
    /* observation is optional — never let it disturb the run */
  }
}

/** On completion, state the outcome from real numbers. */
async function resolveCompletion(total: number) {
  try {
    const insights = await api.insights.get()
    const motifs = insights.missed_tactics ?? []
    const top = motifs.length ? motifs[0].motif : null
    const p = insights.phase_accuracy
    const mistakes = p ? p.opening + p.middlegame + p.endgame : null
    narrationStore.set(narrateComplete(total, mistakes, top), total, total)
  } catch {
    narrationStore.set(narrateComplete(total, null, null), total, total)
  }
  // The resolved line lingers briefly, then Prax goes quiet.
  setTimeout(() => narrationStore.clear(), 12_000)
}

/** Used by the Stop control so the message updates without waiting for a poll. */
export function markStopping(completed: number, total: number) {
  narrationStore.set(narrateStopping(), completed, total, true)
}

export function markStopped(completed: number, total: number) {
  narrationStore.set(narrateStopped(completed, total), completed, total)
  setTimeout(() => narrationStore.clear(), 8_000)
}
