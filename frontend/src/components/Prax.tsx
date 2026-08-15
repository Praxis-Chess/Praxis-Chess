import { useEffect, useRef } from 'react'
import { praxBus, type PraxSignal } from './praxBus'

/**
 * Prax — XPBD soft-body companion.
 *
 * Physics: Extended Position Based Dynamics (Macklin, Müller & Chentanez 2016).
 * 4 substeps per 30 Hz physics tick. Each substep: predict → solve once → update v.
 * Compliance α/h_sub² is timestep-independent: stiffer = smaller α.
 *
 * Body: ~350 2D particles in an irregular polar disk with k≈6 neighbor edges.
 * Constraints: distance (with plastic yield), floor, minimum-separation.
 * Global motion: velocity-based springs toward target positions per phase.
 * Shading: radial brightness (fake sphere depth) → depth-rank brightness (film).
 *
 * Unchanged from previous version: praxBus, Layout, NodeField, CSS class, face swim.
 */

// ── debug panel ───────────────────────────────────────────────────────────────
declare global {
  interface Window {
    PRAX_PARAMS?: {
      stiffness?:   number   // 0..1 distance constraint stiffness (higher=stiffer)
      yieldThresh?: number   // px strain before plastic flow begins
      yieldRate?:   number   // 0..1 rest-length drift fraction per second
      areaStiff?:   number   // 0..1 area pressure stiffness
      gravity?:     number   // px/s²
      floorDamp?:   number   // 0..1 bounce coefficient
    }
  }
}

// ── blob geometry ─────────────────────────────────────────────────────────────
const N_PARTICLES = 350
const K_NEIGHBORS = 6        // edges per particle in neighbor graph

// ── 2D silhouette: directional lobes make the disk read as one uneven organism ──
const LOBES_2D: Array<{ a: number; amp: number; tight: number }> = [
  { a: 0.8,  amp:  0.20, tight: 1.4 },  // broad right swell
  { a: 2.5,  amp:  0.13, tight: 1.8 },  // upper-left rise
  { a: 4.2,  amp: -0.16, tight: 1.3 },  // lower flattening
  { a: 5.6,  amp:  0.10, tight: 2.5 },  // small high-freq nub
  { a: 1.6,  amp: -0.11, tight: 1.7 },  // compressed sector
  { a: 3.4,  amp:  0.09, tight: 2.1 },  // rear ripple
]

function silhouette2D(angle: number): number {
  let r = 1
  for (const L of LOBES_2D) {
    const c = Math.cos(angle - L.a)
    if (c > 0) r += L.amp * Math.pow(c, L.tight)
  }
  return Math.max(0.55, r)
}

// ── placement (same as previous: left gutter, offset by SHIFT_X) ──────────────
const R_MAX = 390, R_MIN = 74
const CROP_X = -0.42, EDGE_MARGIN = 12
const SHIFT_X = 100
const SILHOUETTE_MAX = 1.25
const NAV_TUCK = 0.55, TOP_GUARD = 4

// ── film ──────────────────────────────────────────────────────────────────────
const FLOOR_PAD  = 3
const FILM_THICK = 8
const WAVE_INSET = 0.04

// ── XPBD ─────────────────────────────────────────────────────────────────────
const N_SUB   = 4
const H_STEP  = 1 / 30   // physics at 30 Hz
// Compliance (α). Smaller = stiffer. Divided by h_sub² inside solver.
const ALPHA_DIST  = 0.00008   // distance constraints: moderately stiff
const ALPHA_SEP   = 0.0001    // min-separation: softer (avoidance, not rigid)
const ALPHA_FLOOR = 0.0000005 // floor: nearly rigid

// ── material defaults ─────────────────────────────────────────────────────────
const DEF_GRAVITY    = 2400   // px/s²
const DEF_BOUNCE     = 0.12   // floor restitution
const DEF_YIELD_THR  = 16     // px strain before rest-len drifts
const DEF_YIELD_RATE = 0.18   // fraction drift per second

// ── phase springs (px/s² per px of error = angular freq²) ────────────────────
const K_BLOB_SPRING  = 18    // pull toward blob target during EXPANDING
const K_FILM_LATERAL = 22    // pull toward film x-slot during COLLAPSING spread
const K_FILM_SETTLE  = 40    // pull toward film y-slot when landed
const K_DAMP_V       = 1.8   // velocity damping coefficient (per second)
const SEP_DIST       = 18    // px minimum particle separation in blob mode
const BLEND_TAIL     = 0.14  // final fraction hard-lerped to analytic rest pose

// ── cursor ────────────────────────────────────────────────────────────────────
const REACH = 140, PULL = 5.5
const K_REST = 0.055, DAMP = 0.90, MAX_DISP = 90

// ── attention ─────────────────────────────────────────────────────────────────
const ENERGY_FLOOR = 0.12, ENERGY_DECAY = 0.0038

// ── choreography (ms) ─────────────────────────────────────────────────────────
const T_COLLAPSE = 4800, T_EXPAND = 4700
const T_FACE = 2600, T_SETTLE = 700

// ── types ─────────────────────────────────────────────────────────────────────
type Mode  = 'blob' | 'film'
type Phase = 'IDLE' | 'CURIOUS'
           | 'COLLAPSING' | 'FILM_RESTING' | 'FILM_INTERACTING'
           | 'FACE_TRAVELLING' | 'FILM_SETTLING' | 'EXPANDING'

interface Particle {
  bx0: number; by0: number   // blob-space unit pos (fixed at build time)
  x:   number; y:   number   // current screen pos (physics or analytic)
  px:  number; py:  number   // previous screen pos (XPBD)
  vx:  number; vy:  number   // velocity px/s
  neighbors: number[]        // indices into particles[]
  mass: number; invMass: number
  shade: number              // 0=centre 1=edge (for shading)
  ph:   number               // phase for per-particle breathing
  dx: number; dy: number; ddx: number; ddy: number  // cursor displacement
  u:   number; v:   number   // film slot [0..1]
  landed: boolean            // has touched floor during collapse
}

interface Edge {
  i: number; j: number
  blobLen: number   // blob-space distance (never changes)
  restLen: number   // current rest length in screen px (drifts with yield)
}

interface Ripple { x: number; y: number; born: number; strength: number; isFilm: boolean }

// ── RNG ──────────────────────────────────────────────────────────────────────
function mulberry32(seed: number) {
  return () => {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

// ── helpers ───────────────────────────────────────────────────────────────────
const clamp01 = (v: number) => Math.max(0, Math.min(1, v))
const smooth   = (t: number) => t * t * (3 - 2 * t)
const easeIO   = (t: number) => t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2
const lerp     = (a: number, b: number, t: number) => a + (b - a) * t

// ── blob build ────────────────────────────────────────────────────────────────
/**
 * Sunflower-spiral placement gives even area coverage.
 * silhouette2D() deforms the disk boundary to match LOBES_2D.
 * Jitter on both radius and angle breaks the hexagonal lattice.
 */
function buildBlob(): { particles: Particle[]; edges: Edge[] } {
  const rand   = mulberry32(0x50726178)
  const GOLDEN = Math.PI * (3 - Math.sqrt(5))

  const raw: Array<{ bx: number; by: number; shade: number }> = []
  for (let i = 0; i < N_PARTICLES; i++) {
    const frac  = (i + 0.5) / N_PARTICLES
    const r0    = Math.sqrt(frac)                              // even area distribution
    const angle = i * GOLDEN
    const sil   = silhouette2D(angle)
    const r     = r0 * sil + (rand() - 0.5) * 0.05            // radial jitter
    const ang   = angle    + (rand() - 0.5) * 0.10            // angular jitter
    raw.push({ bx: Math.cos(ang) * r, by: Math.sin(ang) * r, shade: r0 })
  }

  // k-nearest neighbor graph
  const particles: Particle[] = raw.map((p, i) => {
    const nb = raw
      .map((q, j) => ({ j, d: (q.bx - p.bx) ** 2 + (q.by - p.by) ** 2 }))
      .filter(e => e.j !== i)
      .sort((a, b) => a.d - b.d)
      .slice(0, K_NEIGHBORS)
      .map(e => e.j)
    const mass = 0.8 + rand() * 0.4
    return {
      bx0: p.bx, by0: p.by,
      x: 0, y: 0, px: 0, py: 0, vx: 0, vy: 0,
      neighbors: nb, mass, invMass: 1 / mass,
      shade: p.shade, ph: rand() * Math.PI * 2,
      dx: 0, dy: 0, ddx: 0, ddy: 0,
      u: 0, v: 0, landed: false,
    }
  })

  // Deduplicated edge set
  const seen = new Set<string>()
  const edges: Edge[] = []
  for (let i = 0; i < particles.length; i++) {
    for (const j of particles[i].neighbors) {
      const key = i < j ? `${i},${j}` : `${j},${i}`
      if (seen.has(key)) continue
      seen.add(key)
      const d = Math.hypot(raw[i].bx - raw[j].bx, raw[i].by - raw[j].by)
      edges.push({ i, j, blobLen: d, restLen: d })
    }
  }
  return { particles, edges }
}

/** Face — small dense sphere (unchanged from previous) */
function buildFace() {
  const rand = mulberry32(0xfacef00d)
  const pts: Array<{ x: number; y: number; z: number }> = []
  for (let i = 1; i < 18; i++) {
    const lat = (i / 18) * Math.PI, ring = Math.sin(lat)
    const n = Math.max(3, Math.round(ring * 21))
    for (let j = 0; j < n; j++) {
      const lon = (j / n) * Math.PI * 2 + (i % 2) * 0.2
      pts.push({ x: ring * Math.cos(lon), y: Math.cos(lat), z: ring * Math.sin(lon) })
    }
  }
  return pts.filter(() => rand() > 0.06)
}

// ── spatial grid ──────────────────────────────────────────────────────────────
class SpatialGrid {
  private cells = new Map<number, number[]>()
  private inv: number
  constructor(cellSize: number) { this.inv = 1 / cellSize }
  clear() { this.cells.clear() }
  private key(x: number, y: number) {
    return (Math.floor(x * this.inv) * 99991 + Math.floor(y * this.inv)) | 0
  }
  insert(i: number, x: number, y: number) {
    const k = this.key(x, y)
    const a = this.cells.get(k)
    if (a) a.push(i); else this.cells.set(k, [i])
  }
  nearby(x: number, y: number): number[] {
    const cx = Math.floor(x * this.inv), cy = Math.floor(y * this.inv)
    const out: number[] = []
    for (let dx = -1; dx <= 1; dx++)
      for (let dy = -1; dy <= 1; dy++) {
        const k = ((cx + dx) * 99991 + (cy + dy)) | 0
        const a = this.cells.get(k)
        if (a) for (const idx of a) out.push(idx)
      }
    return out
  }
}

// ═══════════════════════════════════════════════════════════════════════════
export function Prax({ onDashboard }: { onDashboard: boolean }) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const wantRef   = useRef<Mode>('blob')
  wantRef.current = onDashboard ? 'blob' : 'film'

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    // Non-nullable aliases for use inside closures (TS can't narrow across them)
    const cv: HTMLCanvasElement       = canvas
    const gx: CanvasRenderingContext2D = ctx

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const { particles, edges } = buildBlob()
    const facePts = buildFace()
    const N     = particles.length
    const grid  = new SpatialGrid(SEP_DIST)

    // ── viewport ──────────────────────────────────────────────────────────
    let vw = 0, vh = 0, navH = 52
    let R = R_MAX, blobCX = 0, blobCY = 0
    let waveX0 = 0, waveX1 = 0, floorY = 0
    let blobFits = true

    // ── animation ─────────────────────────────────────────────────────────
    let phase: Phase   = wantRef.current === 'blob' ? 'IDLE' : 'FILM_RESTING'
    let morph           = wantRef.current === 'blob' ? 0 : 1
    let morphTo         = morph, morphT = 1, morphDur = 1
    let simActive       = false
    let slotsFor: Mode  = wantRef.current
    let physAccum       = 0   // ms accumulator for fixed-step physics
    let blobRot         = 0   // slow blob rotation (idle animation)
    let energy          = 0.5, hush = 0
    let faceT           = 0, faceDir = 1, settleT = 0
    let lastNear        = 0, lastMove = 0
    let raf = 0, prev   = 0
    const ripples: Ripple[] = []
    const ptr = { x: -9999, y: -9999, px: -9999, py: -9999, on: false }
    const creep = { x: 0, y: 0 }

    // ── measure ───────────────────────────────────────────────────────────
    function measure(): boolean {
      const w = window.innerWidth, h = window.innerHeight
      if (!w || !h) return false
      vw = w; vh = h
      const nav  = document.querySelector('nav')
      navH = nav ? Math.round(nav.getBoundingClientRect().height) : 52
      const main = document.querySelector('main')
      let contentLeft = 24
      if (main) {
        const cs = getComputedStyle(main)
        contentLeft = main.getBoundingClientRect().left + parseFloat(cs.paddingLeft || '24')
      }
      const span  = CROP_X + SILHOUETTE_MAX
      const avail = contentLeft - EDGE_MARGIN
      const shift = Math.max(0, Math.min(SHIFT_X, avail - R_MIN * span))
      const fit   = (avail - shift) / span
      blobFits = fit >= R_MIN
      R       = Math.max(R_MIN, Math.min(R_MAX, fit))
      blobCX  = CROP_X * R + shift
      blobCY  = Math.max(R + TOP_GUARD, navH + NAV_TUCK * R)
      waveX0  = vw * WAVE_INSET
      waveX1  = vw * (1 - WAVE_INSET)
      floorY  = vh - FLOOR_PAD
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      const nw = Math.round(vw * dpr), nh = Math.round(vh * dpr)
      if (cv.width !== nw || cv.height !== nh) {
        cv.width = nw; cv.height = nh
        cv.style.width = `${vw}px`; cv.style.height = `${vh}px`
      }
      gx.setTransform(dpr, 0, 0, dpr, 0, 0)
      return true
    }

    // ── blob-space → screen ───────────────────────────────────────────────
    /**
     * Apply slow rotation (blobRot) and breathing to blob-space coords.
     * Returns the analytic rest position in screen px.
     */
    function blobScreen(p: Particle, t: number): { x: number; y: number } {
      const c = Math.cos(blobRot), s = Math.sin(blobRot)
      const bxr = p.bx0 * c - p.by0 * s
      const byr = p.bx0 * s + p.by0 * c
      const breathe = reduced ? 1 : 1 + Math.sin(t * 0.55 + p.ph) * 0.018 * (0.3 + 0.7 * energy)
      return {
        x: blobCX + bxr * R * breathe,
        y: blobCY + byr * R * breathe,
      }
    }

    // ── film ──────────────────────────────────────────────────────────────
    function undulation(x: number, t: number) {
      const k = (x - waveX0) / Math.max(1, waveX1 - waveX0)
      return (Math.sin(k * 9.1 + t * 0.33) * 0.6
            + Math.sin(k * 15.7 - t * 0.21) * 0.35
            + Math.sin(k * 5.3  + t * 0.13) * 0.55) * (0.4 + 0.6 * energy)
    }
    function surfaceAt(x: number, t: number) {
      return floorY - FILM_THICK + undulation(x, t)
    }
    function filmSlot(p: Particle, t: number): { x: number; y: number } {
      const x = lerp(waveX0, waveX1, p.u)
      return { x, y: floorY - Math.pow(1 - p.v, 1.6) * FILM_THICK
                          - Math.pow(Math.max(0, 1 - p.v * 7), 2) * 5
                          + undulation(x, t) }
    }

    // ── slots ─────────────────────────────────────────────────────────────
    function assignFilmSlots(t: number) {
      const pos = particles.map((p, i) => { const s = blobScreen(p, t); return { i, x: s.x, y: s.y } })
      const byX = [...pos].sort((a, b) => a.x - b.x)
      const ys  = pos.map(q => q.y)
      const minY = Math.min(...ys), maxY = Math.max(...ys)
      byX.forEach((q, rank) => {
        const p = particles[q.i]
        p.u = byX.length > 1 ? rank / (byX.length - 1) : 0.5
        p.v = clamp01((q.y - minY) / Math.max(1, maxY - minY))
      })
      slotsFor = 'film'
    }

    /** Scale edge rest lengths to current R (called whenever R is known). */
    function calibrateEdges() {
      for (const e of edges) e.restLen = e.blobLen * R
    }

    // ── phase transitions ─────────────────────────────────────────────────
    function beginCollapse(t: number) {
      if (slotsFor !== 'film') assignFilmSlots(t)
      calibrateEdges()
      for (const p of particles) {
        const s = blobScreen(p, t)
        p.x = s.x; p.y = s.y; p.px = s.x; p.py = s.y
        p.vx = 0; p.vy = 0; p.landed = false
      }
      simActive = true; morphTo = 1; morphT = 0; morphDur = reduced ? 1 : T_COLLAPSE
      phase = 'COLLAPSING'
    }

    function beginExpand(t: number) {
      // Reset rest lengths so distance constraints pull back to blob shape
      calibrateEdges()
      for (const p of particles) {
        const s = filmSlot(p, t)
        p.x = s.x; p.y = s.y; p.px = s.x; p.py = s.y
        p.vx = 0; p.vy = 0; p.landed = false
      }
      simActive = true; morphTo = 0; morphT = 0; morphDur = reduced ? 1 : T_EXPAND
      phase = 'EXPANDING'
    }

    // ── XPBD substep ──────────────────────────────────────────────────────
    /**
     * One substep of duration h seconds.
     * predict → solve constraints once → update velocities
     * (NOT multiple Gauss-Seidel passes — that's a different algorithm)
     */
    function substep(h: number) {
      const pp = window.PRAX_PARAMS ?? {}
      const gravity   = pp.gravity    ?? DEF_GRAVITY
      const bounce    = pp.floorDamp  ?? DEF_BOUNCE
      const yieldThr  = pp.yieldThresh ?? DEF_YIELD_THR
      const yieldRate = pp.yieldRate  ?? DEF_YIELD_RATE
      // α / h_sub² — compliance in XPBD constraint correction term
      const aD  = ALPHA_DIST  / (h * h)
      const aS  = ALPHA_SEP   / (h * h)
      const aFL = ALPHA_FLOOR / (h * h)

      // ── gravity and phase springs → velocity → predict position ──────
      const collapseT  = morph                  // 0→1 during collapse
      const expandT    = 1 - morph              // 0→1 during expand (morph goes 1→0)

      for (let i = 0; i < N; i++) {
        const p = particles[i]
        // save prev pos for velocity update
        p.px = p.x; p.py = p.y

        let fx = 0, fy = 0

        // gravity — ramps in during collapse, off during expand
        if (phase === 'COLLAPSING') {
          fy += gravity * Math.min(1, collapseT * 2.5) * p.mass
        } else if (phase === 'EXPANDING') {
          // no gravity — blob springs handle this
        } else if (phase === 'FILM_RESTING' || phase === 'FILM_INTERACTING') {
          fy += gravity * 0.30 * p.mass   // gentle settle against floor
        } else {
          fy += gravity * 0.04 * p.mass   // minimal idle gravity
        }

        // lateral spread: during collapse, after first half, push toward film x-slot
        if (phase === 'COLLAPSING' && collapseT > 0.45 && p.landed) {
          const tx = lerp(waveX0, waveX1, p.u)
          fx += (tx - p.x) * K_FILM_LATERAL * p.mass
          fy += (floorY - p.y) * K_FILM_SETTLE * p.mass
        }

        // blob spring: during expanding, pull each particle toward its target
        if (phase === 'EXPANDING') {
          // can't call blobScreen here since we don't have t; use bx0/by0 directly
          const c = Math.cos(blobRot), s = Math.sin(blobRot)
          const tx = blobCX + (p.bx0 * c - p.by0 * s) * R
          const ty = blobCY + (p.bx0 * s + p.by0 * c) * R
          const strength = K_BLOB_SPRING * (0.5 + 0.5 * expandT)
          fx += (tx - p.x) * strength * p.mass
          fy += (ty - p.y) * strength * p.mass
        }

        // apply forces as velocity change
        p.vx += fx * p.invMass * h
        p.vy += fy * p.invMass * h

        // predict
        p.x += p.vx * h
        p.y += p.vy * h
      }

      // ── distance constraints (with plastic yield) ─────────────────────
      for (const e of edges) {
        const pi = particles[e.i], pj = particles[e.j]
        const dx = pj.x - pi.x, dy = pj.y - pi.y
        const len = Math.hypot(dx, dy)
        if (len < 0.001) continue
        const C = len - e.restLen
        // Plastic yield: rest length drifts toward actual length under sustained strain
        if (Math.abs(C) > yieldThr) {
          e.restLen += Math.sign(C) * (Math.abs(C) - yieldThr) * yieldRate * h
          // clamp: never drift more than 50% from blob baseline
          e.restLen = Math.max(e.blobLen * R * 0.5, Math.min(e.blobLen * R * 1.5, e.restLen))
        }
        const w  = pi.invMass + pj.invMass
        const lm = -C / (w + aD)
        const nx = dx / len, ny = dy / len
        pi.x -= pi.invMass * lm * nx;  pi.y -= pi.invMass * lm * ny
        pj.x += pj.invMass * lm * nx;  pj.y += pj.invMass * lm * ny
      }

      // ── minimum-separation (spatial grid, blob mode only) ─────────────
      if (morph < 0.55) {
        grid.clear()
        for (let i = 0; i < N; i++) grid.insert(i, particles[i].x, particles[i].y)
        for (let i = 0; i < N; i++) {
          const pi = particles[i]
          for (const j of grid.nearby(pi.x, pi.y)) {
            if (j <= i || pi.neighbors.includes(j)) continue
            const pj = particles[j]
            const dx = pj.x - pi.x, dy = pj.y - pi.y
            const d2 = dx * dx + dy * dy
            if (d2 >= SEP_DIST * SEP_DIST || d2 < 0.01) continue
            const d  = Math.sqrt(d2)
            const C  = d - SEP_DIST
            const w  = pi.invMass + pj.invMass
            const lm = -C / (w + aS)
            const nx = dx / d, ny = dy / d
            pi.x -= pi.invMass * lm * nx;  pi.y -= pi.invMass * lm * ny
            pj.x += pj.invMass * lm * nx;  pj.y += pj.invMass * lm * ny
          }
        }
      }

      // ── floor constraint ─────────────────────────────────────────────
      for (let i = 0; i < N; i++) {
        const p = particles[i]
        if (p.y > floorY) {
          const C  = p.y - floorY
          p.y     -= C / (p.invMass + aFL) * p.invMass
          if (p.y > floorY) p.y = floorY
          if (!p.landed) p.landed = true
        }
      }

      // ── update velocities ─────────────────────────────────────────────
      const damp = Math.exp(-K_DAMP_V * h)
      for (let i = 0; i < N; i++) {
        const p = particles[i]
        p.vx = (p.x - p.px) / h * damp
        p.vy = (p.y - p.py) / h * damp
        // floor bounce
        if (p.y >= floorY - 0.5 && p.vy > 0) {
          p.vy = -p.vy * bounce
          p.vx *= 0.82
        }
      }
    }

    /** Run N_SUB substeps for one physics tick (h = H_STEP / N_SUB). */
    function physicsStep() {
      const h = H_STEP / N_SUB
      for (let s = 0; s < N_SUB; s++) substep(h)
    }

    /** Blend physics positions → analytic rest pose over the final BLEND_TAIL. */
    function blendTail(t: number) {
      const tail = clamp01((morphT - (1 - BLEND_TAIL)) / BLEND_TAIL)
      if (tail <= 0) return
      const w = smooth(tail)
      if (morphTo === 1) {
        for (const p of particles) { const r = filmSlot(p, t); p.x = lerp(p.x, r.x, w); p.y = lerp(p.y, r.y, w) }
      } else {
        for (const p of particles) { const s = blobScreen(p, t); p.x = lerp(p.x, s.x, w); p.y = lerp(p.y, s.y, w) }
      }
    }

    // ── attention & bus ───────────────────────────────────────────────────
    function bump(v: number) { energy = Math.min(1, Math.max(energy, v)) }
    function addRipple(x: number, y: number, strength: number, isFilm: boolean, now: number) {
      if (ripples.length > 9) ripples.shift()
      ripples.push({ x, y, born: now, strength, isFilm })
    }

    const offBus = praxBus.on((s: PraxSignal) => {
      const now = performance.now()
      switch (s) {
        case 'analysis-start': hush = 1; break
        case 'analysis-end':   hush = 0; break
        case 'variation-change':
          addRipple(morph > 0.5 ? lerp(waveX0, waveX1, 0.5) : blobCX,
                    morph > 0.5 ? floorY : blobCY, 0.5, morph > 0.5, now)
          bump(0.4); break
        case 'strong-move': bump(0.9); break
        case 'blunder': energy = ENERGY_FLOOR; hush = 0.6; setTimeout(() => { hush = 0 }, 1400); break
        case 'poke': poke(now); break
      }
    })

    function poke(now: number) {
      if (morph > 0.9 && phase === 'FILM_RESTING') {
        phase = 'FACE_TRAVELLING'; faceT = 0; faceDir = Math.random() > 0.5 ? 1 : -1
        addRipple(lerp(waveX0, waveX1, faceDir > 0 ? 0.08 : 0.92), floorY, 1, true, now)
        bump(1)
      } else if (morph < 0.1) {
        addRipple(blobCX, blobCY, 1, false, now); bump(1)
      }
    }

    // ── main loop ─────────────────────────────────────────────────────────
    function frame(now: number) {
      raf = requestAnimationFrame(frame)
      if (!vw || !vh) { if (!measure()) return }
      const dtMs = prev ? Math.min(64, now - prev) : 16
      prev = now
      render(now, dtMs)
    }

    function render(now: number, dtMs: number) {
      if (!vw || !vh) return
      const t  = reduced ? 0 : now / 1000
      const want: Mode = wantRef.current === 'blob' && blobFits ? 'blob' : 'film'

      // ── phase transitions ─────────────────────────────────────────────
      if (want === 'film' && morphTo === 0 && !simActive) beginCollapse(t)
      else if (want === 'blob' && morphTo === 1 && !simActive) beginExpand(t)

      if (simActive) {
        morphT = Math.min(1, morphT + dtMs / morphDur)
        morph  = morphTo === 1 ? morphT : 1 - morphT
        if (morphT >= 1) {
          simActive = false; morph = morphTo
          phase = morphTo === 1 ? 'FILM_RESTING' : 'IDLE'
          if (morphTo === 0) slotsFor = 'blob'
        }
      }

      // ── attention & state ─────────────────────────────────────────────
      const near = ptr.on && (morph > 0.5
        ? ptr.y > surfaceAt(ptr.x, t) - 56
        : Math.hypot(ptr.x - blobCX, ptr.y - blobCY) < R + 90)
      if (near) { lastNear = now; bump(0.55) }
      const efloor = ENERGY_FLOOR * (1 - hush * 0.7)
      energy += (efloor - energy) * ENERGY_DECAY * (dtMs / 16)
      if (energy < efloor) energy = efloor

      if (phase === 'IDLE' && near) phase = 'CURIOUS'
      else if (phase === 'CURIOUS' && now - lastNear > 900) phase = 'IDLE'
      if (phase === 'FILM_RESTING' && near) phase = 'FILM_INTERACTING'
      else if (phase === 'FILM_INTERACTING' && now - lastNear > 900) phase = 'FILM_RESTING'

      if (phase === 'FACE_TRAVELLING') {
        faceT += dtMs / T_FACE
        if (faceT >= 1) { faceT = 1; phase = 'FILM_SETTLING'; settleT = 0 }
      } else if (phase === 'FILM_SETTLING') {
        settleT += dtMs / T_SETTLE
        if (settleT >= 1) phase = 'FILM_RESTING'
      }

      // slow rotation when idling as blob
      if (!reduced && !simActive && morph < 0.02 && !hush)
        blobRot += 0.0007 * (0.35 + 0.65 * energy)

      // spontaneous ripples
      if (!reduced && !hush && Math.random() < 0.00035 * energy * energy) {
        addRipple(
          morph > 0.5 ? lerp(waveX0, waveX1, Math.random()) : blobCX + (Math.random()-0.5)*R,
          morph > 0.5 ? floorY - 4 : blobCY + (Math.random()-0.5)*R,
          0.35, morph > 0.5, now,
        )
      }

      // cursor trail ripples
      if (ptr.on) {
        const sp = Math.hypot(ptr.x - ptr.px, ptr.y - ptr.py)
        if (sp > 22 && near && now - lastMove > 90) {
          addRipple(ptr.x, ptr.y, Math.min(0.6, sp / 90), morph > 0.5, now); lastMove = now
        }
        ptr.px = ptr.x; ptr.py = ptr.y
      }

      // creep toward cursor
      if (near && !reduced && !simActive && now - lastNear < 200) {
        const cx = morph > 0.5 ? lerp(waveX0, waveX1, 0.5) : blobCX
        const cy = morph > 0.5 ? floorY : blobCY
        creep.x += ((ptr.x - cx) * 0.012 - creep.x) * 0.02
        creep.y += ((ptr.y - cy) * 0.012 - creep.y) * 0.02
      } else { creep.x *= 0.94; creep.y *= 0.94 }
      const cl = Math.hypot(creep.x, creep.y)
      if (cl > 6) { creep.x *= 6 / cl; creep.y *= 6 / cl }

      while (ripples.length && now - ripples[0].born > 1600) ripples.shift()

      // ── physics ───────────────────────────────────────────────────────
      if (simActive) {
        physAccum += dtMs
        let steps = 0
        while (physAccum >= H_STEP * 1000 && steps < 3) {
          physicsStep()
          blendTail(t)
          physAccum -= H_STEP * 1000
          steps++
        }
      } else {
        // analytic positions when not simulating
        if (morph >= 0.5) {
          for (const p of particles) { const s = filmSlot(p, t); p.x = s.x; p.y = s.y }
        } else {
          for (const p of particles) { const s = blobScreen(p, t); p.x = s.x; p.y = s.y }
        }
      }

      // ── centroid for shading ──────────────────────────────────────────
      let cx = 0, cy = 0
      for (let i = 0; i < N; i++) { cx += particles[i].x; cy += particles[i].y }
      cx /= N; cy /= N
      let effR = 0
      for (let i = 0; i < N; i++) {
        const d = Math.hypot(particles[i].x - cx, particles[i].y - cy)
        if (d > effR) effR = d
      }

      // ── face ──────────────────────────────────────────────────────────
      const faceLift = phase === 'FACE_TRAVELLING'
        ? Math.sin(Math.min(1, faceT / 0.12) * Math.PI * 0.5) * (1 - Math.max(0, (faceT - 0.88) / 0.12))
        : phase === 'FILM_SETTLING' ? 1 - clamp01(settleT) : 0
      const faceX = (phase === 'FACE_TRAVELLING' || phase === 'FILM_SETTLING')
        ? lerp(waveX0 + 30, waveX1 - 30, faceDir > 0 ? easeIO(faceT) : 1 - easeIO(faceT))
        : 0
      const faceY = surfaceAt(faceX, t) - 44 * faceLift
        + (phase === 'FACE_TRAVELLING' ? Math.sin(faceT * Math.PI * 3.3) * 7 : 0)

      // ── clear ─────────────────────────────────────────────────────────
      if (simActive) gx.clearRect(0, 0, vw, vh)
      else if (morph >= 0.98) gx.clearRect(0, vh - 220, vw, 220)
      else gx.clearRect(0, 0, blobCX + R + MAX_DISP + 30, blobCY + R + MAX_DISP + 40)

      // ── tether ────────────────────────────────────────────────────────
      if (morph < 0.6 && blobCY - R > navH + 6) {
        const a = 1 - morph / 0.6
        gx.strokeStyle = `rgba(255,255,255,${0.10 * a})`
        gx.lineWidth = 1
        gx.beginPath(); gx.moveTo(blobCX + 0.5, navH); gx.lineTo(blobCX + 0.5, blobCY - R); gx.stroke()
        gx.fillStyle = `rgba(255,255,255,${0.34 * a})`
        gx.fillRect(blobCX - 1.5, blobCY - R - 1.5, 3, 3)
      }

      // ── render particles ──────────────────────────────────────────────
      const BUCKETS = 10
      const bins: number[][] = Array.from({ length: BUCKETS }, () => [])
      const idle = 0.25 + 0.75 * energy

      for (let i = 0; i < N; i++) {
        const p = particles[i]
        let bx = p.x + creep.x, by = p.y + creep.y

        // ripple displacement
        for (const rp of ripples) {
          const age = (now - rp.born) / 1600
          if (age >= 1) continue
          const radius = age * 190
          const d = Math.hypot(bx - rp.x, by - rp.y)
          const band = Math.abs(d - radius)
          if (band < 46) {
            const f = (1 - band / 46) * (1 - age) * rp.strength * 13
            const inv = d > 0.01 ? 1 / d : 0
            bx += (bx - rp.x) * inv * f
            by += (by - rp.y) * inv * f - (rp.isFilm ? f * 0.8 : 0)
          }
        }

        // face liquid drag
        if (faceLift > 0.01) {
          const fdx = bx - faceX, fdy = by - faceY
          const fd = Math.hypot(fdx, fdy)
          if (fd < 86 && fd > 0.01) {
            const f = (1 - fd / 86) ** 2 * 40 * faceLift
            bx += (fdx / fd) * f; by += (fdy / fd) * f - f
          }
        }

        // cursor displacement
        if (!reduced && !simActive) {
          if (ptr.on) {
            const ax = ptr.x - (bx + p.dx), ay = ptr.y - (by + p.dy)
            const d  = Math.hypot(ax, ay)
            if (d < REACH && d > 0.01) {
              const f = (1 - d / REACH) ** 2 * PULL * (0.4 + 0.6 * idle)
              p.ddx += (ax / d) * f; p.ddy += (ay / d) * f
            }
          }
          const bias = morph > 0.5 ? 1.6 : 1 + (Math.hypot(bx - blobCX, by - (blobCY - R)) / (R * 2)) * 0.5
          p.ddx += -K_REST * bias * p.dx; p.ddy += -K_REST * bias * p.dy
          p.ddx *= DAMP; p.ddy *= DAMP
          p.dx += p.ddx; p.dy += p.ddy
          const dl = Math.hypot(p.dx, p.dy)
          if (dl > MAX_DISP) { p.dx *= MAX_DISP / dl; p.dy *= MAX_DISP / dl }
        } else if (simActive) { p.dx *= 0.9; p.dy *= 0.9 }

        const x = bx + p.dx, y = by + p.dy

        // ── 2D radial shading → blends to film depth-rank shading ────
        // Sphere-like: bright at centroid (faked depth), dark at edge
        const radial   = 1 - Math.min(1, Math.hypot(bx - cx, by - cy) / Math.max(1, effR))
        const sphereLit = 0.18 + 0.82 * radial * radial
        // Highlight: spot near upper-left of blob (simulates specular)
        const hlDist   = Math.hypot(bx - (blobCX - R * 0.30), by - (blobCY - R * 0.38))
        const highlight = Math.max(0, 1 - hlDist / (R * 0.45)) ** 2 * 0.22
        const filmLit   = 0.24 + 0.70 * (1 - p.v)
        let bright = lerp(sphereLit + highlight * (1 - morph), filmLit, morph)
        bright *= 0.55 + 0.45 * idle
        if (bright < 0.03) continue

        const size = 0.75 + 1.65 * bright
        const b    = Math.min(BUCKETS - 1, (bright * (BUCKETS - 1)) | 0)
        bins[b].push(x - size * 0.5, y - size * 0.5, size)
      }

      // flush buckets (10 fillStyle changes per frame, not N)
      for (let b = 0; b < BUCKETS; b++) {
        const arr = bins[b]
        if (!arr.length) continue
        const a = 0.10 + (b / (BUCKETS - 1)) * 0.80
        gx.fillStyle = `rgba(238,236,234,${a.toFixed(3)})`
        for (let i = 0; i < arr.length; i += 3) gx.fillRect(arr[i], arr[i + 1], arr[i + 2], arr[i + 2])
      }

      // face dots
      if (faceLift > 0.01) {
        const fr = 22 * faceLift, spin = t * 0.5 * faceDir
        const cs = Math.cos(spin), sn = Math.sin(spin)
        gx.fillStyle = `rgba(244,242,240,${0.30 + 0.55 * faceLift})`
        for (const q of facePts) {
          const qx = q.x * cs + q.z * sn, qz = -q.x * sn + q.z * cs
          const persp = 1 / (1 - qz * 0.22), sz = 1.0 + 0.7 * ((qz + 1) * 0.5)
          gx.fillRect(faceX + qx * fr * persp - sz * 0.5, faceY + q.y * fr * persp - sz * 0.5, sz, sz)
        }
      }
    }

    // ── debug panel ───────────────────────────────────────────────────────
    let debugPanel: HTMLDivElement | null = null
    if (typeof window !== 'undefined' && new URLSearchParams(window.location.search).has('prax-debug')) {
      debugPanel = document.createElement('div')
      Object.assign(debugPanel.style, {
        position: 'fixed', bottom: '20px', right: '20px', zIndex: '9999',
        background: 'rgba(0,0,0,0.88)', color: '#eee', padding: '14px',
        borderRadius: '8px', font: '11px/1.7 monospace', minWidth: '210px',
        boxShadow: '0 4px 16px rgba(0,0,0,0.5)',
      })
      const params: Array<[keyof NonNullable<Window['PRAX_PARAMS']>, number, number, number, number]> = [
        ['stiffness',   0,   1,   0.01, 0.5],
        ['yieldThresh', 2,  60,   1,    DEF_YIELD_THR],
        ['yieldRate',   0,   1,   0.01, DEF_YIELD_RATE],
        ['areaStiff',   0,   1,   0.01, 0.5],
        ['gravity',   200, 6000, 50,    DEF_GRAVITY],
        ['floorDamp',   0,   1,   0.01, DEF_BOUNCE],
      ]
      window.PRAX_PARAMS = {}
      debugPanel.innerHTML = '<b style="opacity:.6">PRAX DEBUG</b><br>'
      for (const [key, min, max, step, def] of params) {
        const label = document.createElement('div')
        const input = document.createElement('input')
        input.type = 'range'; input.min = String(min); input.max = String(max)
        input.step = String(step); input.value = String(def)
        input.style.cssText = 'width:100%;margin:1px 0 3px'
        const span = document.createElement('span')
        span.textContent = `${key}: ${def}`
        label.appendChild(span); label.appendChild(input)
        input.oninput = () => {
          const v = parseFloat(input.value)
          window.PRAX_PARAMS![key] = v
          span.textContent = `${key}: ${v}`
        }
        debugPanel!.appendChild(label)
      }
      document.body.appendChild(debugPanel)
    }

    // ── events ────────────────────────────────────────────────────────────
    function onMove(e: PointerEvent) {
      if (ptr.px < -1000) { ptr.px = e.clientX; ptr.py = e.clientY }
      ptr.x = e.clientX; ptr.y = e.clientY; ptr.on = true
    }
    function onDown(e: PointerEvent) {
      const t = performance.now() / 1000
      if (morph > 0.9) { if (e.clientY > surfaceAt(e.clientX, t) - 46) poke(performance.now()) }
      else if (Math.hypot(e.clientX - blobCX, e.clientY - blobCY) < R * 1.1) poke(performance.now())
    }
    function onLeave() { ptr.on = false; ptr.x = -9999; ptr.y = -9999; ptr.px = -9999 }
    function onVisibility() {
      if (document.hidden) { cancelAnimationFrame(raf); raf = 0 }
      else if (!raf && !reduced) { prev = 0; raf = requestAnimationFrame(frame) }
    }
    function onResize() { measure(); render(performance.now(), 16) }

    // ── init ──────────────────────────────────────────────────────────────
    measure()
    calibrateEdges()
    const t0 = performance.now() / 1000
    if (wantRef.current === 'blob') {
      for (const p of particles) { const s = blobScreen(p, t0); p.x = s.x; p.y = s.y; p.px = s.x; p.py = s.y }
    } else {
      assignFilmSlots(t0)
      morph = 1; morphTo = 1; phase = 'FILM_RESTING'
      for (const p of particles) { const s = filmSlot(p, t0); p.x = s.x; p.y = s.y; p.px = s.x; p.py = s.y }
    }

    render(performance.now(), 16)

    window.addEventListener('resize', onResize)
    const ro = new ResizeObserver(onResize)
    ro.observe(document.documentElement)

    if (reduced) return () => {
      ro.disconnect(); window.removeEventListener('resize', onResize); offBus()
      debugPanel?.remove()
    }

    window.addEventListener('pointermove', onMove, { passive: true })
    window.addEventListener('pointerdown', onDown, { passive: true })
    window.addEventListener('pointerleave', onLeave)
    document.addEventListener('visibilitychange', onVisibility)
    raf = requestAnimationFrame(frame)

    return () => {
      cancelAnimationFrame(raf)
      ro.disconnect()
      window.removeEventListener('resize', onResize)
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerdown', onDown)
      window.removeEventListener('pointerleave', onLeave)
      document.removeEventListener('visibilitychange', onVisibility)
      offBus()
      debugPanel?.remove()
    }
  }, [])

  return <canvas ref={canvasRef} className="prax" aria-hidden />
}
