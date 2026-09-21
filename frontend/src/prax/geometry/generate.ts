import { mulberry32 } from '../core/random'
import { PRAX_CONFIG } from '../core/constants'
import { detailFor, icosphereCount, icosphereVertices } from './icosphere'

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
  aRest: Float32Array //  3n — rest position on the UNDEFORMED sphere
  aCluster: Float32Array //  3n — centroid of this particle's cluster
  aSeed: Float32Array //   n — 0..1 stable per-particle random
  aPhase: Float32Array //   n — 0..2pi breathing offset
  aSize: Float32Array //   n — 0.6..1.4 size multiplier
}

/**
 * Deterministic geometry. Same seed in, same organism out, every reload.
 *
 * Pipeline (Contract §5):
 *   icosphere vertices -> per-particle attributes -> k-means clusters
 *
 * Deformation is NOT baked. It is sampled from 3D noise along each vertex's
 * normal every frame, in the vertex shader — see shaders.ts step 0.
 */
export function generatePraxGeometry(targetCount: number): PraxGeometryData {
  const rand = mulberry32(PRAX_CONFIG.SEED)

  // ── The base is a PERFECT sphere, and that is the change. ──
  //
  // Previously the deformation was BAKED here: a fixed sum of plane waves
  // squashed the ball once, at load, and the shader only jittered the result.
  // So Prax was a static lumpy object with some animation on top — the lumps
  // never moved, because they were geometry rather than motion.
  //
  // Now the rest position is an undeformed unit sphere and EVERY bulge comes
  // from noise sampled per frame in the vertex shader. The surface reorganises
  // continuously instead of wobbling in place.
  const detail = detailFor(targetCount)
  const aRest = icosphereVertices(detail)
  const count = aRest.length / 3

  // Radius applied here rather than in the shader so the bounding sphere Three
  // computes from `position` is the real one.
  for (let i = 0; i < aRest.length; i++) aRest[i] *= PRAX_CONFIG.RADIUS

  const aSeed = new Float32Array(count)
  const aPhase = new Float32Array(count)
  const aSize = new Float32Array(count)

  for (let i = 0; i < count; i++) {
    aSeed[i] = rand()
    aPhase[i] = rand() * Math.PI * 2
    // Modest spread. Displacement drives most of the size variation now, and a
    // wide static range on top of that reads as noise rather than as structure.
    aSize[i] = 0.78 + rand() * 0.44
  }

  return { count, aRest, aCluster: buildClusters(aRest, count, rand), aSeed, aPhase, aSize }
}

/** Vertex count for a target, so callers can report what they actually got. */
export function icosphereSizeFor(target: number): number {
  return icosphereCount(detailFor(target))
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
