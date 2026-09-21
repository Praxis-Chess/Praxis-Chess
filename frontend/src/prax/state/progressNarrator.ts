/**
 * Turns structured analysis events into Prax's running commentary.
 *
 * Deliberately NOT the LLM. Every line here is derived from application state,
 * so it can never drift from what is actually happening. The LLM interprets
 * afterwards, once there is something real to interpret.
 *
 * Three registers, and nothing else:
 *   operational   — what Prax is doing        "27 of 93 games analyzed."
 *   observational — what Prax is seeing       "Endgame mistakes lead so far."
 *   resolved      — what Prax concluded       "Done. 3 patterns worth a look."
 *
 * No "Hmm…", no "Almost there…", no "Interesting…". Filler reads as theatre and
 * would undo the point of grounding everything else.
 */
export type NarrationKind = 'operational' | 'observational' | 'resolved'

export interface PraxNarration {
  kind: NarrationKind
  text: string
  /** Real counts only — rendered as the evidence row under the message. */
  evidence?: { label: string; value: string }[]
  /** Present only once there is something concrete to open. */
  examineHref?: string
}

/** Streak lengths that earn a different sentence. Rare enough to still mean something. */
export const PRACTICE_MILESTONES = [3, 7, 14, 30, 60, 100]

/**
 * The line Prax says on the day's first real work.
 *
 * Restrained on purpose: this is the moment a habit tracker would fire confetti
 * and shout, and doing that here would undo the register everything else is
 * written in. It states what happened. It does not congratulate.
 */
export function narratePractice(streak: number): PraxNarration {
  const milestone = PRACTICE_MILESTONES.includes(streak)

  if (streak <= 1) {
    return { kind: 'resolved', text: 'Recorded. You examined your chess today.' }
  }
  if (milestone) {
    return {
      kind: 'resolved',
      text: streak >= 30
        ? `${streak} days. This is a practice now, not a streak.`
        : `${streak} days. You're building a habit, not just a number.`,
      evidence: [{ label: 'days examined', value: String(streak) }],
    }
  }
  return { kind: 'resolved', text: `Day ${streak}. You showed up again.` }
}

export function narrateStart(total: number): PraxNarration {
  return {
    kind: 'operational',
    text: total > 0 ? `Re-analyzing ${total} games.` : 'Re-analyzing your games.',
  }
}

export function narrateProgress(completed: number, total: number): PraxNarration {
  return { kind: 'operational', text: `${completed} of ${total} games analyzed.` }
}

export function narratePatternPhase(): PraxNarration {
  return { kind: 'operational', text: 'Looking for patterns across your mistakes.' }
}

export function narrateStopping(): PraxNarration {
  return { kind: 'operational', text: 'Stopping after this game.' }
}

export function narrateStopped(completed: number, total: number): PraxNarration {
  return { kind: 'operational', text: `Stopped. ${completed} of ${total} games analyzed.` }
}

const PHASE_LABEL: Record<string, string> = {
  OPENING: 'opening',
  MIDDLEGAME: 'middlegame',
  ENDGAME: 'endgame',
}

/**
 * The one mid-run observation, drawn from real counts rather than invented.
 * Suppressed entirely when the lead isn't meaningful — a 34/33/33 split is not
 * a finding, and saying so would be exactly the filler this avoids.
 */
export function narrateObservation(
  phaseCounts: { phase: string; count: number }[],
  gamesSoFar: number,
): PraxNarration | null {
  const sorted = [...phaseCounts].sort((a, b) => b.count - a.count)
  const top = sorted[0]
  const rest = sorted.slice(1).reduce((s, p) => s + p.count, 0)
  if (!top || top.count < 5) return null
  // Needs a real lead: at least 40% more than everything else combined.
  if (rest > 0 && top.count < rest * 0.4) return null

  return {
    kind: 'observational',
    text: `${cap(PHASE_LABEL[top.phase] ?? top.phase)} mistakes are leading so far.`,
    evidence: [
      { label: `${PHASE_LABEL[top.phase] ?? top.phase} mistakes`, value: String(top.count) },
      { label: 'games so far', value: String(gamesSoFar) },
    ],
  }
}

export function narrateComplete(
  total: number,
  mistakes: number | null,
  topMotif: string | null,
): PraxNarration {
  const evidence: { label: string; value: string }[] = [{ label: 'games', value: String(total) }]
  if (mistakes != null) evidence.push({ label: 'mistakes found', value: String(mistakes) })
  if (topMotif) evidence.push({ label: 'most common', value: humanMotif(topMotif) })

  return {
    kind: 'resolved',
    text: topMotif
      ? `Done. ${humanMotif(topMotif)} is your most frequent mistake.`
      : `Done. ${total} games analyzed.`,
    evidence,
    examineHref: '/insights',
  }
}

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

const humanMotif = (m: string) =>
  m.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase())
