import type { PraxVoiceBands } from './PraxVoice'

/**
 *   AudioBufferSourceNode → GainNode → AnalyserNode → destination
 *
 * The gain node exists for the stop() fade: cutting a source dead produces an
 * audible click. The analyser is what lets Prax react to its own voice.
 */
export class PraxAudioGraph {
  private ctx: AudioContext | null = null
  private gain: GainNode | null = null
  private analyser: AnalyserNode | null = null
  private freq: Uint8Array | null = null
  private source: AudioBufferSourceNode | null = null

  /** Band edges as bin indices, computed once from the real sample rate. */
  private lowEnd = 0
  private midEnd = 0
  private highEnd = 0

  /**
   * Browsers start an AudioContext suspended until a user gesture. Creating it
   * lazily on first use, then resuming, is what stops the first utterance from
   * silently vanishing.
   */
  ensure(): AudioContext {
    if (!this.ctx) {
      const Ctor: typeof AudioContext =
        window.AudioContext ??
        (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
      this.ctx = new Ctor()

      this.gain = this.ctx.createGain()
      this.analyser = this.ctx.createAnalyser()
      this.analyser.fftSize = 512
      this.analyser.smoothingTimeConstant = 0.6

      this.gain.connect(this.analyser)
      this.analyser.connect(this.ctx.destination)
      this.freq = new Uint8Array(this.analyser.frequencyBinCount)

      const hzPerBin = this.ctx.sampleRate / this.analyser.fftSize
      const bin = (hz: number) =>
        Math.min(this.analyser!.frequencyBinCount - 1, Math.max(1, Math.round(hz / hzPerBin)))
      this.lowEnd = bin(250)
      this.midEnd = bin(2000)
      this.highEnd = bin(8000)
    }
    if (this.ctx.state === 'suspended') void this.ctx.resume()
    return this.ctx
  }

  get context(): AudioContext | null {
    return this.ctx
  }

  async decode(data: ArrayBuffer): Promise<AudioBuffer> {
    return this.ensure().decodeAudioData(data)
  }

  /** Plays a buffer, replacing anything currently playing. */
  play(buffer: AudioBuffer, onEnded: () => void): void {
    const ctx = this.ensure()
    this.stopSource(0)

    const src = ctx.createBufferSource()
    src.buffer = buffer
    src.connect(this.gain!)
    this.gain!.gain.cancelScheduledValues(ctx.currentTime)
    this.gain!.gain.setValueAtTime(1, ctx.currentTime)
    src.onended = () => {
      if (this.source === src) this.source = null
      onEnded()
    }
    this.source = src
    src.start()
  }

  /** Ramps to silence over ~40ms before disconnecting, so stopping never clicks. */
  stopSource(fadeMs = 40): void {
    const src = this.source
    if (!src || !this.ctx || !this.gain) return
    this.source = null
    src.onended = null

    if (fadeMs > 0) {
      const t = this.ctx.currentTime
      this.gain.gain.cancelScheduledValues(t)
      this.gain.gain.setValueAtTime(this.gain.gain.value, t)
      this.gain.gain.linearRampToValueAtTime(0.0001, t + fadeMs / 1000)
      setTimeout(() => {
        try {
          src.stop()
          src.disconnect()
        } catch {
          /* already stopped */
        }
      }, fadeMs + 10)
    } else {
      try {
        src.stop()
        src.disconnect()
      } catch {
        /* already stopped */
      }
    }
  }

  suspend(): void {
    void this.ctx?.suspend()
  }

  resume(): void {
    void this.ctx?.resume()
  }

  /**
   * Three bands, restrained by design (Voice Plan §6):
   *   low  → body expansion · mid → internal drift · high → brightness
   */
  bands(): PraxVoiceBands {
    if (!this.analyser || !this.freq || !this.source) {
      return { low: 0, mid: 0, high: 0, overall: 0 }
    }
    // Cast keeps TS happy across lib.dom versions that type this as ArrayBuffer-backed.
    this.analyser.getByteFrequencyData(this.freq as unknown as Uint8Array<ArrayBuffer>)

    const avg = (from: number, to: number) => {
      let sum = 0
      for (let i = from; i < to; i++) sum += this.freq![i]
      return sum / Math.max(1, to - from) / 255
    }

    const low = avg(1, this.lowEnd)
    const mid = avg(this.lowEnd, this.midEnd)
    const high = avg(this.midEnd, this.highEnd)
    return { low, mid, high, overall: (low + mid + high) / 3 }
  }
}

export const praxAudio = new PraxAudioGraph()
