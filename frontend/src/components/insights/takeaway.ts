import type { AccuracyTrendPoint } from '../../api/types'

/** Games on each side of a comparison: one game's accuracy is noise, ten is a level. */
const WINDOW = 10
/** Smaller Elo moves than this are the ordinary swing of a few games. */
const ELO_MOVE = 25
/** Smaller accuracy moves than this, in points, are a level held. */
const ACC_MOVE = 2

export interface Takeaway {
  /** The sentence: what the trend chart says, in words. */
  text: string
  /** Whether results got worse, so the page points at the mistakes behind them. */
  falling: boolean
}

const mean = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length
const dayMonth = (iso: string) =>
  new Date(iso.length === 10 ? `${iso}T12:00:00` : iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })

/**
 * The Overview's one line (U6, N8): where your Elo went and whether your play
 * went with it. Elo is compared with the rated game furthest from today's
 * rating (a peak or a trough, at least ten games back), accuracy as the mean of
 * ten games from there against the last ten. Null below twenty games: too few
 * for a story.
 */
export function takeaway(trend: AccuracyTrendPoint[]): Takeaway | null {
  if (trend.length < 2 * WINDOW) return null
  const lastAcc = mean(trend.slice(-WINDOW).map(p => p.accuracy))
  const rated = trend.map((p, i) => ({ p, i })).filter(x => x.p.rating != null)
  const current = rated[rated.length - 1]

  if (!current || rated.length < 2 * WINDOW) {
    const firstAcc = mean(trend.slice(0, WINDOW).map(p => p.accuracy))
    return { text: `Your accuracy ${accuracyMove(firstAcc, lastAcc)} over your last ${trend.length} games.`, falling: lastAcc - firstAcc <= -ACC_MOVE }
  }

  const now = current.p.rating!
  const anchor = rated
    .filter(x => x.i <= trend.length - WINDOW)
    .reduce((a, b) => (Math.abs(b.p.rating! - now) > Math.abs(a.p.rating! - now) ? b : a))
  const then = anchor.p.rating!
  const since = dayMonth(anchor.p.played_at ?? anchor.p.date)
  const thenAcc = mean(trend.slice(anchor.i, anchor.i + WINDOW).map(p => p.accuracy))
  const accDelta = lastAcc - thenAcc
  const eloDelta = now - then

  const acc = Math.abs(accDelta) < ACC_MOVE
    ? `your accuracy held at about ${Math.round(lastAcc)}%`
    : `your accuracy ${accDelta > 0 ? 'rose' : 'fell'} from ${Math.round(thenAcc)}% to ${Math.round(lastAcc)}%`

  if (Math.abs(eloDelta) < ELO_MOVE) {
    return { text: `Your Elo has held around ${now} since ${since}, and ${acc}.`, falling: accDelta <= -ACC_MOVE }
  }
  const verb = eloDelta > 0 ? 'risen' : 'fallen'
  // Elo and accuracy moving together: one story. Apart: say so.
  const together = Math.abs(accDelta) >= ACC_MOVE && Math.sign(accDelta) === Math.sign(eloDelta)
  const tail = together
    ? `, and your play with it: ${acc.replace('your accuracy ', 'accuracy ')}.`
    : ` while ${acc}.`
  return { text: `Your Elo has ${verb} from ${then} on ${since} to ${now}${tail}`, falling: eloDelta < 0 }
}

function accuracyMove(from: number, to: number) {
  return Math.abs(to - from) < ACC_MOVE
    ? `held at about ${Math.round(to)}%`
    : `${to > from ? 'rose' : 'fell'} from ${Math.round(from)}% to ${Math.round(to)}%`
}
