import { Area, AreaChart, ReferenceLine, ResponsiveContainer, XAxis, YAxis } from 'recharts'
import type { ReviewMove } from '../../api/types'

/** Your winning chance after each half-move, from the stored curve (White's side, pawns; mate ±100). */
function yourChance(evalWhite: number, playerColour: string) {
  const w = 100 / (1 + Math.exp(-0.00368208 * evalWhite * 100))
  return playerColour === 'black' ? 100 - w : w
}

const SEVERITY_FILL: Record<string, string> = {
  BLUNDER: 'var(--loss)', MISTAKE: 'var(--warn)', INACCURACY: 'var(--text-secondary)',
}

/**
 * The game as one line (U5, N7): your winning chance after every move, the
 * 50% line, your flagged moves as dots in their severity, and the move on the
 * board as a rule. Clicking a point shows that position; the dots and the move
 * list are the same moves, so the chart is a picture, not the only way in.
 */
export function EvalGraph({ evals, moves, playerColour, selectedPly, onPick }: {
  evals: (number | null)[]
  moves: ReviewMove[]
  playerColour: string
  selectedPly: number | null
  onPick: (ply: number) => void
}) {
  const data = moves.map((m, i) => ({
    ply: m.ply,
    you: evals[i] == null ? null : Math.round(yourChance(evals[i]!, playerColour) * 10) / 10,
    severity: m.mistake?.severity ?? null,
  }))
  return (
    <div data-chart="eval" aria-hidden="true" style={{ height: 120 }}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 6, right: 6, bottom: 0, left: 6 }} accessibilityLayer={false}
                   onClick={(s: { activeLabel?: string | number }) => { if (s?.activeLabel != null) onPick(Number(s.activeLabel)) }}>
          <XAxis dataKey="ply" hide />
          <YAxis domain={[0, 100]} hide />
          <ReferenceLine y={50} stroke="var(--hairline-strong)" />
          {selectedPly != null && <ReferenceLine x={selectedPly} stroke="var(--text-secondary)" strokeDasharray="2 3" />}
          <Area type="monotone" dataKey="you" stroke="var(--text-primary)" strokeWidth={1.25}
                fill="color-mix(in srgb, var(--text-primary) 14%, transparent)" isAnimationActive={false} connectNulls={false}
                dot={(p: { cx?: number; cy?: number; payload?: { severity: string | null; ply: number } }) => {
                  if (!p.payload?.severity || p.cx == null || p.cy == null) return <g key={`d-${p.payload?.ply}`} />
                  return (
                    <circle key={`d-${p.payload.ply}`} cx={p.cx} cy={p.cy} r={3.5} style={{ cursor: 'pointer' }}
                            fill={SEVERITY_FILL[p.payload.severity] ?? 'var(--text-secondary)'} stroke="var(--canvas)" strokeWidth={1} />
                  )
                }} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
