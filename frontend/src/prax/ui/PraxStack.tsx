import { useSyncExternalStore, useEffect, useState, useRef, useLayoutEffect } from 'react'
import { narrationStore } from '../state/narrationStore'
import { getPraxScreenPos } from '../renderer/screenPos'
import { placePraxCard, type CardPlacement } from '../anchor/cardPlacement'
import { PraxProgress } from './PraxProgress'
import { PraxAsk, praxAsk } from './PraxAsk'

/**
 * The single positioned column beside Prax.
 *
 * Progress and thought cards previously each anchored themselves to the
 * organism's projected position, so whenever both were live they rendered on
 * top of each other. Position is owned here exactly once and the cards flow
 * inside it — running status first, findings beneath.
 */
export function PraxStack() {
  const narration = useSyncExternalStore(narrationStore.subscribe, narrationStore.get)
  const asking = useSyncExternalStore(praxAsk.subscribe, praxAsk.get)

  const ref = useRef<HTMLDivElement>(null)
  const heightRef = useRef(0)
  const [place, setPlace] = useState<CardPlacement>({
    left: -9999,
    top: -9999,
    width: 300,
    maxHeight: 480,
  })

  // PraxThought (the insight card with Listen / Examine / Dismiss) is unmounted
  // for now — its Listen action only makes sense once speech is built. The
  // component and the praxThoughts registry are untouched; restoring it is
  // adding <PraxThought /> back below and folding hasThought into `visible`.
  const visible = !!narration || asking

  // The card's own height is an input to its position, so it has to be
  // measured after paint. ResizeObserver rather than a one-shot read: the
  // answer streams in and the card grows well after first mount.
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    heightRef.current = el.offsetHeight
    const ro = new ResizeObserver(() => {
      heightRef.current = el.offsetHeight
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [visible])

  // Follow Prax only while something is showing.
  useEffect(() => {
    if (!visible) return
    let raf = 0
    const tick = () => {
      raf = requestAnimationFrame(tick)
      const next = placePraxCard(getPraxScreenPos(), heightRef.current)
      setPlace((prev) =>
        prev.left === next.left &&
        prev.top === next.top &&
        prev.width === next.width &&
        prev.maxHeight === next.maxHeight
          ? prev // identical object keeps React from re-rendering every frame
          : next,
      )
    }
    tick()
    return () => cancelAnimationFrame(raf)
  }, [visible])

  if (!visible) return null

  return (
    <div
      ref={ref}
      style={{
        position: 'fixed',
        left: place.left,
        top: place.top,
        zIndex: 60, // above the canvas (50), below the nav (100)
        width: place.width,
        maxHeight: place.maxHeight,
        // Tall answers scroll inside the column rather than off the viewport.
        overflowY: 'auto',
        overflowX: 'hidden',
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        // The column itself must not intercept clicks; each card opts back in.
        pointerEvents: 'none',
      }}
    >
      {/* Running status first, then the question. */}
      <PraxProgress />
      <PraxAsk />
    </div>
  )
}
