import { Spring, Envelope, EnvelopeFollower } from './integrate'
import { PRAX_CONFIG } from '../core/constants'

/** Contract §3 */
export interface PraxMotionParams {
  energy: number
  turbulence: number
  coherence: number
  breathing: number
  expansion: number
  rimIntensity: number
}

export type PraxState = 'dormant' | 'aware' | 'thinking' | 'insight' | 'speaking'

/** The sprung parameters. `rimIntensity` is driven by the impulse, not sprung. */
export type TunableParam = Exclude<keyof PraxMotionParams, 'rimIntensity'>

/** Contract §3 target table. Every number here is tunable, none is structural. */
export const STATE_TARGETS: Record<PraxState, Record<TunableParam, number>> = {
  dormant:  { energy: 0.15, turbulence: 0.03, coherence: 0.85, breathing: 0.08, expansion: 0.15 },
  aware:    { energy: 0.22, turbulence: 0.08, coherence: 0.90, breathing: 0.10, expansion: 0.02 },
  thinking: { energy: 0.35, turbulence: 0.30, coherence: 0.55, breathing: 0.16, expansion: 0.04 },
  insight:  { energy: 0.75, turbulence: 0.12, coherence: 0.95, breathing: 0.12, expansion: -0.20 },
  speaking: { energy: 0.45, turbulence: 1.00, coherence: 0.70, breathing: 0.25, expansion: 0.02 },
}

/**
 * Targets in, smoothed values out. The state machine writes targets and never
 * touches current values — otherwise every state would invent its own easing.
 */
export class MotionModel {
  // Initial values match the dormant target so Prax starts settled, not easing in.
  private energy = new Spring(0.15, 0.15, 0.6)
  private turbulence = new Spring(0.03, 0.03, 0.8)
  private coherence = new Spring(0.85, 0.85, 0.5)
  private breathing = new Spring(0.08, 0.08, 1.2)
  private expansion = new Spring(0.15, 0.15, 0.7)

  private insightEnv = new Envelope(180, 720)
  private spikeEnv = new Envelope(90, 310)
  /** Brief attentiveness from a user interaction. Impulse, never a state. */
  private attentionEnv = new Envelope(140, 620)

  /** ANALYZE — sustained while thinking, so examination reads as ongoing work. */
  private sweepTarget = 0
  private sweep = new EnvelopeFollower(600, 900)

  /** QUERY — fine high-frequency quills, sustained while a question is open. */
  private bristleTarget = 0
  private bristle = new EnvelopeFollower(
    PRAX_CONFIG.BRISTLE_ATTACK_MS,
    PRAX_CONFIG.BRISTLE_RELEASE_MS,
  )

  /** SYNC — a one-shot structural dent that forms and recovers. */
  private craterEnv = new Envelope(260, 1100)
  readonly craterDir: [number, number, number] = [0, 1, 0]

  /**
   * Body colour mix, 0..1. Driven by the STATE, not by audio amplitude —
   * the colour marks "Prax is expressing itself", so it must hold steady
   * through the gaps between words.
   */
  private speakingTarget = 0
  /** Sustained rim while the insight state holds, independent of the impulse. */
  private rimFloorTarget = 0
  private rimFloor = new EnvelopeFollower(220, 500)
  private speakingColor = new EnvelopeFollower(
    PRAX_CONFIG.SPEAK_COLOR_ATTACK_MS,
    PRAX_CONFIG.SPEAK_COLOR_RELEASE_MS,
  )

  // Audio bands, written by the voice layer each frame. Already smoothed by an
  // attack/release follower, so no spring here.
  private spkLow = 0
  private spkMid = 0
  private spkHigh = 0

  /**
   * Read by the renderer each frame. Never allocated per-frame.
   *
   * Pointer response deliberately lives in the renderer, not here: it needs
   * screen-space geometry the motion model has no access to. Keeping a
   * `pointerStrength` here too gave the uniform two writers, one of them dead.
   */
  readonly current: PraxMotionParams & {
    insight: number
    brightness: number
    speaking: number
    sweep: number
    crater: number
    bristle: number
  } = {
    energy: 0.15,
    turbulence: 0.03,
    coherence: 0.85,
    breathing: 0.08,
    expansion: 0.15,
    rimIntensity: 0,
    insight: 0,
    brightness: 0,
    speaking: 0,
    sweep: 0,
    crater: 0,
    bristle: 0,
  }

  private get springs(): Record<TunableParam, Spring> {
    return {
      energy: this.energy,
      turbulence: this.turbulence,
      coherence: this.coherence,
      breathing: this.breathing,
      expansion: this.expansion,
    }
  }

  setState(state: PraxState): void {
    const targets = STATE_TARGETS[state]
    const springs = this.springs
    for (const key of Object.keys(targets) as TunableParam[]) {
      springs[key].target = targets[key]
    }
    // Colour and rim follow the state automatically — no wiring at the call site.
    this.speakingTarget = state === 'speaking' ? PRAX_CONFIG.SPEAK_COLOR_MAX : 0
    this.rimFloorTarget = state === 'insight' ? PRAX_CONFIG.RIM_FLOOR : 0
    this.sweepTarget = state === 'thinking' ? 1 : 0
  }

  /** Debug-panel override — bypasses the state table. */
  setTarget(key: TunableParam, value: number): void {
    this.springs[key].target = value
  }

  fireInsight(amplitude = 1): void {
    this.insightEnv.fire(amplitude)
  }

  fireTurbulenceSpike(amplitude = 0.8): void {
    this.spikeEnv.fire(amplitude)
  }

  /**
   * Speech bands from the voice layer. Rides on top of the current state
   * exactly as attention does — it never displaces the state underneath.
   */
  setSpeaking(low: number, mid: number, high: number): void {
    this.spkLow = low
    this.spkMid = mid
    this.spkHigh = high
  }

  /**
   * A user did something meaningful. Lifts energy and tightens coherence for
   * under a second — Prax notices, then lets go. No state change, by design.
   */
  fireAttention(amplitude = 0.6): void {
    this.attentionEnv.fire(amplitude)
  }

  /**
   * A batch arrived. Dents the field at a fresh random point each time, so
   * repeated syncs read as separate physical events rather than one repeated
   * animation.
   */
  /** A question is open. Sustained, not an impulse — it lasts as long as the wait. */
  setQuerying(active: boolean): void {
    this.bristleTarget = active ? 1 : 0
  }

  fireCrater(amplitude = 1): void {
    const z = Math.random() * 2 - 1
    const t = Math.random() * Math.PI * 2
    const r = Math.sqrt(1 - z * z)
    this.craterDir[0] = Math.cos(t) * r
    this.craterDir[1] = Math.sin(t) * r
    this.craterDir[2] = z
    this.craterEnv.fire(amplitude)
  }

  step(rawDt: number): void {
    // Contract §3 — unclamped dt after a backgrounded tab diverges the springs.
    const dt = Math.min(rawDt, PRAX_CONFIG.DT_CLAMP)
    const c = this.current

    // Attention rides on top of whatever state is current — it lifts the field
    // briefly without displacing the semantic state underneath it.
    const att = this.attentionEnv.step(dt)

    c.energy = Math.min(1, this.energy.step(dt) + att * 0.4)
    c.coherence = Math.min(1, this.coherence.step(dt) + att * 0.08)
    c.breathing = this.breathing.step(dt)

    // Voice Plan §6 — low: body, mid: internal drift, high: brightness.
    // Deliberately small: a field that throbs per syllable is a generic orb.
    c.expansion = this.expansion.step(dt) + this.spkLow * 0.18
    c.brightness = this.spkHigh * 0.08

    c.sweep = this.sweep.step(this.sweepTarget, dt * 1000)
    c.bristle = this.bristle.step(this.bristleTarget, dt * 1000)
    c.crater = this.craterEnv.step(dt)
    c.insight = this.insightEnv.step(dt)
    c.turbulence = this.turbulence.step(dt) + this.spikeEnv.step(dt) * 0.35 + this.spkMid * 0.12

    c.speaking = this.speakingColor.step(this.speakingTarget, dt * 1000)

    // Rim = the discovery flash OR the sustained "I have something for you"
    // floor, whichever is stronger. The impulse gives the moment of discovery
    // its punch; the floor keeps the signal alive while the card is on screen.
    const floor = this.rimFloor.step(this.rimFloorTarget, dt * 1000)
    c.rimIntensity = Math.max(c.insight, floor)
  }
}
