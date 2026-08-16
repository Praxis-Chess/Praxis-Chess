/**
 * Seeded PRNG. Every structural random decision in Prax draws from this —
 * silhouette deformation, size variation, thinning, cluster assignment, phases.
 * The organism must be byte-identical on every reload; only uTime makes it move.
 *
 * Contract §8.
 */
export function mulberry32(seed: number): () => number {
  let s = seed
  return () => {
    s |= 0
    s = (s + 0x6d2b79f5) | 0
    let t = Math.imul(s ^ (s >>> 15), 1 | s)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
