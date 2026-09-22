import { Fragment, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type {
  AppSettingsView, Coverage, EngineConfig, EngineVersion, KindEstimate, SettingsBounds, SettingsEstimate,
} from '../api/types'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { PraxAnchor, praxInteract } from '../prax/PraxHost'

/**
 * Settings: which games are synced and analysed, and how deeply the engine
 * looks at them.
 *
 * Engine settings are VERSIONED on the server. A changed configuration is a new
 * version, and every analysed game records the version it was measured with —
 * because a deeper search flags different moves and scores different accuracy,
 * so the same game analysed twice under different settings is two different
 * measurements. That's why this page shows which settings analysed which games.
 */

type EngineDraft = { sweep: string; depth: string; lines: string; explanations: string; all: boolean }
type Draft = {
  sync_from: string; sync_to: string; analysis_from: string; analysis_to: string
  library: EngineDraft; practice: EngineDraft
}

const toDraft = (v: EngineVersion): EngineDraft => ({
  sweep: String(v.sweep_move_time_ms),
  depth: String(v.multi_pv_depth),
  lines: String(v.multi_pv_lines),
  explanations: v.max_explanations == null ? '' : String(v.max_explanations),
  all: v.max_explanations == null,
})

const fromView = (s: AppSettingsView): Draft => ({
  sync_from: s.sync_from ?? '', sync_to: s.sync_to ?? '',
  analysis_from: s.analysis_from ?? '', analysis_to: s.analysis_to ?? '',
  library: toDraft(s.library), practice: toDraft(s.practice),
})

/** An empty or non-numeric field is sent as null so the server can say "required" — never guessed. */
const num = (s: string): number | null => (s.trim() === '' || Number.isNaN(Number(s)) ? null : Number(s))

const toEngine = (d: EngineDraft): EngineConfig => ({
  sweep_move_time_ms: num(d.sweep) as number,
  multi_pv_depth: num(d.depth) as number,
  multi_pv_lines: num(d.lines) as number,
  max_explanations: d.all ? null : num(d.explanations),
})

const orNull = (s: string) => (s === '' ? null : s)

export function fmtDuration(ms: number): string {
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s} s`
  const m = Math.round(s / 60)
  if (m < 60) return `${m} min`
  return `${Math.floor(m / 60)} h ${m % 60} min`
}

const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.78rem', lineHeight: 1.5 }
const errorStyle: React.CSSProperties = { color: 'var(--loss)', fontSize: '0.74rem', marginTop: 4 }
const inputStyle: React.CSSProperties = {
  background: 'var(--surface-2)', color: 'var(--text-primary)', border: '1px solid var(--hairline)',
  borderRadius: 3, padding: '6px 8px', fontSize: '0.82rem', width: '100%', minWidth: 0,
}

function Field({ id, label, help, error, children }: {
  id: string; label: string; help?: string; error?: string; children: React.ReactNode
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
      <label htmlFor={id} style={{ fontSize: '0.78rem', color: 'var(--text-primary)' }}>{label}</label>
      {children}
      {help && <span style={{ ...muted, fontSize: '0.72rem' }}>{help}</span>}
      {error && <span role="alert" style={errorStyle}>{error}</span>}
    </div>
  )
}

// ── Coverage ──────────────────────────────────────────────────────────────────

function CoverageCard({ coverage, onReanalyseOutdated, reanalysing }: {
  coverage: Coverage; onReanalyseOutdated: () => void; reanalysing: boolean
}) {
  const [open, setOpen] = useState<Record<string, boolean>>({})
  const [confirming, setConfirming] = useState(false)
  const maxSynced = Math.max(1, ...coverage.months.map(m => m.synced))

  return (
    <section className="card" aria-labelledby="coverage-title">
      <h2 id="coverage-title" style={{ fontSize: '1rem', marginBottom: 6 }}>Coverage</h2>
      <p style={muted}>
        {coverage.first_game
          ? <>Games from <span className="mono">{coverage.first_game}</span> to <span className="mono">{coverage.last_game}</span>. </>
          : 'No games synced yet. '}
        <span className="mono">{coverage.synced}</span> synced · <span className="mono">{coverage.analyzed}</span> analysed
        · <span className="mono">{coverage.pending}</span> pending · <span className="mono">{coverage.failed}</span> failed
      </p>
      <p style={{ ...muted, marginTop: 2 }}>
        Practice games (counted separately): <span className="mono">{coverage.practice.played}</span> played
        · <span className="mono">{coverage.practice.analyzed}</span> analysed
      </p>

      {coverage.mixed_settings && (
        <div role="status" style={{
          marginTop: 12, padding: '10px 12px', borderLeft: '2px solid var(--warn)',
          background: 'rgba(229, 176, 75, 0.08)', ...muted,
        }}>
          Your analysed games span {coverage.analyzed_with.length} engine settings:{' '}
          {coverage.analyzed_with.map(c => `${c.label} (${c.games})`).join(', ')}. Reports compare only games
          analysed with matching settings.
        </div>
      )}

      {coverage.outdated_in_range > 0 && (
        <div style={{ marginTop: 12, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          {!confirming ? (
            <button className="secondary" onClick={() => setConfirming(true)} disabled={reanalysing}>
              Re-analyse {coverage.outdated_in_range} game{coverage.outdated_in_range === 1 ? '' : 's'} with current settings
            </button>
          ) : (
            <>
              <span style={muted}>
                This re-analyses {coverage.outdated_in_range} game{coverage.outdated_in_range === 1 ? '' : 's'} and
                removes their drill cards, which are regenerated afterwards.
              </span>
              <button onClick={() => { setConfirming(false); onReanalyseOutdated() }}>Yes, re-analyse</button>
              <button className="secondary" onClick={() => setConfirming(false)}>Cancel</button>
            </>
          )}
        </div>
      )}

      {coverage.months.length > 0 && (
        <div style={{ overflowX: 'auto', marginTop: 14 }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.78rem' }}>
            <caption className="micro-label" style={{ textAlign: 'left', paddingBottom: 6 }}>
              Chess.com games by month
            </caption>
            <thead>
              <tr style={{ color: 'var(--text-secondary)', textAlign: 'left' }}>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500 }}>Month</th>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500, textAlign: 'right' }}>Synced</th>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500, textAlign: 'right' }}>Analysed</th>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500, textAlign: 'right' }}>Pending</th>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500, textAlign: 'right' }}>Failed</th>
                <th scope="col" style={{ padding: '4px 8px', fontWeight: 500 }}>Analysed with</th>
              </tr>
            </thead>
            <tbody>
              {coverage.months.map(m => (
                <Fragment key={m.month}>
                  <tr style={{ borderTop: '1px solid var(--hairline)' }}>
                    <th scope="row" style={{ padding: '6px 8px', fontWeight: 500, textAlign: 'left' }}>
                      <button
                        className="secondary"
                        aria-expanded={!!open[m.month]}
                        aria-label={`${m.month}: show days`}
                        onClick={() => setOpen(o => ({ ...o, [m.month]: !o[m.month] }))}
                        style={{ padding: '1px 6px', fontSize: '0.74rem', fontFamily: 'var(--font-mono)' }}
                      >
                        {open[m.month] ? '▾' : '▸'} {m.month}
                      </button>
                    </th>
                    <td className="mono" style={{ padding: '6px 8px', textAlign: 'right' }}>{m.synced}</td>
                    <td className="mono" style={{ padding: '6px 8px', textAlign: 'right' }}>{m.analyzed}</td>
                    <td className="mono" style={{ padding: '6px 8px', textAlign: 'right' }}>{m.pending}</td>
                    <td className="mono" style={{ padding: '6px 8px', textAlign: 'right' }}>{m.failed}</td>
                    <td style={{ padding: '6px 8px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span style={{ ...muted, whiteSpace: 'nowrap' }}>
                          {m.analyzed_with.map(c => `${c.label}${m.analyzed_with.length > 1 ? ` (${c.games})` : ''}`).join(' · ') || '—'}
                        </span>
                        {/* Share of the month analysed, against the busiest month. */}
                        <span aria-hidden="true" style={{
                          flex: 1, minWidth: 40, height: 4, background: 'var(--surface-2)', borderRadius: 2, overflow: 'hidden',
                        }}>
                          <span style={{
                            display: 'block', height: '100%', width: `${(m.analyzed / maxSynced) * 100}%`,
                            background: 'var(--orchid)', borderRadius: 2,
                          }} />
                        </span>
                      </div>
                    </td>
                  </tr>
                  {open[m.month] && m.days.map(d => (
                    <tr key={d.date} style={{ color: 'var(--text-secondary)' }}>
                      <th scope="row" className="mono" style={{ padding: '3px 8px 3px 28px', fontWeight: 400, textAlign: 'left' }}>{d.date}</th>
                      <td className="mono" style={{ padding: '3px 8px', textAlign: 'right' }}>{d.synced}</td>
                      <td className="mono" style={{ padding: '3px 8px', textAlign: 'right' }}>{d.analyzed}</td>
                      <td className="mono" style={{ padding: '3px 8px', textAlign: 'right' }}>{d.pending}</td>
                      <td className="mono" style={{ padding: '3px 8px', textAlign: 'right' }}>{d.failed}</td>
                      <td />
                    </tr>
                  ))}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

// ── Engine ────────────────────────────────────────────────────────────────────

function EngineCard({ kind, title, intro, draft, onChange, active, bounds, errors }: {
  kind: 'library' | 'practice'; title: string; intro: string
  draft: EngineDraft; onChange: (d: EngineDraft) => void
  active: EngineVersion; bounds: SettingsBounds; errors: Record<string, string>
}) {
  const id = (f: string) => `${kind}-${f}`
  const set = (patch: Partial<EngineDraft>) => onChange({ ...draft, ...patch })
  return (
    <section className="card" aria-labelledby={id('title')}>
      <h2 id={id('title')} style={{ fontSize: '1rem', marginBottom: 4 }}>{title}</h2>
      <p style={{ ...muted, marginBottom: 12 }}>
        {intro} Active: <span className="mono">{active.label}</span>.
      </p>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <Field id={id('sweep')} label={`Scan time per move (${bounds.sweep_min}–${bounds.sweep_max} ms)`}
               help="The first pass over every position. Decides which moves get flagged."
               error={errors[`${kind}_sweep_move_time_ms`]}>
          <input id={id('sweep')} type="number" inputMode="numeric" style={inputStyle}
                 min={bounds.sweep_min} max={bounds.sweep_max} value={draft.sweep}
                 onChange={e => set({ sweep: e.target.value })} />
        </Field>
        <Field id={id('depth')} label={`Deep-check depth (${bounds.depth_min}–${bounds.depth_max})`}
               help="How far ahead each flagged move is searched. Each extra ply costs roughly 1.5–2× the time."
               error={errors[`${kind}_multi_pv_depth`]}>
          <input id={id('depth')} type="number" inputMode="numeric" style={inputStyle}
                 min={bounds.depth_min} max={bounds.depth_max} value={draft.depth}
                 onChange={e => set({ depth: e.target.value })} />
        </Field>
        <Field id={id('lines')} label={`Candidate moves per position (${bounds.lines_min}–${bounds.lines_max})`}
               help="The engine's top N moves from each position. Your played move is always checked as well. N lines costs roughly N× one."
               error={errors[`${kind}_multi_pv_lines`]}>
          <input id={id('lines')} type="number" inputMode="numeric" style={inputStyle}
                 min={bounds.lines_min} max={bounds.lines_max} value={draft.lines}
                 onChange={e => set({ lines: e.target.value })} />
        </Field>
        <Field id={id('explanations')} label={`Written explanations per game (0–${bounds.explanations_max})`}
               help="How many flagged moves get a written explanation. The rest are kept engine-only."
               error={errors[`${kind}_max_explanations`]}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
            <input id={id('explanations')} type="number" inputMode="numeric" style={{ ...inputStyle, maxWidth: 120 }}
                   min={0} max={bounds.explanations_max} value={draft.explanations} disabled={draft.all}
                   onChange={e => set({ explanations: e.target.value })} />
            <label style={{ ...muted, display: 'flex', gap: 6, alignItems: 'center' }}>
              <input type="checkbox" checked={draft.all} onChange={e => set({ all: e.target.checked })} />
              Every flagged move
            </label>
          </div>
        </Field>
      </div>
    </section>
  )
}

function EstimateLine({ label, e, extra }: { label: string; e: KindEstimate | undefined; extra?: React.ReactNode }) {
  if (!e) return null
  if (!e.measured || e.per_game_ms == null) {
    return (
      <p style={muted}>
        {label}: no estimate yet. Timings are recorded as games are analysed; after three, this shows a
        measured figure.
      </p>
    )
  }
  return (
    <p style={muted}>
      {label}: ≈ <span className="mono">{fmtDuration(e.per_game_ms)}</span> per game{extra}
      {' '}<span style={{ color: 'var(--text-tertiary)' }}>
        (measured on {e.samples} games with {e.basis_label}{e.explanations_measured ? '' : '; explanation time not yet measured'})
      </span>
    </p>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export function Settings() {
  const qc = useQueryClient()
  const { data: view } = useQuery({ queryKey: ['settings'], queryFn: api.settings.get })
  const { data: coverage } = useQuery({ queryKey: ['settings-coverage'], queryFn: api.settings.coverage })

  const [draft, setDraft] = useState<Draft | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => { if (view && !draft) setDraft(fromView(view)) }, [view, draft])

  // Estimate follows the draft, debounced so typing "24" doesn't also ask about "2".
  const [debounced, setDebounced] = useState<Draft | null>(null)
  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(draft), 400)
    return () => window.clearTimeout(t)
  }, [draft])
  const { data: estimate } = useQuery<SettingsEstimate>({
    queryKey: ['settings-estimate', debounced?.library, debounced?.practice],
    queryFn: () => api.settings.estimate({ library: toEngine(debounced!.library), practice: toEngine(debounced!.practice) }),
    enabled: !!debounced,
  })

  const save = useMutation({
    mutationFn: (d: Draft) => api.settings.save({
      sync_from: orNull(d.sync_from), sync_to: orNull(d.sync_to),
      analysis_from: orNull(d.analysis_from), analysis_to: orNull(d.analysis_to),
      library: toEngine(d.library), practice: toEngine(d.practice),
    }),
    onSuccess: r => {
      if (!r.ok) {
        setErrors(r.errors)
        setNotice(null)
        return
      }
      setErrors({})
      setDraft(fromView(r.saved.settings))
      const made = [
        r.saved.library_version_created && r.saved.settings.library.label,
        r.saved.practice_version_created && r.saved.settings.practice.label,
      ].filter(Boolean)
      setNotice(made.length
        ? `Saved. New engine settings: ${made.join(', ')}. Games analysed from now on record this version; earlier games keep theirs.`
        : 'Saved.')
      qc.invalidateQueries({ queryKey: ['settings'] })
      qc.invalidateQueries({ queryKey: ['settings-coverage'] })
    },
  })

  const reanalyse = useMutation({
    mutationFn: () => api.analysis.reanalyzeAll(true),
    onSuccess: r => {
      setNotice(`Queued ${r.games_queued} game${r.games_queued === 1 ? '' : 's'} for re-analysis with the current settings.`)
      qc.invalidateQueries({ queryKey: ['settings-coverage'] })
    },
  })

  if (!view || !coverage || !draft) return <LoadingSpinner label="Loading settings…" />

  const b = view.bounds
  const set = (patch: Partial<Draft>) => setDraft({ ...draft, ...patch })

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 920 }}>
      <div>
        <h1 style={{ fontSize: '1.3rem' }}>Settings</h1>
        <p style={muted}>Which games are synced and analysed, and how deeply the engine looks at them.</p>
      </div>

      <CoverageCard coverage={coverage} reanalysing={reanalyse.isPending}
                    onReanalyseOutdated={() => { praxInteract('PRIMARY_ACTION'); reanalyse.mutate() }} />

      <section className="card" aria-labelledby="ranges-title">
        <h2 id="ranges-title" style={{ fontSize: '1rem', marginBottom: 4 }}>Date ranges</h2>
        <p style={{ ...muted, marginBottom: 12 }}>
          Games already stored outside a range are kept. A range decides what gets fetched and analysed from
          now on; it doesn't delete anything.
        </p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 280px), 1fr))', gap: 16 }}>
          <fieldset style={{ border: 'none', display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
            <legend style={{ fontSize: '0.82rem', fontWeight: 600, marginBottom: 6 }}>Sync</legend>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <Field id="sync-from" label="From">
                <input id="sync-from" type="date" style={inputStyle} min={b.earliest_date} value={draft.sync_from}
                       onChange={e => set({ sync_from: e.target.value })} />
              </Field>
              <Field id="sync-to" label="To">
                <input id="sync-to" type="date" style={inputStyle} value={draft.sync_to}
                       onChange={e => set({ sync_to: e.target.value })} />
              </Field>
            </div>
            <span style={{ ...muted, fontSize: '0.72rem' }}>
              Leave both empty for the default: Sync Now fetches this month, Re-Sync the last three.
            </span>
            {errors.sync_range && <span role="alert" style={errorStyle}>{errors.sync_range}</span>}
          </fieldset>
          <fieldset style={{ border: 'none', display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
            <legend style={{ fontSize: '0.82rem', fontWeight: 600, marginBottom: 6 }}>Analysis</legend>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <Field id="analysis-from" label="From">
                <input id="analysis-from" type="date" style={inputStyle} value={draft.analysis_from}
                       onChange={e => set({ analysis_from: e.target.value })} />
              </Field>
              <Field id="analysis-to" label="To">
                <input id="analysis-to" type="date" style={inputStyle} value={draft.analysis_to}
                       onChange={e => set({ analysis_to: e.target.value })} />
              </Field>
            </div>
            <span style={{ ...muted, fontSize: '0.72rem' }}>
              Analyze Pending and Re-analyze All act only on games inside this range. Either end may be left open.
            </span>
            {errors.analysis_range && <span role="alert" style={errorStyle}>{errors.analysis_range}</span>}
          </fieldset>
        </div>
      </section>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 300px), 1fr))', gap: 16 }}>
        <EngineCard kind="library" title="Engine — your Chess.com games"
                    intro="Used when analysing synced games." draft={draft.library}
                    onChange={d => set({ library: d })} active={view.library} bounds={b} errors={errors} />
        <EngineCard kind="practice" title="Engine — practice games"
                    intro={`A practice report must arrive within ${fmtDuration(b.practice_budget_ms)}, so these are checked against that.`}
                    draft={draft.practice} onChange={d => set({ practice: d })}
                    active={view.practice} bounds={b} errors={errors} />
      </div>

      <section className="card" aria-labelledby="estimate-title">
        <h2 id="estimate-title" style={{ fontSize: '1rem', marginBottom: 6 }}>Estimated time</h2>
        <EstimateLine label="Chess.com games" e={estimate?.library}
                      extra={estimate?.library_total_ms != null
                        ? <> — re-analysing the {estimate.games_in_range} game{estimate.games_in_range === 1 ? '' : 's'} in
                            range: ≈ <span className="mono">{fmtDuration(estimate.library_total_ms)}</span></>
                        : null} />
        <EstimateLine label="Practice games" e={estimate?.practice}
                      extra={estimate?.practice_within_budget === false
                        ? <span style={{ color: 'var(--loss)' }}> — over the report window</span>
                        : estimate?.practice_within_budget ? ' — within the report window' : null} />
        {errors.practice_budget && <p role="alert" style={errorStyle}>{errors.practice_budget}</p>}
      </section>

      {/* Bottom RIGHT, where a form's primary action conventionally sits — and
          clear of the bottom-left corner, which the dev-only PraxDebugPanel
          occupies. Left-aligned, the Save button sat under it at the end of the
          page on the dev server. */}
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
        {notice && <span role="status" style={muted}>{notice}</span>}
        {Object.keys(errors).length > 0 && (
          <span role="alert" style={errorStyle}>Some settings need fixing — see the highlighted fields.</span>
        )}
        <button className="solid" onClick={() => { praxInteract('PRIMARY_ACTION'); save.mutate(draft) }}
                disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Save settings'}
        </button>
      </div>

      <PraxAnchor x={0.9} y={0.45} />
    </div>
  )
}
