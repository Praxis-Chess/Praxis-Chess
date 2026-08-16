/**
 * Every tunable in one place. Nothing here is architecture — Contract §8.
 */
export const PRAX_CONFIG = {
  SEED: 18371,

  /** Target survivor count after thinning. Actual count is reported by generate(). */
  PARTICLE_COUNT: { desktop: 1600, compact: 900 },
  CLUSTER_COUNT: 16,
  RADIUS: 1.0,

  /**
   * Silhouette. fBm-style octaves: amplitude falls as frequency rises, so the
   * form gets big lobes AND finer surface variation. Equal-amplitude waves
   * (the previous approach) average out into a near-perfect sphere.
   */
  DEFORM_OCTAVES: [
    { freq: 1.05, amp: 1.0 },
    { freq: 1.7, amp: 0.62 },
    { freq: 2.6, amp: 0.38 },
    { freq: 3.9, amp: 0.22 },
    { freq: 5.6, amp: 0.13 },
  ] as const,
  /** Radial variation as a fraction of radius. 0.13 read as a sphere; this does not. */
  DEFORM_AMPLITUDE: 0.75,
  SQUASH_Y: 0.91,

  /** Uneven density. Patches thin out so the edge never reads as a clean circle. */
  THIN_FREQ: 1.35,
  THIN_STRENGTH: 0.5,

  /** Motion — unused in Phase 0, wired in Phase 1. */
  NOISE_FREQ: 1.8,
  NOISE_SPEED: 0.12,
  BREATH_RATE: 0.45,

  /**
   * Render. PARTICLE_PX is a real CSS-pixel diameter, not a world-space size —
   * decoupling it from the object's scale is what keeps the points discrete
   * instead of merging into a solid mass.
   */
  PARTICLE_PX: 2.5,
  CAMERA_Z: 4.5,
  CAMERA_FOV: 45,
  DPR_MAX: 2,

  /** Anchoring — a meaningful presence, not a decorative icon. +20% over 0.56. */
  ANCHORED_SCALE: 0.67,
  AMBIENT_SCALE: 0.56,

  /**
   * Fallback placement only. Real placement comes per-page from PraxAnchor —
   * the page decides where Prax belongs, the renderer decides how it gets there.
   */
  AMBIENT_POS: [0.72, 0.46] as const,

  /**
   * Relocation spring response, seconds. Short enough that movement starts
   * immediately and most distance is covered in ~250ms, settling by ~600ms.
   * Critically damped, so no overshoot and no float.
   */
  RELOCATE_RESPONSE: 0.42,

  /**
   * Pointer response. Prax reacts to STILLNESS, not motion — a cursor sweeping
   * past leaves it undisturbed. It only reaches once the cursor has rested
   * nearby, and it reaches slowly.
   */
  MAX_TILT: 0.45,
  /** How long the cursor must be at rest before Prax responds at all. */
  DWELL_MS: 1000,
  /** Reach engages within this multiple of Prax's own rendered radius. */
  REACH_RADIUS_MULT: 2.4,
  /** Spring response for reaching, in seconds. Deliberately slow. */
  REACH_RESPONSE: 2.4,

  /**
   * Sustained rim while the insight state is active. The impulse alone fires
   * when the query resolves — seconds before anyone looks at the card — so the
   * flash was always over before it could be seen. Pink means "Prax has
   * something for you", and that stays true for as long as the card is up.
   */
  RIM_FLOOR: 0.5,

  /**
   * Speaking never reaches a fully pink body — it stays a strong tint so the
   * field keeps its white/grey character and its depth reads.
   */
  SPEAK_COLOR_MAX: 0.55,

  /** Body colour ramp when speech starts/ends. Asymmetric by design. */
  SPEAK_COLOR_ATTACK_MS: 200,
  SPEAK_COLOR_RELEASE_MS: 420,

  /**
   * QUERY — fine quills while a question is in flight.
   *
   * Its own channel rather than a louder `thinking`: the analyze sweep already
   * means "working across the library", and a question is a different act.
   * Radial displacement stays small; the read comes from the RATE, not the
   * size — a big slow spike would look like breathing, which is the resting
   * behaviour it has to stay distinguishable from.
   */
  BRISTLE_AMPLITUDE: 0.085,
  /** Oscillations per second. High enough to read as agitation, not a pulse. */
  BRISTLE_RATE: 19.0,
  /** Which particles spike — lower means a sparser, spikier subset. */
  BRISTLE_COVERAGE: 0.42,
  /** Fast in so it answers the click; slower out so it doesn't snap off. */
  BRISTLE_ATTACK_MS: 160,
  BRISTLE_RELEASE_MS: 520,

  /** Render policy (§6). */
  REDUCED_FPS: 20,
  /** Above this intersection ratio the anchor counts as visible. */
  VISIBLE_RATIO: 0.5,

  /** Runtime — unused in Phase 0. */
  DT_CLAMP: 1 / 30,
  FOCUS_DWELL_MS: 200,
} as const

/** Light comes from upper-left, slightly toward the viewer. */
export const PRAX_LIGHT_DIR: readonly [number, number, number] = [-0.5, 0.72, 0.48]

export const PRAX_COLORS = {
  /**
   * Strictly neutral. The base state carries no hue at all — orchid appears
   * only when Prax has something for you, which is what keeps it a signal.
   */
  base: '#E8E8E8',
  /** --orchid. Semantic, never decorative. Gated behind uRimIntensity. */
  rim: '#E7A6D6',
  /** Body colour while speaking — Prax expressing itself, not discovering. */
  speak: '#E7A6D6',
} as const
