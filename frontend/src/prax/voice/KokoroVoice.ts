import type { PraxVoice, PraxVoiceHandle } from './PraxVoice'
import { praxAudio } from './audioGraph'

/**
 * Talks to Spring at /api/voice/speak, which proxies the local Kokoro service.
 * The browser never reaches :8087 directly — single origin, and the Python
 * service stays bound to localhost.
 */
export class KokoroVoice implements PraxVoice {
  constructor(readonly available: boolean) {}

  async speak(text: string): Promise<PraxVoiceHandle> {
    const res = await fetch('/api/voice/speak', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text }),
    })
    // 503 is the documented "engine unavailable" path, not an error condition.
    if (!res.ok) throw new Error(`voice unavailable (${res.status})`)

    const buffer = await praxAudio.decode(await res.arrayBuffer())

    let resolve!: () => void
    const done = new Promise<void>((r) => (resolve = r))
    praxAudio.play(buffer, resolve)

    return { done, energy: () => praxAudio.bands() }
  }

  stop(): void {
    praxAudio.stopSource()
  }

  pause(): void {
    praxAudio.suspend()
  }

  resume(): void {
    praxAudio.resume()
  }
}
