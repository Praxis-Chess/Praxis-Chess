/**
 * An icosphere, built by subdividing an icosahedron.
 *
 * WHY NOT A UV SPHERE. A UV sphere places vertices on latitude rings, so the
 * poles are crowded and the equator is sparse. As a point cloud that reads
 * immediately as a grid — you see the latitude lines rather than a sphere, and
 * a noise displacement over it produces visible banding along the rings.
 *
 * An icosahedron subdivided N times gives near-uniform spacing everywhere, with
 * no poles and no seam. The cloud then reads as a surface rather than a mesh.
 *
 * WHY NOT A FIBONACCI SPHERE (what this replaces). Fibonacci is also uniform,
 * but its points have no neighbour relationship — adjacent indices are on
 * opposite sides of the ball. That is fine for scattering, and wrong here: the
 * whole point of displacing along the normal with SMOOTH noise is that
 * neighbouring points move together, which is what makes the surface look
 * connected rather than like independent specks.
 *
 * Vertices only. Faces are discarded — this is a point cloud, and the triangles
 * exist purely to generate the positions.
 */

/** 10 * 4^detail + 2 unique vertices. */
export function icosphereVertices(detail: number): Float32Array {
  const t = (1 + Math.sqrt(5)) / 2

  // The 12 corners of a regular icosahedron: three mutually perpendicular
  // golden rectangles.
  let verts: [number, number, number][] = [
    [-1, t, 0], [1, t, 0], [-1, -t, 0], [1, -t, 0],
    [0, -1, t], [0, 1, t], [0, -1, -t], [0, 1, -t],
    [t, 0, -1], [t, 0, 1], [-t, 0, -1], [-t, 0, 1],
  ]
  let faces: [number, number, number][] = [
    [0, 11, 5], [0, 5, 1], [0, 1, 7], [0, 7, 10], [0, 10, 11],
    [1, 5, 9], [5, 11, 4], [11, 10, 2], [10, 7, 6], [7, 1, 8],
    [3, 9, 4], [3, 4, 2], [3, 2, 6], [3, 6, 8], [3, 8, 9],
    [4, 9, 5], [2, 4, 11], [6, 2, 10], [8, 6, 7], [9, 8, 1],
  ]

  // Midpoint cache, so an edge shared by two faces yields ONE vertex. Without
  // it the count explodes and coincident points render as double-bright specks.
  for (let d = 0; d < detail; d++) {
    const midpoints = new Map<string, number>()
    const next: [number, number, number][] = []

    const midpoint = (a: number, b: number): number => {
      const key = a < b ? `${a}_${b}` : `${b}_${a}`
      const cached = midpoints.get(key)
      if (cached !== undefined) return cached

      const [ax, ay, az] = verts[a]
      const [bx, by, bz] = verts[b]
      // Pushed back onto the unit sphere — a plain midpoint would sink toward
      // the centre and the ball would slowly become a polyhedron.
      const x = (ax + bx) / 2, y = (ay + by) / 2, z = (az + bz) / 2
      const len = Math.hypot(x, y, z)
      verts.push([x / len, y / len, z / len])

      const index = verts.length - 1
      midpoints.set(key, index)
      return index
    }

    for (const [a, b, c] of faces) {
      const ab = midpoint(a, b)
      const bc = midpoint(b, c)
      const ca = midpoint(c, a)
      next.push([a, ab, ca], [b, bc, ab], [c, ca, bc], [ab, bc, ca])
    }
    faces = next
  }

  // Normalise the original 12; the subdivided ones already are.
  verts = verts.map(([x, y, z]) => {
    const len = Math.hypot(x, y, z)
    return [x / len, y / len, z / len]
  })

  const out = new Float32Array(verts.length * 3)
  for (let i = 0; i < verts.length; i++) {
    out[i * 3] = verts[i][0]
    out[i * 3 + 1] = verts[i][1]
    out[i * 3 + 2] = verts[i][2]
  }
  return out
}

/** How many unique vertices a given detail produces. */
export function icosphereCount(detail: number): number {
  return 10 * Math.pow(4, detail) + 2
}

/** The smallest detail reaching `target` points, capped so a phone stays alive. */
export function detailFor(target: number, maxDetail = 5): number {
  for (let d = 1; d <= maxDetail; d++) {
    if (icosphereCount(d) >= target) return d
  }
  return maxDetail
}
