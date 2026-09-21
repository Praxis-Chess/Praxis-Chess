import { Chess } from 'chess.js'

/** Engine bestmoves are stored as UCI ("e2e4"); moves played are stored as SAN. */
const UCI_PATTERN = /^[a-h][1-8][a-h][1-8][qrbn]?$/

export const ARROW_PLAYED_BAD = 'rgba(226, 102, 74, 0.9)'   // --loss
export const ARROW_BETTER     = 'rgba(185, 217, 108, 0.9)'  // --gain
export const ARROW_NEUTRAL    = 'rgba(162, 155, 150, 0.55)' // --text-secondary

export interface Arrow {
  from: string
  to: string
  color: string
}

/**
 * Resolve a move to its squares by playing it on the position it was made in.
 * Accepts either notation — which one it is depends on where the string came
 * from, not on the caller, so both are handled here rather than at each site.
 */
export function moveToSquares(fen: string, notation: string): { from: string; to: string } | null {
  try {
    const chess = new Chess(fen)
    const move = UCI_PATTERN.test(notation)
      ? chess.move({ from: notation.slice(0, 2), to: notation.slice(2, 4), promotion: notation[4] })
      : chess.move(notation)
    return move ? { from: move.from, to: move.to } : null
  } catch {
    return null
  }
}

/**
 * Arrows for a position: what was played, and what the engine preferred.
 *
 * `playedColor` is the caller's, because the same drawing is used for a move
 * that was criticised and one that was not, and colouring an ordinary move red
 * would read as a verdict the engine never gave.
 */
export function buildArrows(
  fenBefore: string | null | undefined,
  played: string | null | undefined,
  betterMove: string | null | undefined,
  playedColor: string = ARROW_PLAYED_BAD,
): Arrow[] {
  if (!fenBefore) return []
  const arrows: Arrow[] = []

  if (played) {
    const squares = moveToSquares(fenBefore, played)
    if (squares) arrows.push({ ...squares, color: playedColor })
  }

  if (betterMove) {
    const squares = moveToSquares(fenBefore, betterMove)
    if (squares) arrows.push({ ...squares, color: ARROW_BETTER })
  }

  return arrows
}
