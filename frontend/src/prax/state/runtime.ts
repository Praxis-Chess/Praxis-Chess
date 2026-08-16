import { praxBus, type PraxEvent } from '../core/events'
import { MotionModel, type PraxState } from '../motion/model'

/** Contract §6 — semantic axis. How much does Prax matter right now? */
export type PraxPresence = 'absent' | 'ambient' | 'focused' | 'engaged'

/** Contract §6 — performance axis. Independent of presence. */
export type PraxRenderPolicy = 'full' | 'reduced' | 'frozen'

/**
 * Contract §2 — a plain transition table. Five states, no statechart library.
 * `NAVIGATION_START` appearing under every long-lived state IS the interruption
 * rule, stated once and readable at a glance.
 */
const TRANSITIONS: Record<PraxState, Partial<Record<PraxEvent['type'], PraxState>>> = {
  dormant: {
    USER_FOCUS: 'aware',
    QUERY_STARTED: 'thinking',
    ANALYSIS_STARTED: 'thinking',
    INSIGHT_FOUND: 'insight',
    NAVIGATION_END: 'aware', // guarded on hasAnchor below
  },
  aware: {
    USER_FOCUS_END: 'dormant',
    QUERY_STARTED: 'thinking',
    ANALYSIS_STARTED: 'thinking',
    INSIGHT_FOUND: 'insight',
    NAVIGATION_START: 'dormant',
  },
  thinking: {
    INSIGHT_FOUND: 'insight',
    QUERY_FINISHED: 'dormant',
    ANALYSIS_FINISHED: 'dormant',
    NAVIGATION_START: 'dormant',
  },
  insight: {
    RESPONSE_STARTED: 'speaking',
    QUERY_STARTED: 'thinking',
    INSIGHT_DISMISSED: 'dormant',
    ANALYSIS_STARTED: 'thinking',
    NAVIGATION_START: 'dormant',
  },
  speaking: {
    RESPONSE_FINISHED: 'dormant',
    ANALYSIS_STARTED: 'thinking',
    NAVIGATION_START: 'dormant',
  },
}

export interface PraxSnapshot {
  state: PraxState
  presence: PraxPresence
  insightId: string | null
  insightImportance: 'low' | 'medium' | 'high' | null
}

type Listener = () => void

/**
 * Owns semantic state and drives the motion model. Deliberately outside React:
 * the renderer reads `motion.current` every frame and must never cause a render.
 * React subscribes only to the coarse snapshot, which changes rarely.
 */
class PraxRuntime {
  readonly motion = new MotionModel()

  private snapshot: PraxSnapshot = {
    state: 'dormant',
    presence: 'ambient',
    insightId: null,
    insightImportance: null,
  }

  /**
   * Analysis is a sustained CONDITION, not a moment. ANALYSIS_STARTED is
   * edge-triggered, so without this flag a single ignored event lost the signal
   * permanently — which is exactly what left Prax dormant through a whole run.
   */
  private analysisRunning = false

  private listeners = new Set<Listener>()
  private detach: (() => void) | null = null

  start(): void {
    if (this.detach) return
    this.detach = praxBus.on((e) => this.send(e))
  }

  stop(): void {
    this.detach?.()
    this.detach = null
  }

  getSnapshot = (): PraxSnapshot => this.snapshot

  subscribe = (fn: Listener): (() => void) => {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  setPresence(presence: PraxPresence): void {
    if (this.snapshot.presence === presence) return
    this.snapshot = { ...this.snapshot, presence }
    this.notify()
  }

  send(event: PraxEvent): void {
    if (event.type === 'ANALYSIS_STARTED') this.analysisRunning = true
    if (event.type === 'ANALYSIS_FINISHED') this.analysisRunning = false

    // Querying gets its own motion channel rather than riding on `thinking`.
    // The sweep already means "examining the library"; a question in flight is
    // a different act and must not borrow that reading.
    if (event.type === 'QUERY_STARTED') this.motion.setQuerying(true)
    if (event.type === 'QUERY_FINISHED') this.motion.setQuerying(false)

    // ── Impulses fire independently of any transition (Contract §2). ──
    switch (event.type) {
      case 'INSIGHT_FOUND':
        // Importance scales the impulse — a low-confidence finding shouldn't
        // announce itself as loudly as a high-confidence one.
        this.motion.fireInsight(
          event.importance === 'high' ? 1 : event.importance === 'medium' ? 0.75 : 0.5,
        )
        break
      case 'PATTERN_DETECTED':
        // The wave resolves into coherence and briefly lights the surface —
        // reuses the insight impulse rather than inventing another state.
        this.motion.fireInsight(0.55)
        break
      case 'ANALYSIS_STARTED':
        // Prax becomes attentive before it starts sweeping.
        this.motion.fireAttention(0.7)
        break
      case 'ANALYSIS_PROGRESS':
        // Each completed game gives the field a small kick, so a long analysis
        // run reads as ongoing work rather than a static `thinking` pose.
        this.motion.fireTurbulenceSpike(0.25)
        break
      case 'DRILL_CORRECT':
        this.motion.fireInsight(0.6)
        break
      case 'DRILL_WRONG':
        this.motion.fireTurbulenceSpike(0.8)
        break
    }

    // NAVIGATION_END only promotes to `aware` when the destination has an anchor.
    if (event.type === 'NAVIGATION_END' && !event.hasAnchor) {
      this.setPresence('ambient')
      return
    }

    let next = TRANSITIONS[this.snapshot.state][event.type]
    if (!next) return

    // Leaving a thought while a run is still going returns to thinking, not
    // quiet — the work hasn't stopped just because the card was dismissed.
    if (next === 'dormant' && this.analysisRunning && event.type !== 'NAVIGATION_START') {
      next = 'thinking'
    }

    let insightId = this.snapshot.insightId
    let insightImportance = this.snapshot.insightImportance
    if (event.type === 'INSIGHT_FOUND') {
      insightId = event.insightId
      insightImportance = event.importance
    } else if (next === 'dormant') {
      insightId = null
      insightImportance = null
    }

    this.snapshot = { ...this.snapshot, state: next, insightId, insightImportance }
    this.motion.setState(next)
    this.notify()
  }

  private notify(): void {
    for (const fn of this.listeners) fn()
  }
}

export const praxRuntime = new PraxRuntime()
