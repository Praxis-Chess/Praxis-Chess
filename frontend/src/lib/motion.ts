/**
 * Whether this viewer allows motion (U8). CSS honours `prefers-reduced-motion`
 * by itself; script-driven motion (a smooth scroll) asks here.
 */
export function motionOK(): boolean {
  try { return !window.matchMedia('(prefers-reduced-motion: reduce)').matches } catch { return true }
}

/** "smooth" when motion is allowed, otherwise an instant jump. */
export const scrollBehavior = (): ScrollBehavior => (motionOK() ? 'smooth' : 'auto')
