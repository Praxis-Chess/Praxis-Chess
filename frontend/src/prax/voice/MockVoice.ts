import { SILENT, type PraxVoice, type PraxVoiceHandle } from './PraxVoice'

/**
 * Bound when the TTS service is unreachable. Prax stays silent and fully
 * functional — the listen control simply never renders, rather than rendering
 * and failing when pressed.
 */
export class MockVoice implements PraxVoice {
  readonly available = false

  async speak(): Promise<PraxVoiceHandle> {
    return { done: Promise.resolve(), energy: () => SILENT }
  }

  stop(): void {}
  pause(): void {}
  resume(): void {}
}
