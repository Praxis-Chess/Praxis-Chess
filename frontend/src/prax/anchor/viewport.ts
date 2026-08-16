import * as THREE from 'three'

export interface PraxViewport {
  width: number
  height: number
  fov: number
  cameraZ: number
}

/**
 * Contract §4 — the ONLY screen→world conversion in the system.
 *
 * Perspective is kept (rather than orthographic pixel-matching) because
 * volume-without-mesh is the entire rendering premise, and orthographic
 * discards the depth cue that produces it.
 */
export function screenToWorld(
  px: number,
  py: number,
  vp: PraxViewport,
  out: THREE.Vector3,
): THREE.Vector3 {
  const visibleHeight = 2 * Math.tan((vp.fov * Math.PI) / 180 / 2) * vp.cameraZ
  const worldPerPixel = visibleHeight / vp.height
  return out.set((px - vp.width / 2) * worldPerPixel, (vp.height / 2 - py) * worldPerPixel, 0)
}
