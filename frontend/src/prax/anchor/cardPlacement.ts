/**
 * Where the Prax card sits — computed, never hardcoded per page.
 *
 * The old rule was `left: praxX + radius + 18, top: praxY - 40` with a fixed
 * 290px width. That works only when Prax happens to sit far enough from the
 * right edge. It doesn't on Progress, Library or Insights, where each page
 * anchors Prax further right and the card ran off screen; and it clips any
 * answer taller than the space below praxY.
 *
 * Everything here is derived from three live measurements — Prax's projected
 * position, the card's rendered height, and the page's own layout boxes — so
 * there is one rule for all four pages.
 */

export interface PraxAnchorPos {
  x: number
  y: number
  scale: number
}

export interface CardPlacement {
  left: number
  top: number
  width: number
  /** Cap so a very long answer scrolls inside the card instead of off-screen. */
  maxHeight: number
}

/** Clearance from the viewport edges. */
const EDGE = 16
/** Clearance between the particle field and the card. */
const GAP = 18
/** Below this the card is unreadable; flip sides rather than shrink further. */
const MIN_WIDTH = 258
const MAX_WIDTH = 340
/**
 * Below this width there is no free region on the page at all: the content
 * column spans the whole viewport, so every possible placement covers
 * something. See the narrow-viewport branch in computePlacement.
 */
const NARROW = 640

/**
 * How much room `<main>` gives up so the card has somewhere to go.
 *
 * A constant, not the card's measured width: deriving it from the card would
 * make the reserve an input to the placement that the placement then changes,
 * and the layout would oscillate.
 */
export const PRAX_GUTTER_PX = MAX_WIDTH + 2 * EDGE

/** Viewports at or above this reserve a gutter instead of docking. */
export const PRAX_NARROW_PX = NARROW

/**
 * The band the card must stay out of at the top: sticky nav, plus the sync
 * banner while it is on screen. Measured, because both change height (the
 * banner grows a progress bar during analysis) and a constant would be wrong
 * exactly when it matters.
 */
function safeTop(): number {
  let bottom = 0
  const boxes = document.querySelectorAll('nav, [data-prax-avoid]')
  boxes.forEach((el) => {
    const r = el.getBoundingClientRect()
    // Ignore boxes that have scrolled away above the viewport.
    if (r.bottom > bottom && r.bottom < window.innerHeight * 0.5) bottom = r.bottom
  })
  return bottom + EDGE
}

/**
 * The right edge of the page's content column. `<main>` is max-width 1280 and
 * centred, so on a wide screen there is a genuine empty gutter beside it —
 * which is where Prax lives and where the card belongs. Reading the real box
 * is what keeps the card off the charts and game rows without page-specific
 * rules.
 */
function contentRight(vw: number): number {
  const main = document.querySelector('main')
  if (!main) return vw * 0.72
  const r = main.getBoundingClientRect()
  // The right edge of the CONTENT, not of the border box. While the card is up,
  // <main> carries a padding-right of PRAX_GUTTER_PX (see PraxStack) — its box
  // still reaches the viewport edge, but nothing is drawn in that strip. Using
  // the border-box edge here reported "no gutter" on exactly the viewports the
  // reserve exists to serve.
  const padRight = parseFloat(getComputedStyle(main).paddingRight) || 0
  return r.right - padRight
}

/** The page measurements the placement depends on. */
export interface PlacementEnv {
  vw: number
  vh: number
  /** Bottom of the nav / sync banner, plus clearance. */
  safeTop: number
  /** Right edge of the `<main>` content column. */
  contentRight: number
}

function readEnv(): PlacementEnv {
  const vw = window.innerWidth
  return { vw, vh: window.innerHeight, safeTop: safeTop(), contentRight: contentRight(vw) }
}

export function placePraxCard(prax: PraxAnchorPos, cardHeight: number): CardPlacement {
  return computePlacement(prax, cardHeight, readEnv())
}

/**
 * The placement rule itself, with every measurement passed in — so it can be
 * exercised against real page geometry without a browser.
 */
export function computePlacement(
  prax: PraxAnchorPos,
  cardHeight: number,
  env: PlacementEnv,
): CardPlacement {
  const { vw, vh, contentRight: cRight } = env
  const top0 = env.safeTop

  const radius = prax.scale * 210
  const praxRight = prax.x + radius

  // ── narrow viewports ───────────────────────────────────────────────────
  //
  // On a phone the content column IS the viewport, so the "find the empty
  // gutter" rule below has nothing to find and falls through to a card centred
  // under Prax — which lands in the middle of the page, on top of whatever
  // happens to be there. That is not a cosmetic overlap: the card is
  // pointer-events:auto, so it silently swallows clicks on the buttons
  // underneath. A Playwright run caught it eating the Resign button on Play.
  //
  // Dock to the bottom edge instead. It still covers page content — nothing can
  // avoid that at this width — but it covers a PREDICTABLE strip that the user
  // can scroll out from under, the way every sheet on a phone behaves, rather
  // than a strip that moves as Prax drifts.
  if (vw < NARROW) {
    const width = vw - 2 * EDGE
    const maxHeight = Math.max(160, Math.min(vh * 0.45, vh - top0 - EDGE))
    const h = Math.min(cardHeight, maxHeight)
    return {
      left: EDGE,
      top: Math.max(top0, vh - EDGE - h),
      width: Math.round(width),
      maxHeight: Math.round(maxHeight),
    }
  }

  // ── horizontal ─────────────────────────────────────────────────────────
  // The gutter right of the content column is the only region that is free of
  // page content by construction, so it wins outright when it is wide enough —
  // even though the card then overlaps Prax's outer halo. Covering a few
  // particles costs nothing; covering the accuracy chart or the game list is
  // exactly what was asked against.
  const gutter = vw - EDGE - cRight
  let left: number
  let width: number

  // `below` switches the vertical rule from "centred on Prax" to "under it".
  let below = false

  if (gutter >= MIN_WIDTH) {
    width = Math.round(Math.min(MAX_WIDTH, gutter))
    left = vw - EDGE - width
  } else if (praxRight + GAP + MAX_WIDTH <= vw - EDGE) {
    width = MAX_WIDTH
    left = praxRight + GAP
  } else {
    // No room to the right. Drop BELOW Prax rather than mirroring to its left:
    // left is where the page content lives, so a card there lands on the
    // charts, the KPI row and the game list. The space under Prax is empty.
    below = true
    width = Math.round(Math.min(MAX_WIDTH, vw - 2 * EDGE))
    left = prax.x - width / 2 // centred under the organism
  }
  width = Math.round(Math.min(width, vw - 2 * EDGE))
  left = Math.max(EDGE, Math.min(left, vw - EDGE - width))

  // ── vertical ───────────────────────────────────────────────────────────
  // Centred on Prax's body. The card and the organism share a midline, so the
  // two read as one object however tall the answer is.
  const maxHeight = Math.max(160, vh - top0 - EDGE)
  const h = Math.min(cardHeight, maxHeight)

  // Growth is symmetric until an edge is reached, at which point the clamp
  // turns it into upward-only growth — a long answer can never push past the
  // viewport floor.
  let top: number
  if (below) {
    top = prax.y + radius + GAP
    if (top + h > vh - EDGE) {
      // Not enough room under it either. Prefer above the organism over
      // covering it; only clamp into the floor when neither side fits.
      const above = prax.y - radius - GAP - h
      top = above >= top0 ? above : vh - EDGE - h
    }
  } else {
    top = prax.y - h / 2
    top = Math.min(top, vh - EDGE - h)
  }
  top = Math.max(top0, top)

  return { left: Math.round(left), top: Math.round(top), width, maxHeight }
}
