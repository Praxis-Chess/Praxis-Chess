/**
 * Bridge from the render loop to the DOM layer. The AnchorController writes
 * Prax's settled screen position here each frame; PraxThought reads it to
 * position the hit-target.
 *
 * A plain mutable object rather than state — this updates every frame and must
 * never trigger a React render on its own.
 */
const pos = { x: -9999, y: -9999, scale: 0.24 }

export function setPraxScreenPos(x: number, y: number, scale: number): void {
  pos.x = x
  pos.y = y
  pos.scale = scale
}

export function getPraxScreenPos(): { x: number; y: number; scale: number } {
  return { x: pos.x, y: pos.y, scale: pos.scale }
}
