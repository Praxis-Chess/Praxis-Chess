import { useLayoutEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { PraxCanvas } from './renderer/PraxCanvas'
import { setPraxBodyShown } from './state/renderPolicy'
import { PraxStack } from './ui/PraxStack'
import { PraxHitTarget } from './ui/PraxHitTarget'
import { PraxDebugPanel } from './ui/PraxDebugPanel'
import { usePraxRouterBridge } from './state/routerBridge'
import { usePracticeSignal } from './state/usePracticeSignal'

/**
 * Everything Prax mounts, in one place. Must sit inside the Router (the bridge
 * needs useLocation) and is mounted exactly once, outside <Routes>, so the
 * organism survives navigation.
 */
export function PraxHost() {
  usePraxRouterBridge()
  usePracticeSignal()

  // Prax's body appears on the home pages only: Today, Progress and Library.
  // Elsewhere the canvas stays mounted (so the particle body is never rebuilt)
  // but is hidden and frozen, and its click target and cards are not rendered.
  const { pathname } = useLocation()
  const shown = PRAX_BODY_ROUTES.has(pathname)
  useLayoutEffect(() => { setPraxBodyShown(shown) }, [shown])

  return (
    <>
      <PraxCanvas hidden={!shown} />
      {shown && <PraxHitTarget />}
      {shown && <PraxStack />}
      <PraxDebugPanel />
    </>
  )
}

/** The routes that show Prax's body: Today (/), Progress and Library. */
export const PRAX_BODY_ROUTES = new Set(['/', '/progress', '/library'])

export { PraxAnchor } from './ui/PraxAnchor'
export { praxThoughts } from './ui/thoughts'
export { praxBus } from './core/events'
export { useFocusIntent } from './interaction/useFocusIntent'
export { praxInteract, type PraxInteraction } from './interaction/interactions'
export { praxAsk } from './ui/PraxAsk'
export { praxSpeak, stopSpeaking, praxVoiceAvailable } from './voice'
