import { anchorRegistry } from '../anchor/registry'
import type { PraxRenderPolicy } from './runtime'

/**
 * Contract §6 — render frequency is DERIVED from independent signals, never
 * hard-coded into the anchor implementation.
 *
 *        page visibility ──┐
 *        reduced-motion ───┼──▶ render policy ──▶ full / reduced / frozen
 *        anchor visibility ┘
 *
 * Presence (semantic) is a separate axis and is not consulted here: a `focused`
 * Prax scrolled out of view is legitimately `reduced`.
 */
/**
 * Whether the current page shows Prax's body at all (PraxHost decides by route).
 * A hidden body draws nothing, so it costs no frames while it is off screen.
 */
let bodyShown = true
export function setPraxBodyShown(shown: boolean): void {
  bodyShown = shown
}
export function isPraxBodyShown(): boolean {
  return bodyShown
}

export function deriveRenderPolicy(reducedMotion: boolean): PraxRenderPolicy {
  if (!bodyShown) return 'frozen'
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return 'frozen'
  if (reducedMotion) return 'frozen'

  switch (anchorRegistry.getVisibility()) {
    case 'visible':
      return 'full'
    case 'partial':
      return 'reduced'
    case 'hidden':
    case 'absent':
      return 'reduced'
  }
}
