import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { praxRuntime } from './state/runtime'
import { PraxStack } from './ui/PraxStack'
import { usePraxRouterBridge } from './state/routerBridge'
import { usePracticeSignal } from './state/usePracticeSignal'
import { initPraxVoice } from './voice'
import { praxBus } from './core/events'
import { praxAsk } from './ui/PraxAsk'

/**
 * Everything Prax mounts, in one place (no debug pill: removed 20 Oct). Must sit inside the Router (the bridge
 * needs useLocation) and is mounted exactly once, outside <Routes>.
 *
 * Prax has no particle body any more: the ambient background (src/background)
 * is what shows Prax at work, reading the same runtime. What stays here is the
 * runtime itself, the voice, and Prax's cards, docked bottom right.
 */
export function PraxHost() {
  usePraxRouterBridge()
  usePracticeSignal()
  const { pathname } = useLocation()

  useEffect(() => {
    praxRuntime.start()
    void initPraxVoice()
    // Dev only: tests and the debug panel read state and open the card through here.
    if (import.meta.env.DEV) {
      ;(window as unknown as Record<string, unknown>).__prax = { runtime: praxRuntime, bus: praxBus, ask: praxAsk }
    }
  }, [])

  return (
    PRAX_CARD_ROUTES.has(pathname) ? <PraxStack /> : null
  )
}

/**
 * Prax's cards (analysis progress, the question card) show on the home pages
 * only, as they did beside the old body: elsewhere a docked card, a bottom
 * sheet on a phone, would cover the page's own controls (Play's Resign).
 */
export const PRAX_CARD_ROUTES = new Set(['/', '/progress', '/library'])

export { praxThoughts } from './ui/thoughts'
export { praxBus } from './core/events'
export { useFocusIntent } from './interaction/useFocusIntent'
export { praxInteract, type PraxInteraction } from './interaction/interactions'
export { praxAsk } from './ui/PraxAsk'
export { praxSpeak, stopSpeaking, praxVoiceAvailable } from './voice'
