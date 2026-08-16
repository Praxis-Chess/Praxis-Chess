import { useLayoutEffect, useRef } from 'react'
import { anchorRegistry } from '../anchor/registry'
import { PRAX_CONFIG } from '../core/constants'

interface PraxAnchorProps {
  /** Horizontal placement as a viewport fraction. */
  x?: number
  /** Vertical placement as a viewport fraction. */
  y?: number
}

/**
 * Declares that this page is one Prax attends to, WHERE on the page it belongs,
 * and whether the relevant content is on screen.
 *
 * Placement is a page-level decision expressed as viewport fractions, so each
 * route can put Prax in its own negative space without the renderer knowing
 * anything about page layout (Contract §4).
 *
 * Registers in a LAYOUT effect — the router bridge resolves `hasAnchor` on the
 * next animation frame and depends on registration having already happened
 * (Contract §2). Moving this to useEffect would silently break navigation.
 */
export function PraxAnchor({ x = 0.68, y = 0.46 }: PraxAnchorProps) {
  const ref = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    anchorRegistry.set(el, { x, y })

    // Visibility is observed on the parent content region: "is the content in
    // view" is the question that matters, and a zero-height sentinel would read
    // as hidden except at the exact scroll bottom.
    const observed = el.parentElement ?? el

    const io = new IntersectionObserver(
      ([entry]) => {
        const r = entry.intersectionRect
        const b = entry.boundingClientRect
        const visibleArea = r.width * r.height
        // Normalise against the viewport: a container taller than the screen
        // can never reach a raw ratio of 0.5 and would sit in `partial` forever.
        const reference = Math.min(b.width * b.height, window.innerWidth * window.innerHeight)
        const ratio = reference > 0 ? visibleArea / reference : 0

        anchorRegistry.setVisibility(
          ratio >= PRAX_CONFIG.VISIBLE_RATIO ? 'visible' : ratio > 0 ? 'partial' : 'hidden',
        )
      },
      { threshold: [0, 0.1, 0.25, 0.5, 0.75, 1] },
    )
    io.observe(observed)

    return () => {
      io.disconnect()
      anchorRegistry.clear(el)
    }
  }, [x, y])

  return <div ref={ref} aria-hidden="true" style={{ height: 0, pointerEvents: 'none' }} />
}
