/**
 * Contract §1 — Application → Prax. Strictly one direction.
 * Prax emits no domain events.
 */

export type PraxFocusTarget = 'insight' | 'game' | 'metric' | 'card'

export type PraxEvent =
  // navigation — synthesized two-phase, see state/routerBridge.ts
  | { type: 'NAVIGATION_START'; from: string; to: string }
  | { type: 'NAVIGATION_END'; to: string; hasAnchor: boolean }

  // analysis lifecycle
  | { type: 'ANALYSIS_STARTED' }
  | { type: 'ANALYSIS_PROGRESS'; completed: number; total: number }
  | { type: 'ANALYSIS_FINISHED' }
  | { type: 'ANALYSIS_STOPPING' }
  // Emitted from real counts, never from the model.
  | { type: 'PATTERN_DETECTED'; phase: string; count: number }

  // semantic findings
  | { type: 'INSIGHT_FOUND'; insightId: string; confidence: number; importance: 'low' | 'medium' | 'high' }
  | { type: 'INSIGHT_DISMISSED'; insightId: string }

  // deliberate attention — post-dwell, never raw hover
  | { type: 'USER_FOCUS'; target: PraxFocusTarget }
  | { type: 'USER_FOCUS_END' }

  // drill feedback — impulse only, no state change
  | { type: 'DRILL_CORRECT' }
  | { type: 'DRILL_WRONG' }

  // a question the player asked, in flight
  | { type: 'QUERY_STARTED' }
  | { type: 'QUERY_FINISHED' }

  // reserved
  | { type: 'RESPONSE_STARTED' }
  | { type: 'RESPONSE_FINISHED' }

export type PraxEventType = PraxEvent['type']

type Handler = (event: PraxEvent) => void

/**
 * Deliberately tiny. No dependencies, no wildcards, no reverse channel —
 * widening this into a general-purpose app bus is the failure mode to avoid.
 */
class PraxBus {
  private handlers = new Set<Handler>()

  emit(event: PraxEvent): void {
    for (const h of this.handlers) h(event)
  }

  on(handler: Handler): () => void {
    this.handlers.add(handler)
    return () => this.handlers.delete(handler)
  }
}

export const praxBus = new PraxBus()
