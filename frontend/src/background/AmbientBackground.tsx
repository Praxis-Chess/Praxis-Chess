import { useEffect, useRef } from 'react'
import { makePainter, type Frame } from './styles'
import { useBackground } from './preference'
import { CALM, EnergySpring } from './energy'

/** Drawn at this share of the viewport and scaled up: the image is blurred anyway. */
const SCALE = 0.5
/** Below this energy, 30 frames a second is plenty. */
const CALM_FPS_BELOW = 0.3
/** Seconds of motion simulated before a still frame, so trails and stars have something to show. */
const WARM_UP = 3

/**
 * The ambient background (docs/design/BACKGROUND_PLAN.md): one layer behind
 * every page, mounted once in App, never unmounted while a style is on.
 *
 * - Behind everything: `z-index: -1` paints it above the page's black but under
 *   all content, so it can never take a click or cover text.
 * - Soft by design: a half-resolution canvas, scaled up and blurred by CSS.
 * - Driven by Prax: energy (energy.ts) sets speed and volatility; time is
 *   integrated, so a change of speed never makes the field jump.
 * - Reduced motion, or Motion: Still, draws one frame and redraws only when
 *   the energy has moved noticeably.
 */
export function AmbientBackground() {
  const { style, motion } = useBackground()
  const wrap = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    if (style === 'off') return
    const c = canvas.current
    const el = wrap.current
    const ctx = c?.getContext('2d', { alpha: false })
    if (!c || !el || !ctx) return

    const painter = makePainter(style)
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)')
    const spring = new EnergySpring()
    let t = 0
    let last = performance.now()
    let acc = 0
    let raf = 0
    let drawnAt = -1
    let shownEnergy = -1

    const resize = () => {
      c.width = Math.max(160, Math.round(window.innerWidth * SCALE))
      c.height = Math.max(120, Math.round(window.innerHeight * SCALE))
      painter.reset()
      drawnAt = -1
    }
    resize()
    window.addEventListener('resize', resize)

    const draw = (dt: number, energy: number) => {
      const f: Frame = { ctx, w: c.width, h: c.height, t, dt, energy, vol: 0.15 + 1.1 * energy }
      painter.draw(f)
    }

    const frame = (now: number) => {
      raf = requestAnimationFrame(frame)
      const rawDt = Math.min(0.25, (now - last) / 1000)
      last = now

      const target = motion === 'reacts' ? spring.target(rawDt * 1000) : CALM
      const energy = spring.step(Math.min(rawDt, 0.1), target)
      if (Math.abs(energy - shownEnergy) >= 0.02) {
        shownEnergy = energy
        el.dataset.energy = energy.toFixed(2)
      }

      if (motion === 'still' || reduce.matches) {
        if (drawnAt >= 0 && Math.abs(energy - drawnAt) < 0.05) return
        if (drawnAt < 0) {
          for (let s = 0; s < WARM_UP * 30; s++) { t += (1 / 30) * 0.16; draw(1 / 30, energy) }
        } else {
          draw(0, energy)
        }
        drawnAt = energy
        return
      }

      acc += rawDt
      if (energy < CALM_FPS_BELOW && acc < 1 / 30) return
      const dt = Math.min(acc, 0.1)
      acc = 0
      t += dt * (0.1 + 0.6 * energy)
      draw(dt, energy)
    }
    raf = requestAnimationFrame(frame)

    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', resize)
    }
  }, [style, motion])

  if (style === 'off') return null

  return (
    <div ref={wrap} aria-hidden="true" data-background={style} data-motion={motion} className="ambient-background">
      <canvas ref={canvas} />
    </div>
  )
}
