import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { Chessboard } from 'react-chessboard'
import type { ReportExample } from '../../api/types'

/**
 * Shared pieces of the Pattern Report and Training Plan tabs (Phase 12), and of
 * "Show, don't tell" (DESIGN_SYSTEM.md, principle 12).
 *
 * Colour has one meaning each: pink (--orchid) only for what can be clicked;
 * a bar above your own average is a brighter neutral, never a colour, so amber
 * keeps meaning severity. Everything else is neutral.
 */

export const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.8rem', lineHeight: 1.55 }

/** A section heading: clearly above an item title in size and weight. */
export const sectionTitle: React.CSSProperties = {
  fontSize: '1.08rem', fontWeight: 700, margin: 0, paddingTop: 18, borderTop: '1px solid var(--hairline)',
}

/** An item title inside a section. */
export const itemTitle: React.CSSProperties = { fontSize: '0.9rem', fontWeight: 600, margin: 0 }

/** A rank or marker: neutral, so it is never mistaken for a link. */
export const marker: React.CSSProperties = {
  color: 'var(--text-tertiary)', fontWeight: 700, fontVariantNumeric: 'tabular-nums',
}

export const linkStyle: React.CSSProperties = { color: 'var(--orchid)', fontWeight: 600 }

/** A control that reads as a link: for toggles that open text in place. */
export const linkButton: React.CSSProperties = {
  all: 'unset', cursor: 'pointer', ...linkStyle, fontSize: '0.78rem',
}

/** A section's one-line label: what is measured, or the caveat that changes how it reads. */
export const sectionLabel: React.CSSProperties = { ...muted, margin: 0, fontSize: '0.78rem' }

/**
 * One fact, one format, on both tabs:
 * "32 of 145 (22%) · 19 blunders · −27% winning chance each".
 */
export function statLine(s: { count: number; base: number; pct: number; blunders: number; avg_lost: number }) {
  const b = `${s.blunders} blunder${s.blunders === 1 ? '' : 's'}`
  return `${s.count} of ${s.base} (${s.pct}%) · ${b} · −${Math.round(s.avg_lost)}% winning chance each`
}

/** "17.6 per 100": always one decimal, so a column of rates lines up. */
export const rate1 = (n: number) => n.toFixed(1)

/** The drill route for a list of positions. */
export function drillLink(positions: ReportExample[], session?: number, plan?: string) {
  const q = new URLSearchParams({ positions: positions.map(p => `${p.game_id}:${p.ply}`).join(',') })
  if (session) q.set('session', String(session))
  if (plan) q.set('plan', plan)
  return `/drills?${q}`
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** "6 Sep", the same in every browser (Intl writes "Sept" in some). */
export function dayMonth(d: Date) {
  return `${d.getDate()} ${MONTHS[d.getMonth()]}`
}

/** "6 Sep" from "2026-09-06". */
export function shortDate(iso: string | null) {
  return iso ? dayMonth(new Date(`${iso}T12:00:00`)) : null
}

/**
 * Method behind one disclosure: "How this is measured", never a hover tooltip.
 * Native <details>, so tap, keyboard and screen readers work without script.
 */
export function Disclose({ summary = 'How this is measured', children }: { summary?: string; children: ReactNode }) {
  return (
    <details data-disclose className="disclose">
      <summary>{summary}</summary>
      <div style={{ ...muted, paddingTop: 6 }}>{children}</div>
    </details>
  )
}

/**
 * Help that used to be a hover tooltip: an ⓘ button that shows one line of
 * text on click, tap or Enter. Returns the button and the line separately, so
 * the line can sit under a label or heading without becoming part of its name.
 */
export function useHelp(about: string, text: ReactNode): [ReactNode, ReactNode] {
  const [open, setOpen] = useState(false)
  const id = useId()
  const button = (
    <button type="button" data-disclose aria-expanded={open} aria-controls={id} aria-label={`About ${about}`}
            onClick={e => { e.preventDefault(); setOpen(!open) }}
            style={{ all: 'unset', cursor: 'pointer', marginLeft: 6, color: 'var(--orchid)', fontWeight: 400 }}>ⓘ</button>
  )
  const panel = open
    ? <span id={id} role="note" style={{ ...muted, display: 'block', fontSize: '0.74rem', fontWeight: 400 }}>{text}</span>
    : null
  return [button, panel]
}

/** A heading's help: the ⓘ beside it, the line under it. */
export function HelpLine({ about, children, heading }: { about: string; children: ReactNode; heading: ReactNode }) {
  const [button, panel] = useHelp(about, children)
  return <>{heading}{button}{panel}</>
}

export interface BarRow {
  key: string
  label: string
  value: number
  text: string
  /** False: fewer than the sample threshold behind it; drawn faded, never ranked. */
  enough?: boolean
}

/**
 * Bars that can be compared, against a stated baseline: every track has the
 * same width, the baseline is a tick at the same place on every track, and a
 * bar above it is a brighter neutral. A row with too little behind it is faded.
 */
export function Bars({ rows, baseline, max }: { rows: BarRow[]; baseline?: number; max?: number }) {
  const top = Math.max(1e-9, max ?? Math.max(...rows.map(r => r.value), baseline ?? 0))
  const at = (v: number) => `${Math.max(0, Math.min(100, (100 * v) / top))}%`
  return (
    <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
      {rows.map(r => {
        const above = baseline != null && r.value > baseline
        return (
          <li key={r.key} data-above={above || undefined}
              style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 7.5rem) minmax(0, 1fr) 10.5rem',
                       gap: 12, alignItems: 'center', fontSize: '0.8rem', opacity: r.enough === false ? 0.5 : 1 }}>
            <span style={{ overflowWrap: 'anywhere' }}>{r.label}</span>
            <div aria-hidden="true" style={{ position: 'relative', height: 8, background: 'var(--hairline)', borderRadius: 3 }}>
              <div style={{ width: at(r.value), height: '100%', borderRadius: 3,
                            background: above ? 'color-mix(in srgb, var(--text-primary) 62%, transparent)' : 'var(--grey-3)' }} />
              {baseline != null && (
                <div style={{ position: 'absolute', left: at(baseline), top: -4, bottom: -4, width: 2,
                              background: 'var(--text-primary)' }} />
              )}
            </div>
            <span className="mono" style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', textAlign: 'right',
                                            fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
              {r.text}{r.enough === false ? ' · few' : ''}
            </span>
          </li>
        )
      })}
    </ul>
  )
}

const PHONE = 560
const POPOVER_W = 212

/**
 * A button that opens a small panel beside it, on click, tap or Enter.
 *
 * Non-modal: the page stays usable; Escape or a tap elsewhere closes it, and
 * focus returns to the button. The panel is portalled to <body>: inside a card
 * it would sit in the card's stacking context (its backdrop-filter makes one),
 * and later cards would paint over it. On a phone it docks to the bottom of the
 * screen, where there is always room. It holds real links, so open-in-new-tab
 * works.
 */
export function PopoverButton({ label, title, children, style, ariaLabel }: {
  label: ReactNode
  /** The panel's accessible name. */
  title: string
  children: ReactNode
  style?: React.CSSProperties
  ariaLabel?: string
}) {
  const id = useId()
  const button = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [anchor, setAnchor] = useState<DOMRect | null>(null)

  const close = (refocus: boolean) => { setOpen(false); if (refocus) button.current?.focus() }
  useLayoutEffect(() => {
    if (!open) return
    const measure = () => setAnchor(button.current?.getBoundingClientRect() ?? null)
    measure()
    window.addEventListener('scroll', measure, true)
    window.addEventListener('resize', measure)
    return () => { window.removeEventListener('scroll', measure, true); window.removeEventListener('resize', measure) }
  }, [open])
  useEffect(() => {
    if (!open) return
    panel.current?.focus()
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') close(true) }
    const down = (e: PointerEvent) => {
      const t = e.target as Node
      if (!panel.current?.contains(t) && !button.current?.contains(t)) close(false)
    }
    document.addEventListener('keydown', key)
    document.addEventListener('pointerdown', down)
    return () => { document.removeEventListener('keydown', key); document.removeEventListener('pointerdown', down) }
  }, [open])

  let place: React.CSSProperties = {}
  if (anchor) {
    if (window.innerWidth < PHONE) {
      place = { left: 'max(12px, env(safe-area-inset-left, 0px))', right: 'max(12px, env(safe-area-inset-right, 0px))',
                bottom: 'calc(12px + env(safe-area-inset-bottom, 0px))' }
    } else {
      const right = anchor.right + 10 + POPOVER_W <= window.innerWidth - 8
      place = { width: POPOVER_W,
                left: right ? anchor.right + 10 : Math.max(8, anchor.left - 10 - POPOVER_W),
                top: Math.max(8, Math.min(anchor.top - 8, window.innerHeight - 330)) }
    }
  }

  return (
    <>
      <button ref={button} type="button" data-popover aria-expanded={open} aria-controls={id} aria-label={ariaLabel}
              onClick={() => (open ? close(false) : setOpen(true))}
              style={{ all: 'unset', cursor: 'pointer', ...style }}>
        {label}
      </button>
      {open && createPortal(
        <div ref={panel} id={id} role="dialog" aria-label={title} tabIndex={-1} className="popover-panel"
             style={{ position: 'fixed', zIndex: 1000, padding: 10, boxSizing: 'border-box', outline: 'none',
                      // Opaque: --surface-2 is a translucent tint, and the text behind showed through.
                      background: 'var(--canvas)', border: '1px solid var(--hairline-lit)', borderRadius: 4,
                      boxShadow: '0 8px 24px rgba(0,0,0,0.45)', display: 'flex', flexDirection: 'column', gap: 8,
                      visibility: anchor ? 'visible' : 'hidden', ...place }}>
          {children}
        </div>,
        document.body,
      )}
    </>
  )
}

const BOARD = 168

/** A position's board, in the board tokens every other board uses. */
function MiniBoard({ fen }: { fen: string }) {
  const toMove = fen.split(' ')[1] === 'b' ? 'black' : 'white'
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'center', alignSelf: 'center', width: BOARD }}>
      <Chessboard position={fen} boardOrientation={toMove} arePiecesDraggable={false} boardWidth={BOARD}
                  customDarkSquareStyle={{ backgroundColor: 'var(--board-dark)' }}
                  customLightSquareStyle={{ backgroundColor: 'var(--board-light)' }} />
      <span style={{ ...muted, fontSize: '0.72rem' }}>{toMove === 'white' ? 'White' : 'Black'} to move</span>
    </div>
  )
}

/**
 * One position: the move and what it cost, visible; its board, opening and
 * date one tap away (principle 9, "context one tap away"), with the game and a
 * drill of just this position.
 */
export function PositionChip({ p }: { p: ReportExample }) {
  const lost = `−${Math.round(p.lost)}%`
  const context = [p.opening, shortDate(p.played_on), lost].filter(Boolean).join(' · ')
  return (
    <li data-scope={p.move} style={{ fontSize: '0.8rem', listStyle: 'none' }}>
      <PopoverButton title={`${p.move}: the position`} ariaLabel={`${p.move}, ${lost}: show the position`}
                     style={{ display: 'inline-flex', gap: 6, alignItems: 'baseline', padding: '2px 8px', borderRadius: 3,
                              border: '1px solid var(--hairline-lit)', whiteSpace: 'nowrap' }}
                     label={<>
                       <span className="mono" style={{ color: 'var(--orchid)', fontWeight: 600 }}>{p.move}</span>
                       <span className="mono" style={{ color: 'var(--text-secondary)', fontSize: '0.74rem' }}>{lost}</span>
                     </>}>
        <MiniBoard fen={p.fen} />
        <span style={{ ...muted, fontSize: '0.75rem', overflowWrap: 'anywhere' }}>{context}</span>
        <span style={{ display: 'flex', gap: 14, fontSize: '0.78rem' }}>
          <Link to={`/games/${p.game_id}?ply=${p.ply}`} style={linkStyle}>Open game</Link>
          <Link to={drillLink([p])} style={linkStyle}>Drill this</Link>
        </span>
      </PopoverButton>
    </li>
  )
}

/** Positions as chips: the first few, and all on request. */
export function Positions({ positions, first = 3 }: { positions: ReportExample[]; first?: number }) {
  const [all, setAll] = useState(false)
  if (positions.length === 0) return null
  const shown = all ? positions : positions.slice(0, first)
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, alignItems: 'center' }}>
      <ul style={{ display: 'contents' }}>
        {shown.map(p => <PositionChip key={`${p.game_id}-${p.ply}`} p={p} />)}
      </ul>
      {positions.length > first && (
        <button type="button" data-disclose aria-expanded={all} onClick={() => setAll(!all)} style={linkButton}>
          {all ? 'Show fewer' : `Show all ${positions.length}`}
        </button>
      )}
    </div>
  )
}
