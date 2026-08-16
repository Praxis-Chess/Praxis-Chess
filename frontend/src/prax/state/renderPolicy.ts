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
export function deriveRenderPolicy(reducedMotion: boolean): PraxRenderPolicy {
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
