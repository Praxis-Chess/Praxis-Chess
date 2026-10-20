import { useSyncExternalStore } from 'react'
import { BACKGROUND_STYLES, type BackgroundStyle } from './styles'

/**
 * Settings → Display: the background style and how it moves. Choices of this
 * device, so they live in localStorage, not with the server's settings.
 * Storage can be unavailable (private windows, blocked site data): then the
 * defaults hold, and a change still applies until reload.
 */
export type BackgroundChoice = BackgroundStyle | 'off'
/** Reacts to Prax; calm (fixed low energy); still (one frame). */
export type BackgroundMotion = 'reacts' | 'calm' | 'still'

export interface BackgroundPreference {
  style: BackgroundChoice
  motion: BackgroundMotion
}

/** Off until a style is chosen in Settings → Display (20 Oct: none of the styles is final yet). */
export const DEFAULT_BACKGROUND: BackgroundPreference = { style: 'off', motion: 'reacts' }

/**
 * Whether Settings offers the background at all. Hidden for now (20 Oct): the
 * styles are not final and the player is designing their own. While hidden the
 * background is Off for everyone, including a browser that saved a style
 * earlier, since there would be no setting left to turn it off. The styles,
 * the renderer and the saved choice stay; turning this on brings them back.
 */
export const BACKGROUND_SETTING = false

const KEY = 'praxis.background'
const STYLES = new Set<string>([...BACKGROUND_STYLES.map(s => s.key), 'off'])
const MOTIONS = new Set<string>(['reacts', 'calm', 'still'])
const listeners = new Set<() => void>()

function read(): BackgroundPreference {
  if (!BACKGROUND_SETTING) return DEFAULT_BACKGROUND
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? 'null') as Partial<BackgroundPreference> | null
    return {
      style: raw?.style && STYLES.has(raw.style) ? raw.style : DEFAULT_BACKGROUND.style,
      motion: raw?.motion && MOTIONS.has(raw.motion) ? raw.motion : DEFAULT_BACKGROUND.motion,
    }
  } catch {
    return DEFAULT_BACKGROUND
  }
}

let value = read()

export function setBackground(patch: Partial<BackgroundPreference>): void {
  value = { ...value, ...patch }
  try {
    localStorage.setItem(KEY, JSON.stringify(value))
  } catch {
    // Not persisted; the choice holds until reload.
  }
  listeners.forEach(l => l())
}

function subscribe(l: () => void) {
  listeners.add(l)
  const onStorage = (e: StorageEvent) => { if (e.key === KEY) { value = read(); l() } }
  window.addEventListener('storage', onStorage)
  return () => { listeners.delete(l); window.removeEventListener('storage', onStorage) }
}

export function useBackground(): BackgroundPreference {
  return useSyncExternalStore(subscribe, () => value, () => DEFAULT_BACKGROUND)
}
