import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../../api/client'
import { praxBus } from '../core/events'
import { narrationStore } from './narrationStore'
import { narratePractice, PRACTICE_MILESTONES } from './progressNarrator'

/** How long the line stays up. Long enough to read once, not long enough to nag. */
const HOLD_MS = 6000

/**
 * Notices the day's first meaningful activity and lets Prax mark it.
 *
 * There is no server push, so the transition is detected client-side: the
 * streak query flipping `practiced_today` from false to true is exactly the
 * "first activity of the day" signal the backend ledger computes internally.
 * Anything that records practice invalidates `['practice-streak']`, this
 * refetches, and the edge fires once.
 *
 * Mounted once in PraxHost rather than per page — a drill completes on Session,
 * but the streak widget lives on Today, and the signal must not depend on which
 * page happens to be open.
 */
export function usePracticeSignal(): void {
  const { data } = useQuery({
    queryKey: ['practice-streak'],
    queryFn: () => api.practice.streak(),
    staleTime: 60_000,
  })

  // undefined until the first response — otherwise the initial load looks like
  // a false→true edge and Prax congratulates you for opening the app.
  const wasPracticed = useRef<boolean | undefined>(undefined)

  useEffect(() => {
    if (!data) return

    const prev = wasPracticed.current
    wasPracticed.current = data.practiced_today

    if (prev !== false || !data.practiced_today) return

    const streak = data.current_streak
    praxBus.emit({
      type: 'PRACTICE_LOGGED',
      streak,
      milestone: PRACTICE_MILESTONES.includes(streak),
    })

    narrationStore.set(narratePractice(streak))
    const t = setTimeout(() => narrationStore.clear(), HOLD_MS)
    return () => clearTimeout(t)
  }, [data])
}
