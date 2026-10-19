import { Chess } from 'chess.js'

/**
 * The engine names moves in UCI ("g7g6"); everything a player reads is SAN
 * ("g6"). One format per fact (principle 7). Falls back to the UCI move when
 * the position cannot play it, rather than hiding it.
 */
export function uciToSan(fen: string | null | undefined, uci: string | null | undefined): string | null {
  if (!uci) return null
  if (!fen) return uci
  try {
    const move = new Chess(fen).move({ from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: uci[4] ?? undefined })
    return move?.san ?? uci
  } catch {
    return uci
  }
}
