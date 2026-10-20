import { praxRuntime } from '../prax/state/runtime'
import type { PraxState } from '../prax/motion/model'
import { stepVoiceEnergy } from '../prax/voice'

/**
 * How lively the background is, 0 to 1, from what Prax is doing
 * (BACKGROUND_PLAN.md, section 2). Calm while you read; rises while Prax
 * thinks about a question or an analysis runs; highest while it answers,
 * plus the voice level while it speaks aloud.
 */
export const ENERGY: Record<PraxState, number> = {
  dormant: 0.1,
  aware: 0.2,
  thinking: 0.6,
  insight: 0.45,
  speaking: 0.7,
}

/** The fixed energy of "Calm" and "Still", and of the first frame. */
export const CALM = 0.1

/**
 * A critically damped spring towards the target, so a burst of events never
 * strobes: about 1.2 s to settle.
 */
export class EnergySpring {
  value = CALM
  private velocity = 0
  private readonly k = 5

  /** Called once per frame: it also steps the voice followers (they need real time). */
  target(dtMs: number): number {
    const voice = stepVoiceEnergy(dtMs).overall
    return Math.min(1, ENERGY[praxRuntime.getSnapshot().state] + 0.25 * voice)
  }

  step(dt: number, target: number): number {
    const k = this.k
    const accel = -k * k * (this.value - target) - 2 * k * this.velocity
    this.velocity += accel * dt
    this.value += this.velocity * dt
    this.value = Math.max(0, Math.min(1, this.value))
    return this.value
  }
}
