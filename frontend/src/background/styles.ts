/**
 * The six background styles (docs/design/BACKGROUND_PLAN.md, round 4): white
 * light on black, drawn with Canvas 2D into a small canvas that CSS scales up
 * and blurs (AmbientBackground). Line and point drawings, so 2D is the natural
 * tool, and at about half resolution each frame is cheap.
 *
 * Every style reads the same frame: `t` (integrated time, so a change of speed
 * never jumps), `vol` (volatility) and `energy`, both from Prax (energy.ts).
 */

export type BackgroundStyle = 'contours' | 'dots' | 'ridges' | 'trails' | 'stars' | 'horizon'

export const BACKGROUND_STYLES: { key: BackgroundStyle; label: string }[] = [
  { key: 'contours', label: 'Contours' },
  { key: 'dots', label: 'Dot matrix' },
  { key: 'ridges', label: 'Ridge lines' },
  { key: 'trails', label: 'Flow trails' },
  { key: 'stars', label: 'Starfield' },
  { key: 'horizon', label: 'Chess horizon' },
]

export interface Frame {
  ctx: CanvasRenderingContext2D
  w: number
  h: number
  /** Integrated time, seconds of "field time". */
  t: number
  /** Real seconds since the last frame (for particles that move per frame). */
  dt: number
  energy: number
  vol: number
}

/** A style keeps its own particles (trails, stars) between frames. */
export interface Painter {
  draw(f: Frame): void
  /** Styles that accumulate (trails) clear on their first frame and after a resize. */
  reset(): void
}

/** Deterministic: the same field on every load. */
function rng(seed: number) {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 4294967296
  }
}

const field = (x: number, y: number, t: number, vol: number) =>
  Math.sin(x * 3.1 + t * 1.3) +
  Math.sin(y * 4.3 - t * 1.1 + Math.sin(x * 2 + t) * vol) +
  Math.sin((x + y) * 2.2 + t * 0.7) +
  0.6 * vol * Math.sin(x * 7 - y * 5 + t * 2.4)

const clear = (f: Frame) => {
  f.ctx.fillStyle = '#000'
  f.ctx.fillRect(0, 0, f.w, f.h)
}

/** Line widths are written for a 480px-wide canvas and scale with it. */
const unit = (f: Frame) => f.w / 480

function contours(): Painter {
  return {
    reset() {},
    draw(f) {
      clear(f)
      const { ctx, w, h, t, vol } = f
      const cw = 64
      const ch = Math.max(24, Math.round((cw * h) / w))
      const F = new Float32Array((cw + 1) * (ch + 1))
      for (let j = 0; j <= ch; j++) {
        for (let i = 0; i <= cw; i++) F[j * (cw + 1) + i] = field((i / cw) * 2, (j / ch) * 2 * (h / w) * 1.6, t, vol)
      }
      const levels = 9
      const sx = w / cw, sy = h / ch
      ctx.lineWidth = 1.4 * unit(f)
      for (let l = 0; l < levels; l++) {
        const L = -2.6 + (l * 5.2) / (levels - 1)
        ctx.strokeStyle = `rgba(255,255,255,${l % 3 === 0 ? 0.5 : 0.26})`
        ctx.beginPath()
        for (let j = 0; j < ch; j++) {
          for (let i = 0; i < cw; i++) {
            const a = F[j * (cw + 1) + i], b = F[j * (cw + 1) + i + 1]
            const c = F[(j + 1) * (cw + 1) + i + 1], d = F[(j + 1) * (cw + 1) + i]
            const px = i * sx, py = j * sy
            const P: number[] = []
            const edge = (v1: number, v2: number, x1: number, y1: number, x2: number, y2: number) => {
              if ((v1 - L) * (v2 - L) < 0) {
                const k = (L - v1) / (v2 - v1)
                P.push(x1 + (x2 - x1) * k, y1 + (y2 - y1) * k)
              }
            }
            edge(a, b, px, py, px + sx, py)
            edge(b, c, px + sx, py, px + sx, py + sy)
            edge(c, d, px + sx, py + sy, px, py + sy)
            edge(d, a, px, py + sy, px, py)
            if (P.length >= 4) { ctx.moveTo(P[0], P[1]); ctx.lineTo(P[2], P[3]) }
            if (P.length === 8) { ctx.moveTo(P[4], P[5]); ctx.lineTo(P[6], P[7]) }
          }
        }
        ctx.stroke()
      }
    },
  }
}

function dots(): Painter {
  return {
    reset() {},
    draw(f) {
      clear(f)
      const { ctx, w, h, t, vol, energy } = f
      const s = 12 * unit(f)
      for (let y = s / 2; y < h; y += s) {
        for (let x = s / 2; x < w; x += s) {
          const d1 = Math.hypot(x - w * 0.7, y - h * 0.45) / w
          const d2 = Math.hypot(x - w * 0.2, y - h * 0.8) / w
          const k = 0.5 + 0.5 * Math.sin(d1 * (18 + 14 * vol) - t * 6) * 0.6 + 0.5 * Math.sin(d2 * (14 + 10 * vol) - t * 4.5) * 0.4
          const r = (0.5 + 1.9 * k * (0.55 + 0.45 * energy)) * unit(f)
          ctx.fillStyle = `rgba(255,255,255,${0.12 + 0.38 * k})`
          ctx.beginPath()
          ctx.arc(x, y, r, 0, Math.PI * 2)
          ctx.fill()
        }
      }
    },
  }
}

function ridges(): Painter {
  return {
    reset() {},
    draw(f) {
      clear(f)
      const { ctx, w, h, t, vol, energy } = f
      const n = 26
      const side = w * 0.08
      const step = Math.max(2, 4 * unit(f))
      for (let k = 0; k < n; k++) {
        const y0 = h * 0.2 + (k * h * 0.75) / n
        // The ridge's top edge, filled black below so nearer ridges hide farther ones;
        // only the top edge is stroked (the sides of the fill would draw vertical bars).
        const line = new Path2D()
        line.moveTo(side, y0)
        for (let x = side; x <= w - side; x += step) {
          const u = (x - side) / (w - 2 * side)
          const env = Math.exp(-Math.pow((u - 0.5) / 0.17, 2))
          const amp = (14 + 40 * energy) * (h / 300)
          const hgt = env * amp * (0.5 + 0.5 * Math.sin(u * 23 + k * 1.7 + t * 3) * Math.sin(u * 9 - k * 0.9 + t * 1.7 + vol))
            + (1.5 + 2 * vol) * (h / 300) * Math.sin(u * 41 + k + t * 5)
          line.lineTo(x, y0 - Math.max(-2, hgt))
        }
        const body = new Path2D(line)
        body.lineTo(w - side, h * 2)
        body.lineTo(side, h * 2)
        body.closePath()
        ctx.fillStyle = '#000'
        ctx.fill(body)
        ctx.strokeStyle = 'rgba(255,255,255,0.62)'
        ctx.lineWidth = 1.2 * unit(f)
        ctx.stroke(line)
      }
    },
  }
}

function trails(): Painter {
  const r = rng(7)
  let pts: { x: number; y: number }[] = []
  let fresh = true
  return {
    reset() { fresh = true; pts = [] },
    draw(f) {
      const { ctx, w, h, t, vol, energy, dt } = f
      if (fresh || pts.length === 0) {
        clear(f)
        const n = Math.round(500 * (w * h) / (480 * 300))
        pts = Array.from({ length: Math.min(1400, n) }, () => ({ x: r() * w, y: r() * h }))
        fresh = false
      }
      ctx.fillStyle = `rgba(0,0,0,${0.06 + 0.04 * energy})`
      ctx.fillRect(0, 0, w, h)
      ctx.strokeStyle = 'rgba(255,255,255,0.35)'
      ctx.lineWidth = 0.9 * unit(f)
      ctx.beginPath()
      const sp = (18 + 90 * energy) * dt * 3 * unit(f)
      for (const p of pts) {
        const a = field((p.x / w) * 1.6, (p.y / h) * 1.2, t * 0.6, vol) * 1.4
        const nx = p.x + Math.cos(a) * sp, ny = p.y + Math.sin(a) * sp
        ctx.moveTo(p.x, p.y)
        ctx.lineTo(nx, ny)
        p.x = nx
        p.y = ny
        if (p.x < 0 || p.x > w || p.y < 0 || p.y > h || r() < 0.004) { p.x = r() * w; p.y = r() * h }
      }
      ctx.stroke()
    },
  }
}

function stars(): Painter {
  const r = rng(11)
  const s = Array.from({ length: 320 }, () => ({ x: (r() - 0.5) * 2, y: (r() - 0.5) * 2, z: r() }))
  let fresh = true
  return {
    reset() { fresh = true },
    draw(f) {
      const { ctx, w, h, energy, dt } = f
      if (fresh) { clear(f); fresh = false }
      ctx.fillStyle = `rgba(0,0,0,${0.55 - 0.35 * energy})`
      ctx.fillRect(0, 0, w, h)
      const sp = (0.03 + 0.9 * energy * energy) * dt * 2
      const sc = Math.max(w, h) * 0.25
      for (const p of s) {
        const z0 = p.z
        p.z -= sp
        if (p.z <= 0.02) { p.z = 1; p.x = (r() - 0.5) * 2; p.y = (r() - 0.5) * 2; continue }
        const px = w / 2 + (p.x / p.z) * sc, py = h / 2 + (p.y / p.z) * sc
        const qx = w / 2 + (p.x / z0) * sc, qy = h / 2 + (p.y / z0) * sc
        const a = Math.min(1, (1 - p.z) * 1.2)
        ctx.strokeStyle = `rgba(255,255,255,${a})`
        ctx.lineWidth = ((1 - p.z) * 1.8 + 0.3) * unit(f)
        ctx.beginPath()
        ctx.moveTo(qx, qy)
        ctx.lineTo(px + 0.01, py)
        ctx.stroke()
        // The star itself: a streak alone vanishes under the blur when it is short.
        ctx.fillStyle = `rgba(255,255,255,${a})`
        ctx.beginPath()
        ctx.arc(px, py, ((1 - p.z) * 2.2 + 0.6) * unit(f), 0, Math.PI * 2)
        ctx.fill()
      }
    },
  }
}

function horizon(): Painter {
  return {
    reset() {},
    draw(f) {
      clear(f)
      const { ctx, w, h, t, vol, energy } = f
      const hz = h * 0.42
      const off = (t * 1.4) % 2
      const glow = ctx.createLinearGradient(0, hz - h * 0.2, 0, hz + h * 0.07)
      glow.addColorStop(0, 'rgba(255,255,255,0)')
      glow.addColorStop(0.75, `rgba(255,255,255,${0.1 + 0.12 * energy})`)
      glow.addColorStop(1, 'rgba(255,255,255,0)')
      ctx.fillStyle = glow
      ctx.fillRect(0, hz - h * 0.2, w, h * 0.27)
      const proj = (x: number, z: number) => [w / 2 + (x / z) * w * 0.5, hz + (1 / z) * h * 0.55]
      const flip = Math.floor((t * 1.4) / 2) * 2
      for (let r = 0; r < 24; r++) {
        const z1 = 0.35 + r * 0.32 - off * 0.16, z2 = z1 + 0.32
        if (z1 < 0.3) continue
        for (let c = -12; c < 12; c++) {
          if ((c + r + flip) % 2) continue
          const a = proj(c * 0.32, z1), b = proj((c + 1) * 0.32, z1)
          const d = proj(c * 0.32, z2), e = proj((c + 1) * 0.32, z2)
          const fade = Math.max(0, 1 - z1 / 7.5)
          const pulse = 0.5 + 0.5 * Math.sin(t * 3 + c * 0.7 + r * 1.3) * vol * 0.6
          ctx.fillStyle = `rgba(255,255,255,${fade * (0.05 + 0.07 * pulse)})`
          ctx.beginPath()
          ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.lineTo(e[0], e[1]); ctx.lineTo(d[0], d[1])
          ctx.closePath()
          ctx.fill()
        }
      }
      ctx.strokeStyle = 'rgba(255,255,255,0.18)'
      ctx.lineWidth = 0.8 * unit(f)
      ctx.beginPath()
      for (let c = -12; c <= 12; c++) {
        const a = proj(c * 0.32, 0.35), b = proj(c * 0.32, 8)
        ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1])
      }
      ctx.stroke()
    },
  }
}

const MAKERS: Record<BackgroundStyle, () => Painter> = { contours, dots, ridges, trails, stars, horizon }

export function makePainter(style: BackgroundStyle): Painter {
  return MAKERS[style]()
}
