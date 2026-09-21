import { Children, useLayoutEffect, useRef, useState, type ReactNode } from 'react'

/**
 * A board beside a side column — that stacks when there isn't room for both.
 *
 * Three pages used a fixed CSS grid for this (`380px 1fr`,
 * `minmax(320px, 460px) 1fr`). A grid track never wraps, so on a 412px phone the
 * row was ~600px wide. Mobile browsers don't answer that with a scrollbar: they
 * widen the LAYOUT viewport to fit the content and zoom out. innerWidth read 625
 * on a Pixel 7. The page looked cropped, and every tap landed at visual
 * coordinates that no longer matched the layout — so clicks hit the nav, a
 * chess piece, or the sticky board instead of the button under the finger.
 *
 * Flex-wrap rather than a media query, deliberately: the breakpoint that matters
 * is the width of THIS row, not of the viewport. <main> also loses a fixed
 * strip to --prax-gutter whenever a Prax card is open, so a viewport rule would
 * be wrong on exactly the desktops where the card is showing.
 *
 * Expects exactly two children: the board column, then the side column.
 */
export function BoardSplit({
  children,
  boardBasis = 380,
  boardMax,
  sideMin = 280,
  gap = 24,
  stickyBoard = false,
}: {
  children: ReactNode
  /** Preferred board-column width. It shrinks below this when stacked on a phone. */
  boardBasis?: number
  /** Upper bound when the board column may grow. Defaults to boardBasis (fixed width). */
  boardMax?: number
  /** The narrowest the side column may get before it drops below the board. */
  sideMin?: number
  gap?: number
  /** Keep the board in view while the side column scrolls — side by side only. */
  stickyBoard?: boolean
}) {
  const [board, side] = Children.toArray(children)
  const boardRef = useRef<HTMLDivElement>(null)
  const sideRef = useRef<HTMLDivElement>(null)
  const [stacked, setStacked] = useState(false)

  // Whether the side column has wrapped under the board. This is read from the
  // layout rather than predicted, because the wrap point depends on the row's
  // real width. A sticky board over a STACKED side column would ride down over
  // the move list and swallow every tap on it — the other half of the bug.
  useLayoutEffect(() => {
    const b = boardRef.current
    const s = sideRef.current
    if (!b || !s) return
    const measure = () => setStacked(s.offsetTop > b.offsetTop + 1)
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(b.parentElement ?? b)
    return () => ro.disconnect()
  }, [])

  const growable = boardMax !== undefined && boardMax > boardBasis

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap, alignItems: 'flex-start' }}>
      <div
        ref={boardRef}
        style={{
          // Shrinks to fit a phone; grows only when a max above the basis is given.
          flex: `${growable ? 1 : 0} 1 ${boardBasis}px`,
          maxWidth: boardMax ?? boardBasis,
          minWidth: 0,
          ...(stickyBoard && !stacked ? { position: 'sticky' as const, top: 16 } : null),
        }}
      >
        {board}
      </div>
      <div ref={sideRef} style={{ flex: `1 1 ${sideMin}px`, minWidth: 0 }}>
        {side}
      </div>
    </div>
  )
}
