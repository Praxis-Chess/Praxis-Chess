import { praxBus } from '../core/events'
import { praxRuntime } from '../state/runtime'
import { KokoroVoice } from './KokoroVoice'
import { MockVoice } from './MockVoice'
import { praxAudio } from './audioGraph'
import { EnvelopeFollower } from '../motion/integrate'
import { SILENT, type PraxVoice, type PraxVoiceBands } from './PraxVoice'

export type { PraxVoice, PraxVoiceBands, PraxVoiceHandle } from './PraxVoice'

let voice: PraxVoice = new MockVoice()
let probed = false
let speaking = false

/** Smoothed bands, read by the render loop each frame. Never React state. */
const followers = {
  low: new EnvelopeFollower(80, 250),
  mid: new EnvelopeFollower(80, 250),
  high: new EnvelopeFollower(60, 200),
}
const current: PraxVoiceBands = { low: 0, mid: 0, high: 0, overall: 0 }
let liveEnergy: (() => PraxVoiceBands) | null = null

/** Probe once at startup; bind MockVoice if the service isn't there. */
export async function initPraxVoice(): Promise<void> {
  if (probed) return
  probed = true
  try {
    const res = await fetch('/api/voice/status')
    const ok = res.ok && (await res.json())?.available === true
    voice = ok ? new KokoroVoice(true) : new MockVoice()
    if (import.meta.env.DEV) console.info(`[prax] voice ${ok ? 'ready' : 'unavailable — silent'}`)
  } catch {
    voice = new MockVoice()
  }

  // The FSM already drops to dormant on navigation; without this the audio
  // would keep playing over a page Prax has already left.
  praxBus.on((e) => {
    if (e.type === 'NAVIGATION_START') stopSpeaking()
  })
}

export function praxVoiceAvailable(): boolean {
  return voice.available
}

export function isPraxSpeaking(): boolean {
  return speaking
}

/**
 * Speak a line. Drives the existing `speaking` state through the bus rather
 * than inventing a new one — RESPONSE_STARTED/FINISHED were already wired.
 */
export async function praxSpeak(text: string): Promise<void> {
  if (!voice.available || speaking) return

  // Must happen inside the click handler's task to satisfy autoplay policy.
  praxAudio.ensure()

  speaking = true
  praxBus.emit({ type: 'RESPONSE_STARTED' })
  try {
    const handle = await voice.speak(text)
    liveEnergy = handle.energy
    await handle.done
  } catch {
    /* degrade to silence */
  } finally {
    finish()
  }
}

export function stopSpeaking(): void {
  if (!speaking) return
  voice.stop()
  finish()
}

function finish(): void {
  speaking = false
  liveEnergy = null
  followers.low.reset()
  followers.mid.reset()
  followers.high.reset()
  praxRuntime.motion.setSpeaking(0, 0, 0)
  praxBus.emit({ type: 'RESPONSE_FINISHED' })
}

/**
 * Called once per frame from the render loop. Raw analyser output is far too
 * jittery to drive geometry, so every band passes through an attack/release
 * follower before it reaches the motion model.
 */
export function stepVoiceEnergy(dtMs: number): PraxVoiceBands {
  const raw = liveEnergy ? liveEnergy() : SILENT
  current.low = followers.low.step(raw.low, dtMs)
  current.mid = followers.mid.step(raw.mid, dtMs)
  current.high = followers.high.step(raw.high, dtMs)
  current.overall = (current.low + current.mid + current.high) / 3
  praxRuntime.motion.setSpeaking(current.low, current.mid, current.high)
  return current
}
