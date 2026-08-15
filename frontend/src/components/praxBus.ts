/**
 * Signal bus for Prax.
 *
 * The companion's reactions are decoupled from whatever causes them, so chess
 * behaviour can be wired in later without touching the animation system: call
 * `praxBus.emit('blunder')` from anywhere and Prax maps it to an impulse.
 *
 * Nothing emits these yet — there is no backend. This is the seam, not the
 * feature.
 */

export type PraxSignal =
  | 'analysis-start'    // engine working — go quiet
  | 'analysis-end'      // release the hush
  | 'variation-change'  // small ripple: "I noticed"
  | 'strong-move'       // brief expansion, then settle
  | 'blunder'           // short pause, slight flatten — neutral, never judgmental
  | 'poke'              // same as a user click on the body

type Handler = (signal: PraxSignal) => void

const handlers = new Set<Handler>()

export const praxBus = {
  emit(signal: PraxSignal) {
    handlers.forEach(h => h(signal))
  },
  /** Returns an unsubscribe function. */
  on(handler: Handler) {
    handlers.add(handler)
    return () => { handlers.delete(handler) }
  },
}
