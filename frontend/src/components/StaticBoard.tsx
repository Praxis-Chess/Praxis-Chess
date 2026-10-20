/**
 * A board for lists (POLISH_PLAN.md U4): a position drawn from its FEN as an
 * 8×8 grid, in the board tokens, with no chess library and no interaction, so
 * a list of fifty costs fifty small grids, not fifty engines of a board.
 * Oriented for the side to move (the player's view of their own decision)
 * unless `orientation` says otherwise. Marked `data-board` for the density
 * harness; announced as one image.
 */
const GLYPH: Record<string, string> = { k: '♚', q: '♛', r: '♜', b: '♝', n: '♞', p: '♟' }
// U+FE0E: text presentation, so the pawn never turns into an emoji.
const TEXT = '︎'

export function StaticBoard({ fen, size = 96, orientation, label }: {
  fen: string
  size?: number
  orientation?: 'white' | 'black'
  /** The accessible name; defaults to who is to move. */
  label?: string
}) {
  const [placement, turn] = fen.split(' ')
  const view = orientation ?? (turn === 'b' ? 'black' : 'white')
  const rows = placement.split('/').map(r => r.replace(/\d/g, d => '.'.repeat(Number(d))).split(''))
  const ranks = view === 'white' ? rows : [...rows].reverse().map(r => [...r].reverse())
  const sq = size / 8
  return (
    <div data-board="static" role="img" aria-label={label ?? `${turn === 'b' ? 'Black' : 'White'} to move`}
         style={{ width: size, height: size, flexShrink: 0, display: 'grid',
                  // Rows fixed too: an empty rank has no content to give it height.
                  gridTemplateColumns: 'repeat(8, 1fr)', gridTemplateRows: 'repeat(8, 1fr)',
                  borderRadius: 2, overflow: 'hidden', border: '1px solid rgba(255,255,255,0.08)' }}>
      {ranks.flatMap((rank, r) => rank.map((p, f) => {
        const light = (r + f) % 2 === 0
        const white = p !== '.' && p === p.toUpperCase()
        return (
          <span key={`${r}${f}`} aria-hidden="true" style={{
            background: light ? 'var(--board-light)' : 'var(--board-dark)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: sq * 0.82, lineHeight: 1,
            fontFamily: '"Segoe UI Symbol", "DejaVu Sans", "Noto Sans Symbols2", sans-serif',
            color: white ? '#FBF8F4' : '#141210',
            // An outline so white reads on bone and black on graphite.
            textShadow: white ? '0 0 1px #000, 0 0 1px #000' : '0 0 1px rgba(255,255,255,0.55)',
          }}>
            {p === '.' ? '' : GLYPH[p.toLowerCase()] + TEXT}
          </span>
        )
      }))}
    </div>
  )
}
