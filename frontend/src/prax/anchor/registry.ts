/**
 * Contract §4 — registration, placement intent and visibility reporting.
 * This module never measures geometry and never decides where Prax belongs.
 *
 * The PAGE decides where (via PraxAnchor props); the renderer decides how it
 * gets there. Keeping placement here rather than in the renderer is what stops
 * per-page coordinates leaking into the draw loop.
 */
export type AnchorVisibility = 'visible' | 'partial' | 'hidden' | 'absent'

/** Viewport fractions. 0.5/0.5 is dead centre. */
export interface AnchorPlacement {
  x: number
  y: number
}

const FALLBACK: AnchorPlacement = { x: 0.68, y: 0.46 }

class AnchorRegistry {
  private el: HTMLElement | null = null
  private placement: AnchorPlacement = FALLBACK
  private visibility: AnchorVisibility = 'absent'

  set(element: HTMLElement | null, placement: AnchorPlacement): void {
    this.el = element
    this.placement = element ? placement : FALLBACK
    // Optimistic: a freshly mounted anchor is almost always in view, and the
    // IntersectionObserver corrects within a frame if not. Defaulting to
    // 'hidden' would drop every navigation to 20fps until the first callback —
    // and IO stays silent entirely while the document is hidden.
    this.visibility = element ? 'visible' : 'absent'
  }

  clear(element: HTMLElement): void {
    if (this.el === element) {
      this.el = null
      this.placement = FALLBACK
      this.visibility = 'absent'
    }
  }

  /** Reported by PraxAnchor's IntersectionObserver, not polled. */
  setVisibility(v: AnchorVisibility): void {
    this.visibility = v
  }

  get active(): HTMLElement | null {
    return this.el
  }

  getPlacement(): AnchorPlacement {
    return this.placement
  }

  getVisibility(): AnchorVisibility {
    return this.el && this.el.isConnected ? this.visibility : 'absent'
  }

  hasAnchor(): boolean {
    return this.el !== null && this.el.isConnected
  }
}

export const anchorRegistry = new AnchorRegistry()
