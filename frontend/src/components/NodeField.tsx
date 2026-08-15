import { useEffect, useRef } from 'react'

/**
 * Praxis node field — an ambient analytical network rendered behind the UI.
 *
 * Design rules (shared across every page, this is the identity):
 *   · Positions come from a jittered grid, not pure random — the layout reads as
 *     measured rather than decorative.
 *   · Three tiers: background (barely there), secondary, primary (anchors).
 *   · Connections only form between near neighbours, so clusters look meaningful.
 *   · Parallax is depth-sorted; the cursor nudges nearby nodes a few pixels and
 *     brightens their edges. Nothing chases the pointer.
 *   · Everything is orchid/mauve/warm-grey. No second hue family.
 *
 * `density` scales node count per page (see Layout). Honours prefers-reduced-motion.
 */

type Tier = 0 | 1 | 2 // 0 background · 1 secondary · 2 primary

interface FieldNode {
  x: number       // base position, CSS px
  y: number
  tier: Tier
  depth: number   // 0 far … 1 near — drives parallax + drift amplitude
  r: number
  phase: number   // drift offset so nodes don't move in lockstep
  marker: boolean // draw registration crosshair
  orbit: number   // orbit ring radius, 0 = none
}

interface Ring {
  x: number
  y: number
  r: number
  depth: number
}

// rgb triples + alpha/radius ranges per tier
const TIERS = {
  0: { rgb: '109,102,98',  aMin: 0.10, aMax: 0.17, rMin: 0.6, rMax: 1.0 },
  1: { rgb: '176,103,159', aMin: 0.20, aMax: 0.30, rMin: 1.0, rMax: 1.6 },
  2: { rgb: '231,166,214', aMin: 0.32, aMax: 0.46, rMin: 1.8, rMax: 2.5 },
} as const

const BASE_CELL      = 122   // px per grid cell at density 1
const CELL_SKIP      = 0.18  // fraction of cells left empty, breaks grid regularity
const LINK_REACH     = 1.55  // neighbour search radius, in cell units
const CURSOR_REACH   = 150   // px — influence radius of the pointer
const CURSOR_PULL    = 5     // px — max positional nudge. Deliberately tiny.
const PARALLAX       = 14    // px — max offset at depth 1
const FRAME_MS       = 33    // ~30fps is plenty for motion this slow

/** Deterministic PRNG so a given viewport always yields the same constellation. */
function mulberry32(seed: number) {
  return function () {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const lerp = (a: number, b: number, t: number) => a + (b - a) * t

function build(w: number, h: number, density: number) {
  const rand = mulberry32(0x9e37 + Math.round(w) * 31 + Math.round(h))
  const cell = BASE_CELL / Math.sqrt(Math.max(density, 0.05))
  const cols = Math.ceil(w / cell) + 1
  const rows = Math.ceil(h / cell) + 1

  const nodes: FieldNode[] = []
  for (let cy = 0; cy < rows; cy++) {
    for (let cx = 0; cx < cols; cx++) {
      if (rand() < CELL_SKIP) continue
      const roll = rand()
      const tier: Tier = roll > 0.93 ? 2 : roll > 0.64 ? 1 : 0
      const t = TIERS[tier]
      nodes.push({
        // jitter inside the cell keeps spacing even but not visibly gridded
        x: (cx + 0.15 + rand() * 0.7) * cell,
        y: (cy + 0.15 + rand() * 0.7) * cell,
        tier,
        depth: tier === 2 ? 0.62 + rand() * 0.38 : tier === 1 ? 0.3 + rand() * 0.4 : rand() * 0.3,
        r: lerp(t.rMin, t.rMax, rand()),
        phase: rand() * Math.PI * 2,
        marker: tier === 2 && rand() > 0.55,
        orbit: tier === 2 && rand() > 0.78 ? 9 + rand() * 7 : 0,
      })
    }
  }

  // Link near neighbours. Primaries act as hubs and take more edges, which is
  // what produces the radial fans / constellation clusters in the references.
  const reach = cell * LINK_REACH
  const links: Array<[number, number]> = []
  const seen = new Set<string>()
  for (let i = 0; i < nodes.length; i++) {
    const a = nodes[i]
    const budget = a.tier === 2 ? 4 : a.tier === 1 ? 2 : 1
    const near: Array<{ j: number; d: number }> = []
    for (let j = 0; j < nodes.length; j++) {
      if (i === j) continue
      const b = nodes[j]
      const d = Math.hypot(a.x - b.x, a.y - b.y)
      if (d < reach) near.push({ j, d })
    }
    near.sort((p, q) => p.d - q.d)
    for (const { j } of near.slice(0, budget)) {
      const key = i < j ? `${i}:${j}` : `${j}:${i}`
      if (seen.has(key)) continue
      seen.add(key)
      links.push([i, j])
    }
  }

  // A couple of very faint large rings — the translucent geometric accent.
  const rings: Ring[] = []
  const ringCount = density > 1.1 ? 3 : 2
  for (let i = 0; i < ringCount; i++) {
    rings.push({
      x: w * (0.2 + rand() * 0.6),
      y: h * (0.15 + rand() * 0.7),
      r: Math.min(w, h) * (0.22 + rand() * 0.3),
      depth: 0.12 + rand() * 0.18,
    })
  }

  return { nodes, links, rings }
}

export function NodeField({ density = 1 }: { density?: number }) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const densityRef = useRef(density)
  densityRef.current = density

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches

    let w = 0
    let h = 0
    let field = build(1, 1, density)
    let raf = 0
    let last = 0

    // pointer: target vs eased, so motion trails the cursor gently
    const ptr = { tx: -9999, ty: -9999, x: -9999, y: -9999 }

    function resize() {
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      w = window.innerWidth
      h = window.innerHeight
      canvas!.width = Math.round(w * dpr)
      canvas!.height = Math.round(h * dpr)
      canvas!.style.width = `${w}px`
      canvas!.style.height = `${h}px`
      ctx!.setTransform(dpr, 0, 0, dpr, 0, 0)
      field = build(w, h, densityRef.current)
      render(performance.now()) // paint immediately — don't wait for a frame
    }

    function onPointer(e: PointerEvent) {
      ptr.tx = e.clientX
      ptr.ty = e.clientY
    }
    function onLeave() {
      ptr.tx = -9999
      ptr.ty = -9999
    }

    /** Animation loop — throttled; `render` does the actual painting. */
    function tick(now: number) {
      raf = requestAnimationFrame(tick)
      if (now - last < FRAME_MS) return
      last = now
      render(now)
    }

    function render(now: number) {
      const { nodes, links, rings } = field

      // ease pointer toward its target
      if (ptr.x < -1000) {
        ptr.x = ptr.tx
        ptr.y = ptr.ty
      } else {
        ptr.x = lerp(ptr.x, ptr.tx, 0.08)
        ptr.y = lerp(ptr.y, ptr.ty, 0.08)
      }

      const t = reduced ? 0 : now / 1000
      // parallax origin: cursor offset from viewport centre, normalised
      const hasPtr = ptr.tx > -1000
      const ox = hasPtr ? ((ptr.x - w / 2) / w) * 2 : 0
      const oy = hasPtr ? ((ptr.y - h / 2) / h) * 2 : 0

      ctx!.clearRect(0, 0, w, h)

      // --- deep layer: translucent geometric rings ---
      for (const r of rings) {
        const rx = r.x - ox * PARALLAX * r.depth
        const ry = r.y - oy * PARALLAX * r.depth
        ctx!.beginPath()
        ctx!.arc(rx, ry, r.r, 0, Math.PI * 2)
        ctx!.strokeStyle = 'rgba(231,166,214,0.035)'
        ctx!.lineWidth = 1
        ctx!.setLineDash([1, 9])
        ctx!.stroke()
        ctx!.setLineDash([])
      }

      // --- resolve live positions once, reused by links and nodes ---
      const px = new Float32Array(nodes.length)
      const py = new Float32Array(nodes.length)
      const boost = new Float32Array(nodes.length)

      for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i]
        const drift = reduced ? 0 : Math.sin(t * 0.22 + n.phase) * (1 + n.depth * 1.6)
        let x = n.x + ox * PARALLAX * n.depth + drift
        let y = n.y + oy * PARALLAX * n.depth + Math.cos(t * 0.19 + n.phase) * (reduced ? 0 : 0.8)

        if (hasPtr) {
          const dx = ptr.x - x
          const dy = ptr.y - y
          const d = Math.hypot(dx, dy)
          if (d < CURSOR_REACH && d > 0.01) {
            const k = 1 - d / CURSOR_REACH
            const pull = CURSOR_PULL * k * k * n.depth
            x += (dx / d) * pull
            y += (dy / d) * pull
            boost[i] = k * k
          }
        }
        px[i] = x
        py[i] = y
      }

      // --- links ---
      ctx!.lineWidth = 1
      for (const [i, j] of links) {
        const near = Math.max(boost[i], boost[j])
        const a = 0.055 + near * 0.16
        ctx!.beginPath()
        ctx!.moveTo(px[i], py[i])
        ctx!.lineTo(px[j], py[j])
        // at rest the edges are a desaturated orchid-grey; they resolve toward
        // full orchid only where the cursor is close by
        ctx!.strokeStyle = near > 0.02
          ? `rgba(231,166,214,${a})`
          : `rgba(206,186,199,${a * 0.8})`
        ctx!.stroke()
      }

      // --- nodes ---
      for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i]
        const s = TIERS[n.tier]
        const a = lerp(s.aMin, s.aMax, 0.5 + Math.sin(t * 0.3 + n.phase) * 0.5) + boost[i] * 0.3
        const x = px[i]
        const y = py[i]

        ctx!.beginPath()
        ctx!.arc(x, y, n.r, 0, Math.PI * 2)
        ctx!.fillStyle = `rgba(${s.rgb},${a})`
        ctx!.fill()

        if (n.tier === 2 && boost[i] > 0.05) {
          // faint halo, only under the cursor — never a permanent glow
          ctx!.beginPath()
          ctx!.arc(x, y, n.r + 2.5, 0, Math.PI * 2)
          ctx!.strokeStyle = `rgba(231,166,214,${boost[i] * 0.28})`
          ctx!.lineWidth = 1
          ctx!.stroke()
        }

        if (n.orbit) {
          ctx!.beginPath()
          ctx!.arc(x, y, n.orbit, 0, Math.PI * 2)
          ctx!.strokeStyle = `rgba(231,166,214,${0.07 + boost[i] * 0.14})`
          ctx!.lineWidth = 1
          ctx!.setLineDash([1, 4])
          ctx!.stroke()
          ctx!.setLineDash([])
        }

        if (n.marker) {
          // registration tick — same language as the card corner crosshairs
          const m = 4.5
          ctx!.strokeStyle = `rgba(109,102,98,${0.3 + boost[i] * 0.4})`
          ctx!.lineWidth = 1
          ctx!.beginPath()
          ctx!.moveTo(x - m, y); ctx!.lineTo(x - m + 3, y)
          ctx!.moveTo(x + m - 3, y); ctx!.lineTo(x + m, y)
          ctx!.moveTo(x, y - m); ctx!.lineTo(x, y - m + 3)
          ctx!.moveTo(x, y + m - 3); ctx!.lineTo(x, y + m)
          ctx!.stroke()
        }
      }
    }

    resize()
    window.addEventListener('resize', resize)

    // Reduced motion: paint the constellation once and stop. No loop, no
    // pointer tracking, zero ongoing cost.
    if (reduced) {
      return () => window.removeEventListener('resize', resize)
    }

    window.addEventListener('pointermove', onPointer, { passive: true })
    window.addEventListener('pointerleave', onLeave)
    raf = requestAnimationFrame(tick)

    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', resize)
      window.removeEventListener('pointermove', onPointer)
      window.removeEventListener('pointerleave', onLeave)
    }
  }, [density])

  return <canvas ref={canvasRef} className="node-field" aria-hidden />
}
