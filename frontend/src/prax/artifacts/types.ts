/**
 * The Prax Artifact Protocol — client side.
 *
 * Mirrors com.praxis.prax.artifact. Every field here was computed by the
 * backend; none of it was authored by the model. The frontend's only job is to
 * render what it is given, and to render nothing at all when it does not
 * recognise something.
 */

export type ArtifactType = 'CHESS_POSITION' | 'MOVE_COMPARISON' | 'CHART' | 'TABLE'

/** What an arrow MEANS. The backend states meaning; this side owns the palette. */
export type ArrowRole = 'BEST' | 'PLAYED' | 'THREAT'

export interface ArtifactArrow {
  from: string
  to: string
  role: ArrowRole
}

export interface ChessPositionArtifact {
  type: 'CHESS_POSITION'
  id: string
  title: string
  /** From a chesslib replay of the real game. Never from the model. */
  fen: string
  orientation: 'white' | 'black'
  highlights: string[]
  arrows: ArtifactArrow[]
  caption: string | null
}

/**
 * The board, plus what the move cost.
 *
 * Emitted INSTEAD of a CHESS_POSITION when the played move is known — never as
 * well, or one position would render two boards.
 */
export interface MoveComparisonArtifact {
  type: 'MOVE_COMPARISON'
  id: string
  title: string
  fen: string
  orientation: 'white' | 'black'
  highlights: string[]
  arrows: ArtifactArrow[]
  played_move: string | null
  best_move: string | null
  eval_before: number | null
  eval_after: number | null
  /** Null means not measured. NEVER 0 — that would read as "cost nothing". */
  loss_pawns: number | null
  caption: string | null
}

/** A closed set, so an axis label can never be something the model invented. */
export type ChartId = 'MISTAKES_BY_MOTIF' | 'MISTAKES_BY_PHASE' | 'GAMES_BY_OPENING'

export interface ChartArtifact {
  type: 'CHART'
  id: string
  title: string
  chart_id: ChartId
  unit: string
  bars: { label: string; value: number }[]
  caption: string | null
}

export interface TableArtifact {
  type: 'TABLE'
  id: string
  title: string
  columns: string[]
  align: ('LEFT' | 'RIGHT')[]
  /** Pre-formatted by the backend, which knows the units. */
  rows: string[][]
  caption: string | null
}

/**
 * Kept as a union so adding a type is a compile error everywhere it must be
 * handled, rather than a silent omission.
 */
export type PraxArtifact =
  | ChessPositionArtifact
  | MoveComparisonArtifact
  | ChartArtifact
  | TableArtifact
