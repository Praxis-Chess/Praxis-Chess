/**
 * Where the Prax card sits: in the margin beside the centred page, bottom right,
 * derived from live measurements (the page's box, the nav and banner, the card's
 * height), never hardcoded per page. With the particle body retired there is no
 * body to sit beside.
 */

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
  // The right edge of the CONTENT, not of the border box.
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

/**
 * The card sits in the margin beside the centred page, bottom right, when that
 * margin can hold it (MIN_WIDTH). Otherwise a card that appeared on its own (a
 * run's progress, which the sync banner also shows) is not shown, and one the
 * player opened overlays the page, bottom right. Nothing is reserved or
 * shifted, so a page never moves when a card opens.
 */
export function marginPraxCard(cardHeight: number, opened = false): CardPlacement | null {
  const env = readEnv()
  const page = document.querySelector('[data-page]')?.getBoundingClientRect()
  const pageRight = page ? page.right : env.contentRight
  const room = env.vw - EDGE - (pageRight + GAP)
  // A card the player opened may sit over the page, bottom right; one that
  // appeared on its own (a run's progress) never covers the page's controls.
  if (room < MIN_WIDTH && !opened) return null
  const width = Math.round(Math.min(MAX_WIDTH, room < MIN_WIDTH ? env.vw - 2 * EDGE : room))
  const maxHeight = Math.max(160, env.vh - env.safeTop - EDGE)
  const h = Math.min(cardHeight, maxHeight)
  return { left: env.vw - EDGE - width, top: Math.round(Math.max(env.safeTop, env.vh - EDGE - h)), width, maxHeight: Math.round(maxHeight) }
}
