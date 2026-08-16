/**
 * Window-level pointer tracking with dwell detection.
 *
 * The canvas is pointer-events:none (Contract §6) so it receives no events of
 * its own — but a window listener still sees every move, which is all Prax
 * needs. Plain mutable state, never React: this updates continuously and must
 * not cause a render.
 *
 * Prax reacts to STILLNESS, not movement: a cursor sweeping across the page
 * should leave it undisturbed. `stillSince` is what the renderer reads.
 */
const state = {
  x: 0,
  y: 0,
  active: false,
  /** Timestamp the cursor last came to rest. */
  stillSince: 0,
}

/** Sub-pixel jitter and trackpad noise must not count as movement. */
const MOVE_EPSILON_PX = 3

export function initPointerTracking(): () => void {
  const move = (e: PointerEvent) => {
    const moved = Math.hypot(e.clientX - state.x, e.clientY - state.y) > MOVE_EPSILON_PX
    state.x = e.clientX
    state.y = e.clientY
    state.active = true
    if (moved) state.stillSince = performance.now()
  }
  const leave = () => {
    state.active = false
  }

  window.addEventListener('pointermove', move, { passive: true })
  document.addEventListener('mouseleave', leave)
  window.addEventListener('blur', leave)

  return () => {
    window.removeEventListener('pointermove', move)
    document.removeEventListener('mouseleave', leave)
    window.removeEventListener('blur', leave)
  }
}

export function getPointer(): Readonly<typeof state> {
  return state
}

/** Milliseconds the cursor has been at rest. */
export function dwellMs(now: number): number {
  return state.active ? now - state.stillSince : 0
}
