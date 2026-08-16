import { useRef, useMemo } from 'react'
import { praxBus, type PraxFocusTarget } from '../core/events'
import { PRAX_CONFIG } from '../core/constants'

/**
 * Contract §1 — raw pointerenter would make Prax twitch at every cursor
 * crossing. USER_FOCUS fires only after a dwell threshold, and cancels cleanly
 * if the pointer leaves first.
 *
 * Spread the returned props onto any element that represents a semantic target.
 */
export function useFocusIntent(target: PraxFocusTarget) {
  const timer = useRef<number | null>(null)
  const fired = useRef(false)

  return useMemo(
    () => ({
      onPointerEnter: () => {
        if (timer.current !== null) return
        timer.current = window.setTimeout(() => {
          timer.current = null
          fired.current = true
          praxBus.emit({ type: 'USER_FOCUS', target })
        }, PRAX_CONFIG.FOCUS_DWELL_MS)
      },
      onPointerLeave: () => {
        if (timer.current !== null) {
          clearTimeout(timer.current)
          timer.current = null
        }
        if (fired.current) {
          fired.current = false
          praxBus.emit({ type: 'USER_FOCUS_END' })
        }
      },
    }),
    [target],
  )
}
