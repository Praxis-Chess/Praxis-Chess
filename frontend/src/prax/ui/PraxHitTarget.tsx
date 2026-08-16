import { useEffect, useState } from 'react'
import { getPraxScreenPos } from '../renderer/screenPos'
import { praxRuntime } from '../state/runtime'
import { praxInteract } from '../interaction/interactions'
import { praxAsk } from './PraxAsk'

/**
 * Contract §6 — the canvas is pointer-events:none permanently, because it is
 * fullscreen and fixed and would otherwise swallow every click in the app.
 * Prax is therefore made clickable by a transparent DOM disc that tracks its
 * projected screen position.
 */
export function PraxHitTarget() {
  const [pos, setPos] = useState({ x: -9999, y: -9999, scale: 0.24 })

  useEffect(() => {
    let raf = 0
    const tick = () => {
      raf = requestAnimationFrame(tick)
      setPos(getPraxScreenPos())
    }
    tick()
    return () => cancelAnimationFrame(raf)
  }, [])

  // Slightly inside the silhouette, so the clickable area matches what is drawn
  // rather than a bounding box with dead corners.
  const r = pos.scale * 190
  if (pos.x < -1000) return null

  return (
    <div
      role="button"
      tabIndex={0}
      aria-label="Ask Prax"
      onClick={() => {
        praxInteract('SECONDARY_ACTION')
        praxAsk.toggle()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          praxAsk.toggle()
        }
      }}
      onMouseEnter={() => praxRuntime.setPresence('engaged')}
      onMouseLeave={() => praxRuntime.setPresence('focused')}
      style={{
        position: 'fixed',
        left: pos.x - r,
        top: pos.y - r,
        width: r * 2,
        height: r * 2,
        borderRadius: '50%',
        zIndex: 55, // above the canvas (50), below the cards (60)
        cursor: 'pointer',
        // Fully invisible. The organism is the affordance — the pointer-response
        // lean (§ dwell) is already the feedback that Prax noticed the cursor,
        // so a ring or tooltip on top of it is redundant chrome.
        background: 'transparent',
        outline: 'none',
      }}
    />
  )
}
