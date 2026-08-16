import { mulberry32 } from '../core/random'
import { PRAX_CONFIG } from '../core/constants'

/**
 * Per-particle attributes. Written once, never mutated — particle identity is
 * permanent (Contract §5). States change the forces applied to these, never
 * the values themselves.
 *
 * `aRest` is uploaded as the built-in `position` attribute so Three can compute
 * a bounding sphere; the shader aliases it back to aRest.
 */
export interface PraxGeometryData {
  count: number
  aRest: Float32Array //  3n — rest position on the deformed sphere
  aCluster: Float32Array //  3n — centroid of this particle's cluster
  aSeed: Float32Array //   n — 0..1 stable per-particle random
  aPhase: Float32Array //   n — 0..2pi breathing offset
  aSize: Float32Array //   n — 0.6..1.4 size multiplier
}

type Wave = { dx: number; dy: number; dz: number; freq: number; phase: number; amp: number }
type Octave = { freq: number; amp: number }

/**
 * A weighted sum of plane waves over the sphere surface — this is the whole
 * silhouette.
 *
 * Amplitudes are normalised so the result stays in [-1, 1], but they are NOT
 * equal: a strong low-frequency term produces the big lobes while progressively
 * weaker high-frequency terms add surface irregularity. Equal amplitudes cancel
 * each other out and converge back toward a sphere.
 */
function buildWaves(rand: () => number, octaves: readonly Octave[]): Wave[] {
  const total = octaves.reduce((s, o) => s + o.amp, 0)
  return octaves.map(({ freq, amp }) => {
    // Uniform random direction on the unit sphere.
    const z = rand() * 2 - 1
    const t = rand() * Math.PI * 2
    const r = Math.sqrt(1 - z * z)
    return {
      dx: Math.cos(t) * r,
      dy: Math.sin(t) * r,
      dz: z,
      freq,
      phase: rand() * Math.PI * 2,
      amp: amp / total,
    }
  })
}

function sampleWaves(waves: Wave[], x: number, y: number, z: number): number {
  let sum = 0
  for (const w of waves) {
    sum += w.amp * Math.sin((x * w.dx + y * w.dy + z * w.dz) * w.freq + w.phase)
  }
  return sum
}

/**
 * Deterministic geometry. Same seed in, same organism out, every reload.
 *
 * Pipeline (Contract §5):
 *   fibonacci sphere -> low-frequency deformation -> squash -> uneven thinning
 *   -> k-means clusters -> attributes
 */
export function generatePraxGeometry(targetCount: number): PraxGeometryData {
  const rand = mulberry32(PRAX_CONFIG.SEED)

  const deformWaves = buildWaves(rand, PRAX_CONFIG.DEFORM_OCTAVES)
  const thinWaves = buildWaves(rand, [
    { freq: PRAX_CONFIG.THIN_FREQ, amp: 1 },
    { freq: PRAX_CONFIG.THIN_FREQ * 1.7, amp: 0.6 },
    { freq: PRAX_CONFIG.THIN_FREQ * 3.1, amp: 0.35 },
  ])

  // Oversample so that after thinning we land near the target count.
  const candidates = Math.round(targetCount * 1.45)
  const golden = Math.PI * (3 - Math.sqrt(5))

  const kept: number[] = []

  for (let i = 0; i < candidates; i++) {
    // Fibonacci distribution — even coverage without pole clustering.
    const y0 = 1 - (i / (candidates - 1)) * 2
    const ring = Math.sqrt(Math.max(0, 1 - y0 * y0))
    const theta = golden * i
    let x = Math.cos(theta) * ring
    let y = y0
    let z = Math.sin(theta) * ring

    // Uneven thinning: sparse patches keep the edge from reading as a clean circle.
    const density = 0.5 + 0.5 * sampleWaves(thinWaves, x * 2, y * 2, z * 2)
    if (rand() > 1 - PRAX_CONFIG.THIN_STRENGTH * (1 - density)) continue

    // Low-frequency radial deformation — the hand-squeezed quality.
    const d = sampleWaves(deformWaves, x, y, z)
    const scale = PRAX_CONFIG.RADIUS * (1 + d * PRAX_CONFIG.DEFORM_AMPLITUDE)
    x *= scale
    y *= scale * PRAX_CONFIG.SQUASH_Y
    z *= scale

    kept.push(x, y, z)
  }

  const count = kept.length / 3
  const aRest = new Float32Array(kept)
  const aSeed = new Float32Array(count)
  const aPhase = new Float32Array(count)
  const aSize = new Float32Array(count)

  for (let i = 0; i < count; i++) {
    aSeed[i] = rand()
    aPhase[i] = rand() * Math.PI * 2
    aSize[i] = 0.6 + rand() * 0.8
  }

  return { count, aRest, aCluster: buildClusters(aRest, count, rand), aSeed, aPhase, aSize }
}

/**
 * k-means with a few Lloyd iterations. Each particle stores its cluster centroid
 * as a static attribute, which lets the Insight state contract toward local
 * density with a single mix() in the vertex shader — no GPGPU ping-pong needed
 * to give a vertex knowledge of its neighbours (Contract §5).
 */
function buildClusters(pos: Float32Array, count: number, rand: () => number): Float32Array {
  const k = Math.min(PRAX_CONFIG.CLUSTER_COUNT, count)
  const cx = new Float32Array(k)
  const cy = new Float32Array(k)
  const cz = new Float32Array(k)

  for (let c = 0; c < k; c++) {
    const p = Math.floor(rand() * count) * 3
    cx[c] = pos[p]
    cy[c] = pos[p + 1]
    cz[c] = pos[p + 2]
  }

  const assign = new Int32Array(count)

  for (let iter = 0; iter < 4; iter++) {
    for (let i = 0; i < count; i++) {
      const x = pos[i * 3]
      const y = pos[i * 3 + 1]
      const z = pos[i * 3 + 2]
      let best = 0
      let bestD = Infinity
      for (let c = 0; c < k; c++) {
        const dx = x - cx[c]
        const dy = y - cy[c]
        const dz = z - cz[c]
        const d = dx * dx + dy * dy + dz * dz
        if (d < bestD) {
          bestD = d
          best = c
        }
      }
      assign[i] = best
    }

    // Recompute centroids. Empty clusters keep their previous position.
    const sx = new Float64Array(k)
    const sy = new Float64Array(k)
    const sz = new Float64Array(k)
    const n = new Int32Array(k)
    for (let i = 0; i < count; i++) {
      const c = assign[i]
      sx[c] += pos[i * 3]
      sy[c] += pos[i * 3 + 1]
      sz[c] += pos[i * 3 + 2]
      n[c]++
    }
    for (let c = 0; c < k; c++) {
      if (n[c] === 0) continue
      cx[c] = sx[c] / n[c]
      cy[c] = sy[c] / n[c]
      cz[c] = sz[c] / n[c]
    }
  }

  const aCluster = new Float32Array(count * 3)
  for (let i = 0; i < count; i++) {
    const c = assign[i]
    aCluster[i * 3] = cx[c]
    aCluster[i * 3 + 1] = cy[c]
    aCluster[i * 3 + 2] = cz[c]
  }
  return aCluster
}

/** Module-level singleton. Prax is generated once per page load, never on remount. */
let cached: PraxGeometryData | null = null

export function getPraxGeometry(): PraxGeometryData {
  if (!cached) {
    const compact = typeof window !== 'undefined' && window.innerWidth < 900
    cached = generatePraxGeometry(
      compact ? PRAX_CONFIG.PARTICLE_COUNT.compact : PRAX_CONFIG.PARTICLE_COUNT.desktop,
    )
  }
  return cached
}
