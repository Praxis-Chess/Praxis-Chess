import * as THREE from 'three'
import { getPraxGeometry } from '../geometry/generate'
import { PRAX_VERTEX, PRAX_FRAGMENT } from './shaders'
import { PRAX_CONFIG, PRAX_COLORS, PRAX_LIGHT_DIR } from '../core/constants'

export interface PraxPointsHandle {
  points: THREE.Points
  uniforms: Record<string, THREE.IUniform>
  count: number
  dispose(): void
}

/**
 * One BufferGeometry, one Points, one ShaderMaterial, one draw call.
 *
 * Deliberately not a React component: the contract puts the animation loop
 * outside React entirely (§3), and there is exactly one object here — a
 * declarative scene graph would be pure overhead.
 *
 * Phase 0 holds every motion uniform at zero so the raw generated silhouette is
 * visible with nothing layered on top. Phase 1 begins feeding these.
 */
export function createPraxPoints(pixelRatio: number): PraxPointsHandle {
  const data = getPraxGeometry()

  const geometry = new THREE.BufferGeometry()
  // position IS aRest — Three needs `position` to compute a bounding sphere.
  geometry.setAttribute('position', new THREE.BufferAttribute(data.aRest, 3))
  geometry.setAttribute('aCluster', new THREE.BufferAttribute(data.aCluster, 3))
  geometry.setAttribute('aSeed', new THREE.BufferAttribute(data.aSeed, 1))
  geometry.setAttribute('aPhase', new THREE.BufferAttribute(data.aPhase, 1))
  geometry.setAttribute('aSize', new THREE.BufferAttribute(data.aSize, 1))

  const uniforms: Record<string, THREE.IUniform> = {
    uTime: { value: 0 },

    uEnergy: { value: 0.15 },
    uTurbulence: { value: 0 },
    uCoherence: { value: 0.85 },
    uBreathing: { value: 0 },
    uExpansion: { value: 0 },
    uInsight: { value: 0 },
    uPointer: { value: new THREE.Vector3(0, 0, 0) },
    uPointerStrength: { value: 0 },
    uRimIntensity: { value: 0 },
    uBrightness: { value: 0 },
    uSpeaking: { value: 0 },
    uSweep: { value: 0 },
    uCrater: { value: 0 },
    uCraterDir: { value: new THREE.Vector3(0, 1, 0) },
    uBristle: { value: 0 },

    // Static render config.
    uLightDir: { value: new THREE.Vector3(...PRAX_LIGHT_DIR) },
    uPixelRatio: { value: pixelRatio },
    // Updated per frame from the current object scale so density stays constant.
    uParticlePx: { value: PRAX_CONFIG.PARTICLE_PX },
    uCameraZ: { value: PRAX_CONFIG.CAMERA_Z },
    uNoiseFreq: { value: PRAX_CONFIG.NOISE_FREQ },
    uNoiseSpeed: { value: PRAX_CONFIG.NOISE_SPEED },
    uBreathRate: { value: PRAX_CONFIG.BREATH_RATE },
    uBristleRate: { value: PRAX_CONFIG.BRISTLE_RATE },
    uBristleAmp: { value: PRAX_CONFIG.BRISTLE_AMPLITUDE },
    uBristleCoverage: { value: PRAX_CONFIG.BRISTLE_COVERAGE },
    uBaseColor: { value: new THREE.Color(PRAX_COLORS.base) },
    uRimColor: { value: new THREE.Color(PRAX_COLORS.rim) },
    uSpeakColor: { value: new THREE.Color(PRAX_COLORS.speak) },
  }

  const material = new THREE.ShaderMaterial({
    vertexShader: PRAX_VERTEX,
    fragmentShader: PRAX_FRAGMENT,
    uniforms,
    transparent: true,
    depthWrite: false,
    depthTest: false,
    blending: THREE.NormalBlending,
  })

  const points = new THREE.Points(geometry, material)
  // Shader deformation pushes vertices past the computed bounds; never cull.
  points.frustumCulled = false

  return {
    points,
    uniforms,
    count: data.count,
    dispose() {
      geometry.dispose()
      material.dispose()
    },
  }
}
