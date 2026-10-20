import type { ReviewMove } from '../../api/types'

const SEVERITY_COLOUR: Record<string, string> = {
  BLUNDER: 'var(--loss)', MISTAKE: 'var(--warn)', INACCURACY: 'var(--text-secondary)',
}

/**
 * The whole game as a move list (U5, N7): white and black side by side, your
 * flagged moves in their severity colour. Any move shows its position; a flagged
 * one selects its mistake. Keyboard and screen readers reach every move here.
 */
export function AnalysisMoveList({ moves, activePly, onPick }: {
  moves: ReviewMove[]
  activePly: number | null
  onPick: (move: ReviewMove) => void
}) {
  const rows: { n: number; white?: ReviewMove; black?: ReviewMove }[] = []
  for (const m of moves) {
    const row = rows[m.move_number - 1] ?? (rows[m.move_number - 1] = { n: m.move_number })
    if (m.color === 'white') row.white = m
    else row.black = m
  }
  const cell = (m?: ReviewMove) => m && (
    <button type="button" onClick={() => onPick(m)} aria-pressed={activePly === m.ply}
            aria-label={`${m.move_number}${m.color === 'white' ? '.' : '...'}${m.san}${m.mistake ? `, ${m.mistake.severity.toLowerCase()}` : ''}`}
            // A plain button, so it takes the shared hover and press (U8); only the move on the board keeps a fill.
            style={{ border: 'none', cursor: 'pointer', padding: '1px 6px', borderRadius: 3, fontFamily: 'var(--font-mono)', fontSize: '0.78rem',
                     letterSpacing: 0, lineHeight: 'inherit',
                     color: m.mistake ? SEVERITY_COLOUR[m.mistake.severity] : 'var(--text-primary)',
                     fontWeight: m.mistake ? 600 : 400,
                     background: activePly === m.ply ? 'var(--surface-2)' : undefined }}>
      {m.san}
    </button>
  )
  return (
    <ol aria-label="Moves" style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid',
                                    gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', columnGap: 12, rowGap: 2 }}>
      {rows.filter(Boolean).map(r => (
        <li key={r.n} style={{ display: 'grid', gridTemplateColumns: '2.2rem 1fr 1fr', alignItems: 'baseline' }}>
          <span className="mono" style={{ fontSize: '0.72rem', color: 'var(--text-tertiary)' }}>{r.n}.</span>
          <span>{cell(r.white)}</span>
          <span>{cell(r.black)}</span>
        </li>
      ))}
    </ol>
  )
}
