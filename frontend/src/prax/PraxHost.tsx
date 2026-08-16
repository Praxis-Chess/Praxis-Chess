import { PraxCanvas } from './renderer/PraxCanvas'
import { PraxStack } from './ui/PraxStack'
import { PraxHitTarget } from './ui/PraxHitTarget'
import { PraxDebugPanel } from './ui/PraxDebugPanel'
import { usePraxRouterBridge } from './state/routerBridge'

/**
 * Everything Prax mounts, in one place. Must sit inside the Router (the bridge
 * needs useLocation) and is mounted exactly once, outside <Routes>, so the
 * organism survives navigation.
 */
export function PraxHost() {
  usePraxRouterBridge()

  return (
    <>
      <PraxCanvas />
      <PraxHitTarget />
      <PraxStack />
      <PraxDebugPanel />
    </>
  )
}

export { PraxAnchor } from './ui/PraxAnchor'
export { praxThoughts } from './ui/thoughts'
export { praxBus } from './core/events'
export { useFocusIntent } from './interaction/useFocusIntent'
export { praxInteract, type PraxInteraction } from './interaction/interactions'
export { praxAsk } from './ui/PraxAsk'
export { praxSpeak, stopSpeaking, praxVoiceAvailable } from './voice'
