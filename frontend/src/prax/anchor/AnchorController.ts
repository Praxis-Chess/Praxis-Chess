import * as THREE from 'three'
import { anchorRegistry } from './registry'
import { screenToWorld, type PraxViewport } from './viewport'
import { Spring } from '../motion/integrate'
import { PRAX_CONFIG } from '../core/constants'
import { praxRuntime } from '../state/runtime'

/**
 * Contract §4 — sole owner of DOM geometry. Emits world-space coordinates;
 * the renderer never sees a rect.
 *
 * Only the ACTIVE anchor is measured, and it is measured every frame. Because
 * getBoundingClientRect is viewport-relative, that single read handles document
 * scroll, nested scroll containers and mid-flight resize with no scroll listener.
 */
export class AnchorController {
  // Relocation is deliberate, not floaty: critically damped so it accelerates
  // immediately, covers most of the distance fast and settles without overshoot.
  // Springs also make "newest target wins" automatic — assigning .target
  // replaces the goal outright, so rapid navigation cannot stack animations.
  private x = new Spring(0, 0, PRAX_CONFIG.RELOCATE_RESPONSE)
  private y = new Spring(0, 0, PRAX_CONFIG.RELOCATE_RESPONSE)
  private scale = new Spring(
    PRAX_CONFIG.AMBIENT_SCALE,
    PRAX_CONFIG.AMBIENT_SCALE,
    PRAX_CONFIG.RELOCATE_RESPONSE,
  )

  private scratch = new THREE.Vector3()
  private vp: PraxViewport = {
    width: 1,
    height: 1,
    fov: PRAX_CONFIG.CAMERA_FOV,
    cameraZ: PRAX_CONFIG.CAMERA_Z,
  }

  /** The screen-space position Prax settled at — used to place the DOM hit-target. */
  readonly screen: { x: number; y: number; scale: number } = {
    x: 0,
    y: 0,
    scale: PRAX_CONFIG.AMBIENT_SCALE,
  }

  setViewport(width: number, height: number): void {
    this.vp.width = width
    this.vp.height = height
  }

  /**
   * The page supplies the placement; this only converts it. No route knowledge
   * lives here — that separation is the point of Contract §4.
   */
  private targetScreen(anchored: boolean): [number, number] {
    const p = anchored
      ? anchorRegistry.getPlacement()
      : { x: PRAX_CONFIG.AMBIENT_POS[0], y: PRAX_CONFIG.AMBIENT_POS[1] }
    return [this.vp.width * p.x, this.vp.height * p.y]
  }

  step(dt: number, out: THREE.Vector3): number {
    const anchored = anchorRegistry.hasAnchor()
    const [sx, sy] = this.targetScreen(anchored)

    if (anchored) {
      this.scale.target = PRAX_CONFIG.ANCHORED_SCALE
      if (praxRuntime.getSnapshot().presence === 'ambient') praxRuntime.setPresence('focused')
    } else {
      this.scale.target = PRAX_CONFIG.AMBIENT_SCALE
      if (praxRuntime.getSnapshot().presence === 'focused') praxRuntime.setPresence('ambient')
    }

    screenToWorld(sx, sy, this.vp, this.scratch)
    this.x.target = this.scratch.x
    this.y.target = this.scratch.y

    const clamped = Math.min(dt, PRAX_CONFIG.DT_CLAMP)
    out.set(this.x.step(clamped), this.y.step(clamped), 0)
    const s = this.scale.step(clamped)

    // Project the settled world position back to screen for the DOM hit-target.
    const visibleHeight = 2 * Math.tan((this.vp.fov * Math.PI) / 180 / 2) * this.vp.cameraZ
    const pxPerWorld = this.vp.height / visibleHeight
    this.screen.x = out.x * pxPerWorld + this.vp.width / 2
    this.screen.y = this.vp.height / 2 - out.y * pxPerWorld
    this.screen.scale = s

    return s
  }

  /** Jump without easing — used on first mount so Prax doesn't fly in from origin. */
  snapToCurrent(): void {
    const anchored = anchorRegistry.hasAnchor()
    const [sx, sy] = this.targetScreen(anchored)
    screenToWorld(sx, sy, this.vp, this.scratch)
    this.x.snap(this.scratch.x)
    this.y.snap(this.scratch.y)
    this.scale.snap(anchored ? PRAX_CONFIG.ANCHORED_SCALE : PRAX_CONFIG.AMBIENT_SCALE)
  }
}
