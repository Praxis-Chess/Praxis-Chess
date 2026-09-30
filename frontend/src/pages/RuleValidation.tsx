import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { LabelCard, MechanismScore, RuleReport } from '../api/types'
import { ChessBoard } from '../components/ChessBoard'
import { buildArrows } from '../components/moveArrows'

/**
 * Hand labels for the rules, and how the rules score against them.
 *
 * The rules produce the training labels, the baseline and the runtime fallback,
 * so they are checked against a person before any of that is trusted (§7.3).
 * Two things about this page are deliberate:
 *
 *  - The rules' verdict is never shown while labelling. A labeller shown the
 *    answer mostly agrees with it, and the precision figure would then measure
 *    deference rather than the rules.
 *  - Mistakes come in a fixed pseudo-random order, so the first hundred labelled
 *    are a sample of the library, not its most recent games.
 */

const CONSEQUENCES: [string, string][] = [
  ['MATED', 'You got mated, and the engine’s move would have avoided it'],
  ['LOST_MATERIAL', 'You lost at least a pawn, net, within 8 plies — and the engine’s move would not have'],
  ['MISSED_MATE', 'The engine’s move forced mate; yours did not'],
  ['MISSED_MATERIAL', 'The engine’s move won material; yours did not'],
  ['NOT_CONCRETE', 'Nothing concrete within 8 plies — the difference is positional'],
]

const MECHANISMS: [string, string][] = [
  ['IGNORED_THREAT', 'The punishing move was already threatened before your move; the engine’s move stops it, yours does not'],
  ['REMOVED_DEFENDER', 'Your move took away the defence of another piece, which is then lost'],
  ['MOVED_INTO_ATTACK', 'The piece you moved went somewhere it can be won'],
  ['LOSING_CAPTURE', 'You captured, but the exchange that follows loses material'],
  ['CREATED_TACTIC', 'Your move allowed a fork, pin, skewer or discovered attack that was not there before'],
  ['MISSED_OPPORTUNITY', 'You missed a win the engine found'],
  ['UNCLEAR', 'Something concrete was lost, but none of these fits — or more than one does'],
  ['NONE', 'Nothing concrete to explain — positional'],
]

export default function RuleValidation() {
  const qc = useQueryClient()
  const next = useQuery({ queryKey: ['diagnosis', 'next'], queryFn: () => api.diagnosis.next() })
  const report = useQuery({ queryKey: ['diagnosis', 'report'], queryFn: () => api.diagnosis.report() })

  const build = useMutation({
    mutationFn: () => api.diagnosis.build(25),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['diagnosis'] })
    },
  })

  // After a rule fix, the stored verdicts are out of date. Rebuilding replaces
  // the evidence and the verdict, never the hand label, so the fix is re-scored
  // against the same labels.
  const rebuild = useMutation({
    mutationFn: () => api.diagnosis.rebuild(100),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['diagnosis'] })
    },
  })

  // A second look at a label you already gave, opened from the disagreements.
  const [reviewId, setReviewId] = useState<string | null>(null)
  const review = useQuery({
    queryKey: ['diagnosis', 'review', reviewId],
    queryFn: () => api.diagnosis.review(reviewId!),
    enabled: !!reviewId,
  })

  const card = next.data ?? null
  const rep = report.data
  const openReview = (id: string) => {
    setReviewId(id)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  return (
    <div className="page">
      <h1>Rule validation</h1>
      <p className="muted" style={{ maxWidth: '68ch' }}>
        Label your own mistakes by hand, and the rules are scored against you. The rules
        produce the labels a model will be trained on, so they get checked first. Their
        verdict is hidden while you label — you should not be agreeing with it, you should
        be testing it.
      </p>

      <section className="card" aria-label="Progress"
        style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
        <strong>
          {!rep ? '—' : rep.labelled < rep.target
            ? `${rep.labelled} of ${rep.target} labelled`
            : `${rep.labelled} labelled · ${rep.fresh.labelled} since the rule fixes`}
        </strong>
        <span className="muted">{rep ? `${rep.built} mistakes have evidence built` : ''}</span>
        <button onClick={() => build.mutate()} disabled={build.isPending}>
          {build.isPending ? 'Building…' : 'Build evidence for 25 more'}
        </button>
        {build.data && (
          <span role="status" className="muted">
            Built {build.data.built} in {(build.data.millis / 1000).toFixed(0)} s
            {build.data.failed > 0 ? ` · ${build.data.failed} failed` : ''} · {build.data.remaining} to go
          </span>
        )}
        {build.isError && <span role="alert" className="error">{(build.error as Error).message}</span>}
        <Link to="/games" className="muted" style={{ marginLeft: 'auto' }}>Evidence lab is on each game →</Link>
      </section>

      <LibraryDiagnosis />

      {rep && rep.stale > 0 && (
        <section className="card" aria-label="Out of date"
          style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
          <span>
            <strong>{rep.stale}</strong> {rep.stale === 1 ? 'mistake was' : 'mistakes were'} built
            with older rules, so the scores below are out of date. Your labels are kept.
          </span>
          <button onClick={() => rebuild.mutate()} disabled={rebuild.isPending}>
            {rebuild.isPending ? 'Rebuilding…' : `Rebuild ${Math.min(rep.stale, 100)} with the current rules`}
          </button>
          {rebuild.isError && <span role="alert" className="error">{(rebuild.error as Error).message}</span>}
        </section>
      )}
      {rebuild.data && (
        <p role="status" className="muted">
          Rebuilt {rebuild.data.built} in {(rebuild.data.millis / 1000).toFixed(0)} s
          {rebuild.data.failed > 0 ? ` · ${rebuild.data.failed} failed` : ''}
          {rebuild.data.remaining > 0 ? ` · ${rebuild.data.remaining} still out of date` : ''}
        </p>
      )}

      {reviewId && review.data && (
        <section className="card" aria-label="Reviewing a label" style={{ display: 'grid', gap: 8 }}>
          <p>
            <strong>Reviewing your label.</strong>{' '}
            <span className="muted">
              You said {review.data.consequence.toLowerCase().replace(/_/g, ' ')} /{' '}
              {review.data.mechanism.toLowerCase().replace(/_/g, ' ')}. Change it, or save it as it is.
              Your first labelling time is kept, so this label stays out of the fresh-sample check.
            </span>
          </p>
          <button type="button" className="secondary" style={{ justifySelf: 'start' }}
            onClick={() => setReviewId(null)}>Back to the queue</button>
          <Labeller key={`review-${reviewId}`} card={review.data.card}
            initial={{ consequence: review.data.consequence, mechanism: review.data.mechanism, note: review.data.note }}
            onSaved={() => setReviewId(null)} />
        </section>
      )}
      {reviewId && review.isError && (
        <p role="alert" className="error">Couldn’t open that label for review.</p>
      )}

      {next.isLoading && <p className="muted">Loading…</p>}
      {!next.isLoading && !card && (
        <p className="muted">
          Nothing waiting to be labelled. Build evidence for more mistakes to continue.
        </p>
      )}
      {card && !reviewId && <Labeller key={card.id} card={card} />}

      {rep && <Scores report={rep} onReview={openReview} />}
    </div>
  )
}

interface LabellerProps {
  card: LabelCard
  /** A saved label, when reviewing one. */
  initial?: { consequence: string; mechanism: string; note: string | null }
  onSaved?: () => void
}

function Labeller({ card, initial, onSaved }: LabellerProps) {
  const qc = useQueryClient()
  const [consequence, setConsequence] = useState<string | null>(initial?.consequence ?? null)
  const [mechanism, setMechanism] = useState<string | null>(initial?.mechanism ?? null)
  const [note, setNote] = useState(initial?.note ?? '')

  const save = useMutation({
    mutationFn: () => api.diagnosis.label(card.id, {
      consequence: consequence!, mechanism: mechanism!, note: note.trim() || null,
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['diagnosis'] })
      onSaved?.()
    },
  })

  const arrows = buildArrows(card.fen, card.played_san, card.best_uci)

  return (
    <section className="card" aria-label={`Label ${card.move_label}`}>
      <h2>
        {card.move_label}{' '}
        <span className="muted" style={{ fontWeight: 'normal' }}>
          {card.severity?.toLowerCase()} · {card.phase.toLowerCase()} · {card.player.toLowerCase()} to move
        </span>
      </h2>

      <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
        <div style={{ flex: '0 1 360px', minWidth: 260 }}>
          <ChessBoard fen={card.fen} playerColor={card.player.toLowerCase()} arrows={arrows} />
          <p className="muted" style={{ fontSize: '0.85rem' }}>
            Played <strong>{card.played_san}</strong> (red) · engine <strong>{card.best_san}</strong> (green)
          </p>
          <p style={{ fontSize: '0.85rem' }}>
            <span className="muted">Played line:</span> {card.played_line.join(' ')}
            <br />
            <span className="muted">Engine line:</span> {card.best_line.join(' ')}
          </p>
        </div>

        <div style={{ flex: '1 1 380px', minWidth: 280 }}>
          <fieldset>
            <legend>What happened?</legend>
            {CONSEQUENCES.map(([value, text]) => (
              <label key={value} style={{ display: 'block', margin: '4px 0' }}>
                <input type="radio" name="consequence" value={value}
                  checked={consequence === value} onChange={() => setConsequence(value)} />{' '}
                <strong>{value.toLowerCase().replace(/_/g, ' ')}</strong> — {text}
              </label>
            ))}
          </fieldset>

          <fieldset style={{ marginTop: 12 }}>
            <legend>Why?</legend>
            {MECHANISMS.map(([value, text]) => (
              <label key={value} style={{ display: 'block', margin: '4px 0' }}>
                <input type="radio" name="mechanism" value={value}
                  checked={mechanism === value} onChange={() => setMechanism(value)} />{' '}
                <strong>{value.toLowerCase().replace(/_/g, ' ')}</strong> — {text}
              </label>
            ))}
          </fieldset>

          <label style={{ display: 'block', marginTop: 12 }}>
            Note (optional — anything the rules should know)
            <textarea id="label-note" value={note} onChange={e => setNote(e.target.value)}
              rows={2} style={{ width: '100%', display: 'block' }} />
          </label>

          <button style={{ marginTop: 12 }}
            disabled={!consequence || !mechanism || save.isPending}
            onClick={() => save.mutate()}>
            {save.isPending ? 'Saving…' : 'Save and next'}
          </button>
          {save.isError && <p role="alert" className="error">{(save.error as Error).message}</p>}
        </div>
      </div>

      <details style={{ marginTop: 12 }}>
        <summary>The evidence graph</summary>
        <pre style={{ whiteSpace: 'pre-wrap', fontSize: '0.8rem' }}>{card.evidence}</pre>
      </details>
    </section>
  )
}

function pct(x: number | null | undefined) {
  return x == null ? '—' : `${Math.round(x * 100)}%`
}

function ci(x: [number, number] | null | undefined) {
  return x ? `${Math.round(x[0] * 100)}–${Math.round(x[1] * 100)}%` : ''
}

function Scores({ report, onReview }: { report: RuleReport; onReview: (id: string) => void }) {
  return (
    <section className="card" aria-label="Rule scores">
      <h2>How the rules score against you</h2>
      {report.labelled === 0 ? (
        <p className="muted">No labels yet.</p>
      ) : (
        <>
          <p>
            Consequence agrees on <strong>{pct(report.consequence_agreement)}</strong>{' '}
            <span className="muted">({ci(report.consequence_ci)})</span>
            {' · '}single-cause mechanism right on <strong>{pct(report.single_cause_accuracy)}</strong>{' '}
            <span className="muted">of {report.single_cause_labelled}</span>
          </p>
          {report.fresh.labelled > 0 && (
            <p aria-label="Fresh labels">
              <strong>On the {report.fresh.labelled} label{report.fresh.labelled === 1 ? '' : 's'} since the rule fixes</strong>{' '}
              <span className="muted">(the rules were not tuned on these)</span>: consequence{' '}
              <strong>{pct(report.fresh.consequence_agreement)}</strong>{' '}
              <span className="muted">({ci(report.fresh.consequence_ci)})</span>
              {' · '}single-cause mechanism <strong>{pct(report.fresh.single_cause_accuracy)}</strong>{' '}
              <span className="muted">of {report.fresh.single_cause_labelled} ({ci(report.fresh.single_cause_ci)})</span>
            </p>
          )}
          <table>
            <thead>
              <tr><th>Mechanism</th><th>Precision</th><th>Recall</th><th>Agree / rule only / you only</th></tr>
            </thead>
            <tbody>
              {report.mechanisms.map((m: MechanismScore) => (
                <tr key={m.mechanism}>
                  <th scope="row">{m.mechanism.toLowerCase().replace(/_/g, ' ')}</th>
                  <td>{pct(m.precision)} <span className="muted">{ci(m.precision_ci)}</span></td>
                  <td>{pct(m.recall)} <span className="muted">{ci(m.recall_ci)}</span></td>
                  <td>{m.true_positives} / {m.false_positives} / {m.false_negatives}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="muted" style={{ fontSize: '0.85rem' }}>
            Intervals are 95% (Wilson). With a hundred labels over six mechanisms most cells are
            small — read the intervals, not the percentages.
          </p>
        </>
      )}

      <h3>Across every mistake with evidence built</h3>
      <ul>
        <li>{pct(report.composite_rate)} fall in the composite subset (no single rule explains them)</li>
        <li>{pct(report.not_concrete_rate)} are not concrete — the rules abstain</li>
        <li>
          Budget: {report.budget.over_budget} of {report.budget.graphs} graphs over · largest{' '}
          {report.budget.max_items} items · tokens median {report.budget.median_tokens}, p95{' '}
          {report.budget.p95_tokens}, max {report.budget.max_tokens}
        </li>
        <li className={report.rule_diagnoses_failing_verification > 0 ? 'error' : undefined}>
          {report.rule_diagnoses_failing_verification} rule diagnoses fail their own verifier
          {report.rule_diagnoses_failing_verification > 0 ? ' — a bug' : ''}
        </li>
      </ul>

      {report.disagreements.length > 0 && (
        <details>
          <summary>
            {report.disagreements.length} disagreement{report.disagreements.length === 1 ? '' : 's'}
          </summary>
          <ul>
            {report.disagreements.map(d => (
              <li key={d.id}>
                <strong>{d.move_label}</strong>: you said {d.human_consequence.toLowerCase()} /{' '}
                {d.human_mechanism.toLowerCase()}, rules said {d.rule_consequence.toLowerCase()} /{' '}
                {d.rule_mechanisms.toLowerCase() || 'none'}
                {d.note ? <span className="muted"> — “{d.note}”</span> : null}{' '}
                <button type="button" className="secondary" style={{ padding: '1px 10px', fontSize: '0.8rem' }}
                  onClick={() => onReview(d.id)}>Review</button>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}

/**
 * Phase 9: a verified diagnosis for every mistake in the library. Newly analysed
 * games are diagnosed as they finish; this is the backfill, and the catch-up
 * after a rule change. It runs on the server, one engine run per mistake, and
 * queues behind an analysis run rather than competing with it.
 */
function LibraryDiagnosis() {
  const qc = useQueryClient()
  const status = useQuery({
    queryKey: ['diagnosis', 'library'],
    queryFn: () => api.diagnosis.library(),
    refetchInterval: (q) => (q.state.data && ['QUEUED', 'RUNNING'].includes(q.state.data.state) ? 3000 : false),
  })
  const start = useMutation({
    mutationFn: () => api.diagnosis.libraryStart(),
    onSettled: () => qc.invalidateQueries({ queryKey: ['diagnosis', 'library'] }),
  })
  const stop = useMutation({
    mutationFn: () => api.diagnosis.libraryStop(),
    onSettled: () => qc.invalidateQueries({ queryKey: ['diagnosis', 'library'] }),
  })
  const s = status.data
  const busy = !!s && (s.state === 'QUEUED' || s.state === 'RUNNING')
  const undiagnosed = s ? s.missing + s.stale : 0
  const uncommented = s ? s.uncommented : 0
  const left = undiagnosed + uncommented

  return (
    <section className="card" aria-label="Whole library"
      style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
      <span>
        {!s ? '—' : busy
          ? <>Diagnosing the library: <strong>{s.built + s.rebuilt}</strong> diagnosed
              {s.commentary_enabled ? <>, <strong>{s.commented}</strong> commented</> : ''}
              {s.failed > 0 ? ` · ${s.failed} failed` : ''}{s.state === 'QUEUED' ? ' · waiting for an analysis run to finish' : ''}</>
          : left === 0
            ? <>Every mistake in the library has a verified diagnosis{s.commentary_enabled ? ' and checked AI commentary' : ''}.</>
            : <>
                {undiagnosed > 0 && <><strong>{undiagnosed}</strong> {undiagnosed === 1 ? 'mistake has' : 'mistakes have'} no
                  current verified diagnosis{s.stale > 0 ? ` (${s.stale} from older rules)` : ''}. </>}
                {uncommented > 0 && <><strong>{uncommented}</strong> {uncommented === 1 ? 'is' : 'are'} waiting for the
                  trained model's commentary (about 5 s each).</>}
              </>}
      </span>
      {!busy && left > 0 && (
        <button onClick={() => start.mutate()} disabled={start.isPending}>
          {undiagnosed > 0 ? `Diagnose all ${undiagnosed}` : `Write commentary for ${uncommented}`}
        </button>
      )}
      {busy && <button onClick={() => stop.mutate()} disabled={stop.isPending}>Stop</button>}
      {s && !busy && (s.state === 'DONE' || s.state === 'STOPPED') && (
        <span role="status" className="muted">
          Last run {s.state === 'DONE' ? 'finished' : 'stopped'}: {s.built} built, {s.rebuilt} rebuilt
          {s.commentary_enabled ? `, ${s.commented} commented` : ''}
          {s.failed > 0 ? `, ${s.failed} failed` : ''}
        </span>
      )}
      {s?.state === 'FAILED' && <span role="alert" className="error">The last run failed: {s.error}</span>}
      {start.isError && <span role="alert" className="error">{(start.error as Error).message}</span>}
    </section>
  )
}

