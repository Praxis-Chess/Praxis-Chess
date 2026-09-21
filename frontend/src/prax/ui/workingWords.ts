import { useEffect, useState } from 'react'

/**
 * What Prax says it is doing while it thinks.
 *
 * Replaces the raw tool trace — `get_mistake_patterns · 274` is an
 * implementation detail, and reading a function name is not the same as being
 * told something is happening.
 *
 * Chosen to suggest unhurried, slightly absent-minded rummaging: Prax is going
 * through your games, and that genuinely takes twenty seconds. Deliberately not
 * chess vocabulary — a word like "castling" would read as a claim about the
 * position rather than a description of the wait.
 */
const WORDS = [
  'pondering',
  'mulling',
  'noodling',
  'percolating',
  'rummaging',
  'sifting',
  'squinting',
  'tinkering',
  'unspooling',
  'meandering',
  'whirring',
  'wondering',
  'wrangling',
  'ruminating',
  'puttering',
  'riffling',
  'scribbling',
  'simmering',
  'swirling',
  'tallying',
  'thumbing',
  'tracing',
  'unpacking',
  'weighing',
  'whittling',
  'winnowing',
  'deliberating',
  'orbiting',
  'burrowing',
  'dawdling',
] as const

/** Long enough to read, short enough that the wait never feels stalled. */
const ROTATE_MS = 2400

function pick(exclude?: string): string {
  // Never the same word twice running: a label that "changes" to itself reads
  // as a frozen screen, which is the impression this exists to avoid.
  const pool = exclude ? WORDS.filter(w => w !== exclude) : WORDS
  return pool[Math.floor(Math.random() * pool.length)]
}

/**
 * A word that changes while `active` is true.
 *
 * @param active whether work is in flight. Going false stops the timer rather
 *               than leaving it running behind a finished answer.
 */
export function useWorkingWord(active: boolean): string {
  const [word, setWord] = useState(() => pick())

  useEffect(() => {
    if (!active) return
    // Fresh word each time work starts, so two questions in a row do not open
    // with the same one.
    setWord(w => pick(w))
    const id = window.setInterval(() => setWord(w => pick(w)), ROTATE_MS)
    return () => window.clearInterval(id)
  }, [active])

  return word
}

/**
 * How much Prax looked at, without naming the machinery.
 *
 * The old footer listed tool names — `recommend_openings · 61` — which is an
 * implementation detail wearing the clothes of provenance. What actually earns
 * the player's trust is the DEPTH: how many of their games the answer rests on,
 * and how many separate lookups it took. Both survive; the function names do not.
 */
export function effortLine(steps: { tool: string; sample_size: number }[]): string | null {
  if (!steps || steps.length === 0) return null
  const largest = Math.max(0, ...steps.map(s => s.sample_size || 0))
  const checks = steps.length === 1 ? '1 lookup' : `${steps.length} lookups`
  return largest > 0 ? `Based on ${largest} of your games · ${checks}` : `Checked your library · ${checks}`
}
