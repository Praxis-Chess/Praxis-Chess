import { Fragment, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type {
  AppSettingsView, Coverage, EngineConfig, EngineVersion, KindEstimate, SettingsBounds, SettingsEstimate,
} from '../api/types'
import { LibraryCard } from '../components/LibraryCard'
import { AiModelsCard } from '../components/AiModelsCard'
import { PlayerCard } from '../components/PlayerCard'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { PraxAnchor, praxInteract } from '../prax/PraxHost'
import { BackupFailedNotice } from '../components/BackupFailedNotice'
import { BackupsCard } from '../components/BackupsCard'
import { useHelp } from '../components/report/ReportParts'

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
  time_classes: string[]
  library: EngineDraft; practice: EngineDraft
}

/** Chess.com's time classes, in speed order: every one is synced, the ticked ones analysed. */
const TIME_CLASSES = ['bullet', 'blitz', 'rapid', 'daily']

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
  time_classes: s.analysis_time_classes ?? ['rapid'],
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

function Field({ id, label, error, children }: {
  id: string; label: string; error?: string; children: React.ReactNode
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
      <label htmlFor={id} style={{ fontSize: '0.78rem', color: 'var(--text-primary)' }}>{label}</label>
      {children}
      {error && <span role="alert" style={errorStyle}>{error}</span>}
    </div>
  )
}

// ── Coverage ──────────────────────────────────────────────────────────────────

function CoverageCard({ coverage, onReanalyseOutdated, reanalysing, reanalyseError, onDismissError }: {
  coverage: Coverage; onReanalyseOutdated: (skipBackup: boolean) => void; reanalysing: boolean
  reanalyseError: unknown; onDismissError: () => void
}) {
  const [open, setOpen] = useState<Record<string, boolean>>({})
  const [confirming, setConfirming] = useState(false)
  const maxSynced = Math.max(1, ...coverage.months.map(m => m.synced))

  return (
    <section className="card" aria-labelledby="coverage-title">
      <h2 id="coverage-title" style={{ fontSize: '1rem', marginBottom: 6 }}>Coverage</h2>
      <p style={muted}>
        {coverage.first_game
          ? <><span className="mono">{coverage.first_game}</span> → <span className="mono">{coverage.last_game}</span></>
          : 'No games yet'}
        {' · practice '}<span className="mono">{coverage.practice.played}</span> played,
        {' '}<span className="mono">{coverage.practice.analyzed}</span> analysed
        {coverage.months.length === 0 && <> · <span className="mono">{coverage.synced}</span> synced</>}
      </p>

      {coverage.mixed_settings && (
        <div role="status" style={{
          marginTop: 12, padding: '10px 12px', borderLeft: '2px solid var(--warn)',
          background: 'rgba(229, 176, 75, 0.08)', ...muted,
        }}>
          Your analysed games span {coverage.analyzed_with.length} engine settings:{' '}
          {coverage.analyzed_with.map(c => `${c.label} (${c.games})`).join(', ')}.
        </div>
      )}

      {coverage.outdated_in_range > 0 && (
        <div style={{ marginTop: 12, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          {!confirming ? (
            <button className="secondary" onClick={() => setConfirming(true)} disabled={reanalysing}>
              {reanalysing ? 'Backing up, then queuing…'
                : `Re-analyse ${coverage.outdated_in_range} game${coverage.outdated_in_range === 1 ? '' : 's'} with current settings`}
            </button>
          ) : (
            <>
              <span style={muted}>
                This re-analyses {coverage.outdated_in_range} game{coverage.outdated_in_range === 1 ? '' : 's'} and
                keeps their drill cards and history; a card whose move is no longer a mistake is paused.
              </span>
              <button onClick={() => { setConfirming(false); onReanalyseOutdated(false) }}>Yes, re-analyse</button>
              <button className="secondary" onClick={() => setConfirming(false)}>Cancel</button>
            </>
          )}
        </div>
      )}

      <BackupFailedNotice error={reanalyseError} busy={reanalysing}
                          onProceed={() => onReanalyseOutdated(true)} onCancel={onDismissError} />

      {coverage.months.length > 0 && (
        <div style={{ overflowX: 'auto', marginTop: 14 }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.78rem' }}>
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
                          {m.analyzed_with.map(c => `${c.label}${m.analyzed_with.length > 1 ? ` (${c.games})` : ''}`).join(' · ') || 'none'}
                        </span>
                        {/* Share of the month analysed, against the busiest month. */}
                        <span aria-hidden="true" style={{
                          flex: 1, minWidth: 40, height: 4, background: 'var(--surface-2)', borderRadius: 2, overflow: 'hidden',
                        }}>
                          <span style={{
                            display: 'block', height: '100%', width: `${(m.analyzed / maxSynced) * 100}%`,
                            background: 'var(--grey-1)', borderRadius: 2,
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
              {/* The totals, as a row: the same columns, read the same way. */}
              <tr style={{ borderTop: '1px solid var(--hairline-lit)', fontWeight: 600 }}>
                <th scope="row" style={{ padding: '6px 8px', fontWeight: 600, textAlign: 'left' }}>Total</th>
                <td className="mono" style={{ padding: '6px 8px', textAlign: 'right' }}>{coverage.synced}</td>
                <td className="mono" style={{ padding: '6px 8px', textAlign: 'right' }}>{coverage.analyzed}</td>
                <td className="mono" style={{ padding: '6px 8px', textAlign: 'right' }}>{coverage.pending}</td>
                <td className="mono" style={{ padding: '6px 8px', textAlign: 'right' }}>{coverage.failed}</td>
                <td />
              </tr>
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

// ── Engine ────────────────────────────────────────────────────────────────────

type Kind = 'library' | 'practice'
const KINDS: { kind: Kind; name: string }[] = [
  { kind: 'library', name: 'Chess.com games' },
  { kind: 'practice', name: 'Practice games' },
]
/** Label, then a value per engine: stacks to one column on a phone, where each value names its engine. */
const engineRow: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: 'minmax(0, 1.3fr) minmax(0, 1fr) minmax(0, 1fr)', gap: 12, alignItems: 'start',
}

/**
 * One engine setting, a row: its label (with its help one tap away) and a value
 * for each engine. The label and its range are said once, not once per engine.
 */
function EngineRow({ label, help, field, drafts, set, errors, input }: {
  label: string; help: string; field: keyof EngineDraft
  drafts: Record<Kind, EngineDraft>; set: (k: Kind, patch: Partial<EngineDraft>) => void
  errors: Record<string, string>
  input: (k: Kind, id: string, value: string, onChange: (v: string) => void) => React.ReactNode
}) {
  const [button, panel] = useHelp(label, help)
  const errorKey: Record<string, string> = {
    sweep: 'sweep_move_time_ms', depth: 'multi_pv_depth', lines: 'multi_pv_lines', explanations: 'max_explanations',
  }
  return (
    <div role="group" aria-label={label} className="stack-on-phone" style={engineRow}>
      <div style={{ fontSize: '0.78rem', color: 'var(--text-primary)', minWidth: 0 }}>{label}{button}{panel}</div>
      {KINDS.map(({ kind, name }) => (
        <div key={kind} role="group" aria-label={name} style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
          <span className="only-stacked micro-label">{name}</span>
          {input(kind, `${kind}-${field}`, String(drafts[kind][field]), v => set(kind, { [field]: v } as Partial<EngineDraft>))}
          {errors[`${kind}_${errorKey[field]}`] && <span role="alert" style={errorStyle}>{errors[`${kind}_${errorKey[field]}`]}</span>}
        </div>
      ))}
    </div>
  )
}

function EngineTable({ drafts, set, view, bounds, errors }: {
  drafts: Record<Kind, EngineDraft>; set: (k: Kind, patch: Partial<EngineDraft>) => void
  view: AppSettingsView; bounds: SettingsBounds; errors: Record<string, string>
}) {
  const numberInput = (min: number, max: number, label: string) =>
    (k: Kind, id: string, value: string, onChange: (v: string) => void) => (
      <input id={id} type="number" inputMode="numeric" style={inputStyle} min={min} max={max} value={value}
             aria-label={`${label}, ${KINDS.find(x => x.kind === k)!.name}`} onChange={e => onChange(e.target.value)} />
    )
  const rows: { field: keyof EngineDraft; label: string; help: string; min: number; max: number }[] = [
    { field: 'sweep', label: `Scan time per move (${bounds.sweep_min}–${bounds.sweep_max} ms)`, min: bounds.sweep_min, max: bounds.sweep_max,
      help: 'The first pass over every position. Decides which moves get flagged.' },
    { field: 'depth', label: `Deep-check depth (${bounds.depth_min}–${bounds.depth_max})`, min: bounds.depth_min, max: bounds.depth_max,
      help: 'How far ahead each flagged move is searched. Each extra ply costs roughly 1.5–2× the time.' },
    { field: 'lines', label: `Candidate moves per position (${bounds.lines_min}–${bounds.lines_max})`, min: bounds.lines_min, max: bounds.lines_max,
      help: 'The engine\'s top N moves from each position. Your played move is always checked as well. N lines costs roughly N× one.' },
  ]
  const explanations = `Written explanations per game (0–${bounds.explanations_max})`
  return (
    <section className="card" aria-labelledby="engine-title">
      <h2 id="engine-title" style={{ fontSize: '1rem', marginBottom: 12 }}>Engine</h2>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="stack-on-phone" style={engineRow}>
          <span />
          {KINDS.map(({ kind, name }) => (
            <div key={kind} role="group" aria-label={name} style={{ minWidth: 0 }}>
              <div style={{ fontSize: '0.82rem', fontWeight: 600 }}>{name}</div>
              <div style={{ ...muted, fontSize: '0.72rem' }}>
                {kind === 'practice' && <>within {fmtDuration(bounds.practice_budget_ms)} · </>}
                active <span className="mono">{view[kind].label}</span>
              </div>
            </div>
          ))}
        </div>
        {rows.map(r => (
          <EngineRow key={r.field} label={r.label} help={r.help} field={r.field} drafts={drafts} set={set}
                     errors={errors} input={numberInput(r.min, r.max, r.label)} />
        ))}
        {/* Per-move explanations are retired (Phase 12): kept, with their values, one tap away. */}
        <details data-disclose className="disclose">
          <summary>Retired explanations</summary>
          <div style={{ paddingTop: 8 }}>
            <EngineRow label={explanations} field="explanations" drafts={drafts} set={set} errors={errors}
                       help="How many flagged moves get a written explanation. The rest are kept engine-only."
                       input={(k, id, value, onChange) => (
                         <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
                           <input id={id} type="number" inputMode="numeric" style={{ ...inputStyle, maxWidth: 120 }}
                                  min={0} max={bounds.explanations_max} value={value} disabled={drafts[k].all}
                                  aria-label={`${explanations}, ${KINDS.find(x => x.kind === k)!.name}`}
                                  onChange={e => onChange(e.target.value)} />
                           <label style={{ ...muted, display: 'flex', gap: 6, alignItems: 'center' }}>
                             <input type="checkbox" checked={drafts[k].all} onChange={e => set(k, { all: e.target.checked })} />
                             Every flagged move
                           </label>
                         </div>
                       )} />
          </div>
        </details>
      </div>
    </section>
  )
}

function EstimateTable({ estimate }: { estimate: SettingsEstimate | undefined }) {
  const rows: { label: string; e: KindEstimate | undefined; total: React.ReactNode }[] = [
    { label: 'Chess.com games', e: estimate?.library,
      total: estimate?.library_total_ms != null
        ? <><span className="mono">{estimate.games_in_range}</span> in range: ≈ <span className="mono">{fmtDuration(estimate.library_total_ms)}</span></>
        : null },
    { label: 'Practice games', e: estimate?.practice,
      total: estimate?.practice_within_budget === false
        ? <span style={{ color: 'var(--loss)' }}>over the report window</span>
        : estimate?.practice_within_budget ? 'within the report window' : null },
  ]
  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.78rem' }}>
        <thead>
          <tr>
            <th scope="col" style={{ padding: '4px 8px' }}>Games</th>
            <th scope="col" style={{ padding: '4px 8px', textAlign: 'right' }}>Per game</th>
            <th scope="col" style={{ padding: '4px 8px' }}>Total</th>
            <th scope="col" style={{ padding: '4px 8px' }}>Measured on</th>
          </tr>
        </thead>
        <tbody>
          {rows.filter(r => r.e).map(({ label, e, total }) => (
            <tr key={label} style={{ borderTop: '1px solid var(--hairline)' }}>
              <th scope="row" style={{ padding: '6px 8px', fontWeight: 500, textAlign: 'left' }}>{label}</th>
              {!e!.measured || e!.per_game_ms == null ? (
                <td colSpan={3} style={{ ...muted, padding: '6px 8px' }}>no estimate yet (needs three analysed games)</td>
              ) : (
                <>
                  <td className="mono" style={{ padding: '6px 8px', textAlign: 'right' }}>≈ {fmtDuration(e!.per_game_ms)}</td>
                  <td style={{ ...muted, padding: '6px 8px' }}>{total}</td>
                  <td style={{ ...muted, padding: '6px 8px' }}>
                    <span className="mono">{e!.samples}</span> games, {e!.basis_label}
                    {e!.explanations_measured ? '' : '; explanation time not yet measured'}
                  </td>
                </>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export function Settings() {
  const qc = useQueryClient()
  const { data: view } = useQuery({ queryKey: ['settings'], queryFn: api.settings.get })
  const { data: coverage } = useQuery({ queryKey: ['settings-coverage'], queryFn: api.settings.coverage })
  const { data: player } = useQuery({ queryKey: ['settings-player'], queryFn: api.settings.player })

  const [draft, setDraft] = useState<Draft | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => { if (view && !draft) setDraft(fromView(view)) }, [view, draft])
  // Hooks before the early return below.
  const rangesHelpParts = useHelp('what to sync and analyse',
    'A range decides what is fetched and analysed from now on. Games already stored are never deleted.')
  const speedsHelpParts = useHelp('speeds',
    'Every game is synced. Mixing speeds blurs the patterns (bullet blunders are mostly the clock), so rapid alone is the default.')

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
      analysis_time_classes: d.time_classes,
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
        ? `Saved. New engine settings: ${made.join(', ')} (earlier games keep theirs).`
        : 'Saved.')
      qc.invalidateQueries({ queryKey: ['settings'] })
      qc.invalidateQueries({ queryKey: ['settings-coverage'] })
    },
  })

  const reanalyse = useMutation({
    mutationFn: (skipBackup: boolean) => api.analysis.reanalyzeAll(true, skipBackup),
    onSuccess: r => {
      setNotice(`Queued ${r.games_queued} game${r.games_queued === 1 ? '' : 's'} for re-analysis with the current settings.`)
      qc.invalidateQueries({ queryKey: ['settings-coverage'] })
    },
  })

  if (!view || !coverage || !draft) return <LoadingSpinner label="Loading settings…" />

  const b = view.bounds
  const set = (patch: Partial<Draft>) => setDraft({ ...draft, ...patch })
  // One solid button at a time: the username first, then unsaved settings, else Sync Now.
  const noPlayer = player?.source === 'none'
  const dirty = JSON.stringify(draft) !== JSON.stringify(fromView(view))
  const [rangesHelp, rangesPanel] = rangesHelpParts
  const [speedsHelp, speedsPanel] = speedsHelpParts

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 920 }}>
      <div>
        <h1 style={{ fontSize: '1.3rem' }}>Settings</h1>
      </div>

      <PlayerCard />

      <LibraryCard primary={!noPlayer && !dirty} />

      <CoverageCard coverage={coverage} reanalysing={reanalyse.isPending}
                    reanalyseError={reanalyse.error} onDismissError={() => reanalyse.reset()}
                    onReanalyseOutdated={skip => { praxInteract('PRIMARY_ACTION'); reanalyse.mutate(skip) }} />

      <section className="card" aria-labelledby="ranges-title">
        <h2 id="ranges-title" style={{ fontSize: '1rem', marginBottom: 12 }}>
          What to sync and analyse{rangesHelp}
        </h2>
        {rangesPanel && <div style={{ marginTop: -6, marginBottom: 10 }}>{rangesPanel}</div>}
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
            <span style={{ ...muted, fontSize: '0.72rem' }}>Empty: this month; Re-Sync, last three.</span>
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
            {errors.analysis_range && <span role="alert" style={errorStyle}>{errors.analysis_range}</span>}
            <div role="group" aria-label="Time controls to analyse" style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginTop: 4 }}>
              {TIME_CLASSES.map(tc => (
                <label key={tc} style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: '0.8rem', textTransform: 'capitalize' }}>
                  <input type="checkbox" checked={draft.time_classes.includes(tc)}
                         onChange={e => set({ time_classes: e.target.checked
                           ? TIME_CLASSES.filter(t => t === tc || draft.time_classes.includes(t))
                           : draft.time_classes.filter(t => t !== tc) })} />
                  {tc}
                </label>
              ))}
            </div>
            <span style={{ ...muted, fontSize: '0.72rem' }}>Only ticked speeds are analysed.{speedsHelp}</span>
            {speedsPanel}
            {errors.analysis_time_classes && <span role="alert" style={errorStyle}>{errors.analysis_time_classes}</span>}
          </fieldset>
        </div>
      </section>

      <EngineTable drafts={{ library: draft.library, practice: draft.practice }} view={view} bounds={b} errors={errors}
                   set={(k, patch) => set({ [k]: { ...draft[k], ...patch } })} />

      <AiModelsCard />

      <BackupsCard />

      <section className="card" aria-labelledby="estimate-title">
        <h2 id="estimate-title" style={{ fontSize: '1rem', marginBottom: 6 }}>Estimated time</h2>
        <EstimateTable estimate={estimate} />
        {errors.practice_budget && <p role="alert" style={errorStyle}>{errors.practice_budget}</p>}
      </section>

      {/* Bottom RIGHT, where a form's primary action conventionally sits — and
          clear of the bottom-left corner, which the dev-only PraxDebugPanel
          occupies. Left-aligned, the Save button sat under it at the end of the
          page on the dev server. */}
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
        {notice && <span role="status" style={muted}>{notice}</span>}
        {Object.keys(errors).length > 0 && (
          <span role="alert" style={errorStyle}>Some settings need fixing. See the highlighted fields.</span>
        )}
        <button className={dirty && !noPlayer ? 'solid' : 'secondary'} onClick={() => { praxInteract('PRIMARY_ACTION'); save.mutate(draft) }}
                disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Save settings'}
        </button>
      </div>

      <PraxAnchor x={0.9} y={0.45} />
    </div>
  )
}
