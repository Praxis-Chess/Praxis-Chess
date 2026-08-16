import { praxBus, type PraxEvent } from '../core/events'
import { praxRuntime } from '../state/runtime'

/**
 * The semantic interaction layer.
 *
 * Components never reason about Prax behaviour — they declare WHAT the user
 * did, and this single table decides how much Prax cares. Adding a button means
 * picking an existing kind, not writing new Prax logic.
 *
 * Deliberately not one-per-DOM-click: most interactions produce a small
 * internal impulse and no state change at all. Prax should read as aware of the
 * interface, not as reacting to it.
 */
export type PraxInteraction =
  | 'PRIMARY_ACTION' // Start session, Sync Now — the user committed to something
  | 'SECONDARY_ACTION' // tab switches, toggles
  | 'EXAMINE' // the user followed Prax's suggestion
  | 'DISMISS' // the user rejected it
  | 'EVIDENCE_OPENED' // investigative — "show me why"
  | 'DRILL_STARTED'
  | 'DRILL_COMPLETED'
  | 'FILTER_CHANGED' // routine; near-invisible
  | 'GAME_SELECTED'
  | 'SYNC_STARTED'
  | 'SYNC_COMPLETED'

interface Response {
  /** Brief energy lift, 0..1. Impulse only — never a state change. */
  attention: number
  /** Some interactions genuinely are state transitions; those go through the bus. */
  event?: PraxEvent
}

const RESPONSES: Record<PraxInteraction, Response> = {
  // Committed actions — Prax leans in briefly.
  PRIMARY_ACTION: { attention: 0.8 },
  EXAMINE: { attention: 0.9 },
  DRILL_STARTED: { attention: 0.7 },
  DRILL_COMPLETED: { attention: 0.6 },

  // Investigative — curiosity, slightly less than commitment.
  EVIDENCE_OPENED: { attention: 0.55 },
  SYNC_STARTED: { attention: 0.6 },
  SYNC_COMPLETED: { attention: 0.45 },

  // Acknowledgement, then back to dormant. The state change is the point;
  // the impulse is only so the dismissal registers visually.
  DISMISS: { attention: 0.25 },

  // Routine. Barely perceptible — this is what keeps Prax from feeling needy.
  SECONDARY_ACTION: { attention: 0.3 },
  GAME_SELECTED: { attention: 0.25 },
  FILTER_CHANGED: { attention: 0.12 },
}

/**
 * Report a semantic interaction. Safe to call from anywhere; components need no
 * knowledge of states, impulses or the bus.
 */
export function praxInteract(kind: PraxInteraction, detail?: { insightId?: string }): void {
  const res = RESPONSES[kind]
  if (!res) return

  // SYNC is a batch arriving — a structural event, not an attention blip.
  if (kind === 'SYNC_STARTED' || kind === 'SYNC_COMPLETED') {
    praxRuntime.motion.fireCrater(kind === 'SYNC_STARTED' ? 1 : 0.6)
  }

  if (kind === 'DISMISS') {
    praxBus.emit({ type: 'INSIGHT_DISMISSED', insightId: detail?.insightId ?? '' })
  } else if (res.event) {
    praxBus.emit(res.event)
  }

  praxRuntime.motion.fireAttention(res.attention)
}
