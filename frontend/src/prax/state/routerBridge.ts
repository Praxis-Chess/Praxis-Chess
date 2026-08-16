import { useLayoutEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import { praxBus } from '../core/events'
import { anchorRegistry } from '../anchor/registry'

/**
 * Contract §2 — React Router fires useLocation once, AFTER the route commits;
 * there is no native start event. Both phases are manufactured from the frame gap.
 *
 * The one-frame delay is only sufficient because PraxAnchor registers in its own
 * layout effect, which has already run by the time this rAF fires.
 */
export function usePraxRouterBridge(): void {
  const { pathname } = useLocation()
  const prev = useRef(pathname)

  useLayoutEffect(() => {
    if (prev.current !== pathname) {
      praxBus.emit({ type: 'NAVIGATION_START', from: prev.current, to: pathname })
    }

    const raf = requestAnimationFrame(() => {
      praxBus.emit({
        type: 'NAVIGATION_END',
        to: pathname,
        hasAnchor: anchorRegistry.hasAnchor(),
      })
    })

    prev.current = pathname
    return () => cancelAnimationFrame(raf)
  }, [pathname])
}
