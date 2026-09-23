import { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ExplainedMistake } from '../api/types'

/**
 * One game re-diagnosed from evidence the backend computed.
 *
 * This is a lab, not a product page, and it is built to be doubted: every answer
 * is shown next to the exact block the model was given, so a claim can be
 * checked against its evidence instead of taken on trust. That is the entire
 * thesis of the LoRA work in one screen — if the sentence says something the
 * block does not contain, the sentence is wrong, and you can see it.
 *
 * Nothing here writes to the database. The pipeline's own explanations are the
 * baseline everything later gets measured against, and overwriting them with a
 * different model would destroy the comparison.
 */
export default function EvidenceLab() {
  const { id = '' } = useParams()
  const [limit, setLimit] = useState(3)
  const [model, setModel] = useState('praxis-phase1')
  const [run, setRun] = useState(0)

  const query = useQuery({
    queryKey: ['evidence', id, limit, model, run],
    queryFn: () => api.evidence.forGame(id, limit, model),
    enabled: run > 0,
    // An engine search per mistake, then a model call. Minutes, not seconds.
    staleTime: Infinity,
    retry: false,
  })

  return (
    <div className="page">
      <h1>Evidence lab</h1>
      <p className="muted" style={{ maxWidth: '64ch' }}>
        Re-explains this game's worst moves from an evidence block the backend builds —
        a fresh engine search after the move, what it actually costs, and the tactic
        geometry read off the board. The block is shown with every answer so you can
        check the answer against it.
      </p>

      <div className="card" style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <label>
          Mistakes
          <input
            id="evidence-limit"
            type="number"
            min={1}
            max={10}
            value={limit}
            onChange={e => setLimit(Number(e.target.value))}
            style={{ width: 80, display: 'block' }}
          />
        </label>
        <label>
          Model
          <input
            id="evidence-model"
            value={model}
            onChange={e => setModel(e.target.value)}
            style={{ width: 220, display: 'block' }}
          />
        </label>
        <button onClick={() => setRun(r => r + 1)} disabled={query.isFetching}>
          {query.isFetching ? 'Analysing…' : 'Run'}
        </button>
        <Link to={`/games/${id}`} className="muted">← back to the game</Link>
        <Link to="/labels" className="muted">Rule validation →</Link>
      </div>

      {query.isFetching && (
        <p role="status" className="muted">
          One engine search per mistake, then the model. This takes a while.
        </p>
      )}

      {query.isError && (
        <p role="alert" className="error">
          {(query.error as Error).message}
        </p>
      )}

      {query.data && (
        <>
          <section className="card" aria-label="Cost">
            <h2>What it cost</h2>
            <ul>
              <li>
                <strong>{query.data.engine_ms_per_mistake} ms</strong> of engine time per mistake —
                the extra search the evidence needs, which ordinary analysis never does.
              </li>
              <li>
                <strong>{query.data.model_ms_per_mistake} ms</strong> in the model per mistake.
              </li>
              <li className="muted">
                {query.data.explained.length} of {query.data.flagged_total} flagged moves,
                via <code>{query.data.model}</code>.
              </li>
            </ul>
          </section>

          {query.data.explained.map(m => (
            <Mistake key={`${m.move_number}-${m.played_san}`} mistake={m} />
          ))}

          {query.data.explained.length === 0 && (
            <p className="muted">
              Nothing could be replayed from this game's stored positions.
            </p>
          )}
        </>
      )}
    </div>
  )
}

/** "16. a3" for White, "16... Nf6" for Black — move_number is a ply, odd is White. */
function moveLabel(ply: number, san: string) {
  const move = Math.ceil(ply / 2)
  return ply % 2 === 1 ? `${move}. ${san}` : `${move}... ${san}`
}

/**
 * The threat probe is the one fact that decides which lesson applies, so it is
 * stated in words rather than left as numbers to interpret. "You created this"
 * and "you ignored this" need opposite advice.
 *
 * A free move ALWAYS has a best move, so "the probe found a move" is not a
 * threat — the first version of this page said "already threatening" on every
 * mistake of the first real game it ran against. The threat is what passing
 * would cost.
 */
function threatSentence(m: ExplainedMistake): string {
  const t = m.threat
  if (!t.probed) return `Threat probe skipped — ${t.skip_reason}`
  const cost = m.threat_cost == null ? '' : ` (a free move was worth ${m.threat_cost.toFixed(1)} to them)`
  if (m.threat_is_reply) {
    return `The refutation ${m.reply_uci} was already threatened before this move${cost} — the move failed to deal with it, rather than causing it.`
  }
  if (m.threatened) {
    // Say what the refutation was worth on its own: a free tempo flatters
    // almost any move, so "it was also good" is not "it was the threat".
    const replyWorth = m.reply_threat_cost == null
      ? 'was not even available'
      : `was worth only ${m.reply_threat_cost.toFixed(1)}`
    return `The opponent already had a threat, ${t.move_uci}${cost}, but the refutation ${m.reply_uci} ${replyWorth} before this move — this move created a new problem.`
  }
  return `Nothing serious was threatened before this move${cost}, so the move created the problem.`
}

function Mistake({ mistake }: { mistake: ExplainedMistake }) {
  const label = moveLabel(mistake.move_number, mistake.played_san)
  return (
    <section className="card" aria-label={label}>
      <h2>
        {label}{' '}
        <span className="muted" style={{ fontWeight: 'normal' }}>{mistake.severity.toLowerCase()}</span>
      </h2>

      {mistake.explanation ? (
        <p style={{ fontSize: '1.05rem' }}>{mistake.explanation}</p>
      ) : (
        <p role="alert" className="error">
          No answer{mistake.model_error ? `: ${mistake.model_error}` : ''}
        </p>
      )}

      <p className={mistake.threatened ? 'warn' : 'muted'}>{threatSentence(mistake)}</p>

      <p className="muted">
        {mistake.tactics.length > 0
          ? `Geometry: ${mistake.tactics.join(', ').toLowerCase().replace(/_/g, ' ')}`
          : 'No tactic geometry created by the reply'}
        {' · '}
        {mistake.visibility_depth
          ? `visible from depth ${mistake.visibility_depth} (${mistake.visibility.toLowerCase()})`
          : 'drop not visible in this search'}
        {' · '}
        {mistake.engine_ms} ms engine, {mistake.model_ms} ms model
      </p>

      <details>
        <summary>The evidence it was given</summary>
        <pre style={{ whiteSpace: 'pre-wrap', fontSize: '0.85rem' }}>{mistake.block}</pre>
      </details>
    </section>
  )
}
