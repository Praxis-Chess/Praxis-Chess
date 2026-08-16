import type { PraxNarration } from './progressNarrator'

/**
 * The single narration Prax is currently showing during a long operation.
 *
 * Separate from `praxThoughts` on purpose: a thought is a finding that persists
 * until dismissed, whereas this is a running status that replaces itself and
 * disappears when the work ends.
 */
type Listener = () => void

let current: (PraxNarration & { completed: number; total: number; stopping: boolean }) | null = null
const listeners = new Set<Listener>()

export const narrationStore = {
  get: () => current,

  set(n: PraxNarration | null, completed = 0, total = 0, stopping = false): void {
    current = n ? { ...n, completed, total, stopping } : null
    listeners.forEach((f) => f())
  },

  clear(): void {
    if (current === null) return
    current = null
    listeners.forEach((f) => f())
  },

  subscribe(fn: Listener): () => void {
    listeners.add(fn)
    return () => listeners.delete(fn)
  },
}
