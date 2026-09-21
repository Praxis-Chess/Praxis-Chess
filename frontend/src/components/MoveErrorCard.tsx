import type { MoveError } from '../api/types'

interface Props {
  error: MoveError
  isSelected: boolean
  /** Omitted where the card is a detail panel rather than a list row. */
  onClick?: () => void
}

export const SEVERITY_SYMBOL: Record<string, string> = {
  BLUNDER: '??',
  MISTAKE: '?',
  INACCURACY: '?!',
}

export const SEVERITY_COLOR: Record<string, string> = {
  BLUNDER: 'var(--loss)',
  MISTAKE: 'var(--orange)',
  INACCURACY: 'var(--yellow)',
}

export function MoveErrorCard({ error, isSelected, onClick }: Props) {
  const symbol = SEVERITY_SYMBOL[error.severity] ?? '?'
  const severityClass = `badge badge-${error.severity.toLowerCase()}`

  return (
    <div
      onClick={onClick}
      style={{
        padding: '12px 14px',
        borderRadius: 8,
        cursor: onClick ? 'pointer' : 'default',
        border: `1px solid ${isSelected ? 'var(--accent)' : 'var(--border)'}`,
        background: isSelected ? 'var(--accent-dim)' : 'var(--surface-2)',
        marginBottom: 8,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <span style={{ fontWeight: 700, fontFamily: 'monospace', color: 'var(--text)' }}>
          {Math.ceil(error.move_number / 2)}.{error.move_played}{symbol}
        </span>
        <span className={severityClass}>{error.severity}</span>
        {error.tactical_motif && error.tactical_motif !== 'OTHER' && (
          <span className="badge" style={{ background: 'var(--surface)', color: 'var(--text-muted)', border: '1px solid var(--border)' }}>
            {error.tactical_motif.replace('_', ' ')}
          </span>
        )}
        {error.game_phase && (
          <span style={{ marginLeft: 'auto', fontSize: '0.7rem', color: 'var(--text-muted)' }}>
            {error.game_phase}
          </span>
        )}
      </div>
      {error.better_move && (
        <div style={{ fontSize: '0.8rem', color: 'var(--green)', marginBottom: 4 }}>
          Engine best: <strong>{error.better_move}</strong>
        </div>
      )}
      {error.explanation && error.analysis_state === 'EXPLAINED' && (
        <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>
          {error.explanation}
        </p>
      )}
      {error.analysis_state === 'SKIPPED' && (
        <p style={{ fontSize: '0.78rem', color: 'var(--text-muted)', fontStyle: 'italic' }}>
          Found by the engine, but not among the mistakes written up for this game.
        </p>
      )}
      {error.analysis_state === 'LLM_FAILED' && (
        <p style={{ fontSize: '0.78rem', color: 'var(--red)', fontStyle: 'italic' }}>
          AI commentary failed for this move.
        </p>
      )}
      {error.clock_remaining !== null && error.clock_remaining !== undefined && error.clock_remaining <= 30 && (
        <div style={{ marginTop: 4, fontSize: '0.72rem', color: 'var(--orange)' }}>
          ⏱ Time pressure ({error.clock_remaining}s remaining)
        </div>
      )}
    </div>
  )
}
