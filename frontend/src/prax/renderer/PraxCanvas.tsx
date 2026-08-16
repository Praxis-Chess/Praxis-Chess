import { Component, useEffect, useRef, type ReactNode } from 'react'
import * as THREE from 'three'
import { createPraxPoints, type PraxPointsHandle } from './createPraxPoints'
import { setPraxScreenPos } from './screenPos'
import { AnchorController } from '../anchor/AnchorController'
import { screenToWorld } from '../anchor/viewport'
import { Spring } from '../motion/integrate'
import { initPointerTracking, getPointer, dwellMs } from '../interaction/pointer'
import { initPraxVoice, stepVoiceEnergy } from '../voice'
import { praxRuntime } from '../state/runtime'
import { anchorRegistry } from '../anchor/registry'
import { deriveRenderPolicy } from '../state/renderPolicy'
import { praxBus } from '../core/events'
import { PRAX_CONFIG } from '../core/constants'

/**
 * Contract §7 — Prax must be incapable of taking down Praxis.
 * The only failure-matrix entry that protects the app rather than merely
 * degrading appearance.
 */
class PraxBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidCatch(error: Error) {
    console.warn('[prax] disabled after runtime error:', error.message)
  }

  render() {
    return this.state.failed ? null : this.props.children
  }
}

function hasWebGL(): boolean {
  try {
    const c = document.createElement('canvas')
    return !!(c.getContext('webgl2') || c.getContext('webgl'))
  } catch {
    return false
  }
}

const webglAvailable = hasWebGL()
const reducedMotion =
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

function PraxScene() {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    const dpr = Math.min(window.devicePixelRatio || 1, PRAX_CONFIG.DPR_MAX)

    let renderer: THREE.WebGLRenderer
    try {
      renderer = new THREE.WebGLRenderer({
        canvas,
        antialias: true,
        alpha: true,
        powerPreference: 'low-power',
      })
    } catch (err) {
      console.warn('[prax] renderer init failed:', err)
      return
    }
    renderer.setPixelRatio(dpr)
    renderer.setClearColor(0x000000, 0)

    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(PRAX_CONFIG.CAMERA_FOV, 1, 0.1, 100)
    camera.position.set(0, 0, PRAX_CONFIG.CAMERA_Z)

    let handle: PraxPointsHandle
    try {
      handle = createPraxPoints(dpr)
    } catch (err) {
      console.warn('[prax] geometry build failed:', err)
      renderer.dispose()
      return
    }
    scene.add(handle.points)

    const anchors = new AnchorController()
    const worldTarget = new THREE.Vector3()

    // Pointer response: a slow lean toward a resting cursor, plus a local swell.
    // The long response is the point — Prax drifts toward attention, never snaps.
    const tiltX = new Spring(0, 0, PRAX_CONFIG.REACH_RESPONSE)
    const tiltY = new Spring(0, 0, PRAX_CONFIG.REACH_RESPONSE)
    const pointerStrength = new Spring(0, 0, PRAX_CONFIG.REACH_RESPONSE)
    const pointerLocal = new THREE.Vector3()
    const detachPointer = initPointerTracking()

    function viewportForPointer() {
      return {
        width: window.innerWidth,
        height: window.innerHeight,
        fov: PRAX_CONFIG.CAMERA_FOV,
        cameraZ: PRAX_CONFIG.CAMERA_Z,
      }
    }

    function resize() {
      const w = window.innerWidth
      const h = window.innerHeight
      renderer.setSize(w, h, false)
      camera.aspect = w / h
      camera.updateProjectionMatrix()
      anchors.setViewport(w, h)
    }
    resize()
    anchors.snapToCurrent()
    window.addEventListener('resize', resize)

    praxRuntime.start()
    void initPraxVoice()

    if (import.meta.env.DEV) {
      console.info(
        `[prax] ${handle.count} particles · ${PRAX_CONFIG.CLUSTER_COUNT} clusters · seed ${PRAX_CONFIG.SEED} · dpr ${dpr}`,
      )
      ;(window as unknown as Record<string, unknown>).__prax = {
        renderer,
        scene,
        camera,
        handle,
        anchors,
        runtime: praxRuntime,
        bus: praxBus,
        registry: anchorRegistry,
        policy: () => deriveRenderPolicy(reducedMotion),
      }
    }

    // ── The loop. Lives outside React entirely (Contract §3). ──
    const u = handle.uniforms
    let raf = 0
    let last = performance.now()
    let reducedAccum = 0

    function frame(now: number) {
      raf = requestAnimationFrame(frame)

      const rawDt = (now - last) / 1000
      last = now

      // Contract §6 — derived from page visibility, reduced-motion and anchor
      // intersection. Independent of presence.
      const policy = deriveRenderPolicy(reducedMotion)
      if (policy === 'frozen') return

      if (policy === 'reduced') {
        reducedAccum += rawDt
        if (reducedAccum < 1 / PRAX_CONFIG.REDUCED_FPS) return
        reducedAccum = 0
      }

      const dt = Math.min(rawDt, PRAX_CONFIG.DT_CLAMP)

      // Audio bands -> attack/release follower -> motion model, before step()
      // so speech is folded in on the same frame it is measured.
      stepVoiceEnergy(rawDt * 1000)

      praxRuntime.motion.step(dt)
      const m = praxRuntime.motion.current

      u.uTime.value += dt
      u.uEnergy.value = m.energy
      u.uTurbulence.value = m.turbulence
      u.uCoherence.value = m.coherence
      u.uBreathing.value = m.breathing
      u.uExpansion.value = m.expansion
      u.uInsight.value = m.insight
      u.uRimIntensity.value = m.rimIntensity
      u.uBrightness.value = m.brightness
      u.uSpeaking.value = m.speaking
      u.uSweep.value = m.sweep
      u.uBristle.value = m.bristle
      u.uCrater.value = m.crater
      ;(u.uCraterDir.value as THREE.Vector3).set(...praxRuntime.motion.craterDir)

      const scale = anchors.step(dt, worldTarget)
      handle.points.position.copy(worldTarget)
      handle.points.scale.setScalar(scale)

      // Particle diameter tracks the object's scale so density reads the same
      // at every size — without this, growing Prax would just thin the field.
      u.uParticlePx.value = PRAX_CONFIG.PARTICLE_PX * (scale / PRAX_CONFIG.ANCHORED_SCALE)

      // ── Pointer: Prax responds to a RESTING cursor, never to movement. ──
      const ptr = getPointer()
      const ox = ptr.x - anchors.screen.x
      const oy = ptr.y - anchors.screen.y
      const dist = Math.hypot(ox, oy)

      // Prax's own rendered radius, so "close" scales with how big it is.
      const visibleH = 2 * Math.tan((PRAX_CONFIG.CAMERA_FOV * Math.PI) / 180 / 2) * PRAX_CONFIG.CAMERA_Z
      const radiusPx = scale * (window.innerHeight / visibleH)
      const reach = radiusPx * PRAX_CONFIG.REACH_RADIUS_MULT

      const dwelling =
        ptr.active &&
        !reducedMotion &&
        dwellMs(now) >= PRAX_CONFIG.DWELL_MS &&
        dist < reach

      if (dwelling) {
        // Direction is normalised against the reach radius; the distance fade is
        // a SEPARATE term. Folding the fade into the direction makes the two
        // cancel and caps the tilt at ~4deg regardless of MAX_TILT.
        const dx = THREE.MathUtils.clamp(ox / reach, -1, 1)
        const dy = THREE.MathUtils.clamp(oy / reach, -1, 1)
        const fade = 1 - THREE.MathUtils.smoothstep(dist, reach * 0.35, reach)

        tiltY.target = dx * PRAX_CONFIG.MAX_TILT * fade
        tiltX.target = -dy * PRAX_CONFIG.MAX_TILT * fade
        pointerStrength.target = fade
      } else {
        tiltY.target = 0
        tiltX.target = 0
        pointerStrength.target = 0
      }

      handle.points.rotation.set(tiltX.step(dt), tiltY.step(dt), 0)
      u.uPointerStrength.value = pointerStrength.step(dt)

      // The shader works in object-local space, so the cursor must be converted
      // through the object's full transform — position, scale AND rotation.
      handle.points.updateMatrixWorld()
      screenToWorld(ptr.x, ptr.y, viewportForPointer(), pointerLocal)
      handle.points.worldToLocal(pointerLocal)
      ;(u.uPointer.value as THREE.Vector3).copy(pointerLocal)

      setPraxScreenPos(anchors.screen.x, anchors.screen.y, anchors.screen.scale)

      renderer.render(scene, camera)
    }
    raf = requestAnimationFrame(frame)

    // §7 — context loss must not leave a dead canvas behind.
    function onLost(e: Event) {
      e.preventDefault()
      cancelAnimationFrame(raf)
      console.warn('[prax] webgl context lost')
    }
    function onRestored() {
      console.info('[prax] webgl context restored')
      resize()
      last = performance.now()
      raf = requestAnimationFrame(frame)
    }
    canvas.addEventListener('webglcontextlost', onLost)
    canvas.addEventListener('webglcontextrestored', onRestored)

    return () => {
      cancelAnimationFrame(raf)
      detachPointer()
      window.removeEventListener('resize', resize)
      canvas.removeEventListener('webglcontextlost', onLost)
      canvas.removeEventListener('webglcontextrestored', onRestored)
      praxRuntime.stop()
      scene.remove(handle.points)
      handle.dispose()
      renderer.dispose()
    }
  }, [])

  return <canvas ref={canvasRef} style={{ display: 'block', width: '100%', height: '100%' }} />
}

/**
 * Single persistent canvas. Mounted once, above route content, never unmounted —
 * so the organism is never destroyed and rebuilt on navigation (Contract §4).
 *
 * pointer-events is `none` permanently: the canvas is fullscreen and fixed, so
 * any other value would swallow every click in the app.
 */
export function PraxCanvas() {
  if (!webglAvailable) return null

  return (
    <PraxBoundary>
      <div
        aria-hidden="true"
        style={{
          position: 'fixed',
          inset: 0,
          zIndex: 50, // above the sync banner, below the nav (100)
          pointerEvents: 'none',
        }}
      >
        <PraxScene />
      </div>
    </PraxBoundary>
  )
}
