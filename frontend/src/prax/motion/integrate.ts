/**
 * Contract §3 — integration policy is deliberately not uniform.
 * Springs converge; envelopes have shape. The insight impulse was specified as
 * a shape, so it cannot be a spring.
 */

/** Critically-damped spring. Semi-implicit Euler — stable at variable dt. */
export class Spring {
  private vel = 0

  constructor(
    public value: number,
    public target: number,
    /** Time to substantially reach the target, in seconds. */
    private response: number,
  ) {}

  step(dt: number): number {
    const omega = (2 * Math.PI) / this.response
    this.vel += (-2 * omega * this.vel - omega * omega * (this.value - this.target)) * dt
    this.value += this.vel * dt
    return this.value
  }

  /** Jump without easing — used on hard resets only. */
  snap(to: number): void {
    this.value = to
    this.target = to
    this.vel = 0
  }
}

const easeOutCubic = (u: number) => 1 - Math.pow(1 - u, 3)
const easeInOutCubic = (u: number) =>
  u < 0.5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2

/**
 * A transient 0 → amplitude → 0 shape. Semantic state and animation duration
 * stay independent: `insight` the state can persist while its impulse has
 * already decayed to zero.
 */
export class Envelope {
  private elapsed = -1
  private amplitude = 1

  constructor(
    private riseMs: number,
    private fallMs: number,
  ) {}

  fire(amplitude = 1): void {
    this.elapsed = 0
    this.amplitude = amplitude
  }

  get active(): boolean {
    return this.elapsed >= 0
  }

  step(dt: number): number {
    if (this.elapsed < 0) return 0
    this.elapsed += dt * 1000

    if (this.elapsed < this.riseMs) {
      return this.amplitude * easeOutCubic(this.elapsed / this.riseMs)
    }

    const fallU = (this.elapsed - this.riseMs) / this.fallMs
    if (fallU >= 1) {
      this.elapsed = -1
      return 0
    }
    return this.amplitude * (1 - easeInOutCubic(fallU))
  }
}

/**
 * Asymmetric attack/release follower.
 *
 * NOT a spring. Spring is critically damped and symmetric — it rises and falls
 * at the same rate. Anything driven by speech needs to catch onsets fast and
 * decay slowly, or it collapses in every gap between words.
 *
 * Frame-rate independent via exp(), so it behaves identically under the `full`
 * and `reduced` render policies.
 */
export class EnvelopeFollower {
  private value = 0

  constructor(
    private attackMs = 80,
    private releaseMs = 250,
  ) {}

  step(target: number, dtMs: number): number {
    const tau = target > this.value ? this.attackMs : this.releaseMs
    const a = 1 - Math.exp(-dtMs / tau)
    this.value += (target - this.value) * a
    return this.value
  }

  reset(): void {
    this.value = 0
  }

  get current(): number {
    return this.value
  }
}
