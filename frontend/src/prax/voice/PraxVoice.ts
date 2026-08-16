/**
 * The voice adapter contract.
 *
 * Nothing above this line knows which engine is running. Swapping Kokoro for
 * Piper or OpenVoice touches one file and nothing in the renderer, state
 * machine, event bus or chess UI — the same separation the particle renderer
 * already uses.
 */

export interface PraxVoiceBands {
  low: number
  mid: number
  high: number
  overall: number
}

export interface PraxVoiceHandle {
  /** Resolves when playback finishes, or when stop() cuts it short. */
  done: Promise<void>
  /** Live 0..1 envelope, read per frame by the renderer. Never React state. */
  energy(): PraxVoiceBands
}

export interface PraxVoice {
  speak(text: string): Promise<PraxVoiceHandle>
  stop(): void
  pause(): void
  resume(): void
  /** False when the service is unreachable — the UI hides the listen control. */
  readonly available: boolean
}

export const SILENT: PraxVoiceBands = { low: 0, mid: 0, high: 0, overall: 0 }
