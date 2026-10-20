import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { StaticBoard } from './StaticBoard'
import { SampleLink } from '../sample/SampleLink'
import { sampleAvailable, sampleOn } from '../sample/sampleMode'

/** A position to show before there is any of the player's own: the Blackburne Shilling Gambit, where 4.Nxe5? loses to Qg5. */
const SAMPLE_FEN = 'r1bqkbnr/pppp1ppp/8/4p3/2BnP3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4'

/**
 * An empty screen that teaches (U7): what this screen will show, what it is
 * built from (the player's own figures, never invented), the one action that
 * moves it on, and the positions it will be made of. With no analysed games
 * there are no positions yet, so one sample position stands in, labelled.
 * Words are few on purpose: these states carry the reading budget too.
 */
export function TeachingEmpty({ title, promise, note, action, label, compact, link }: {
  title: string
  /** What the screen will show, in one sentence of at most 15 words. */
  promise?: string
  /** Why it is empty now, in a few words: "No report yet", "Made by an older version". */
  note: string
  action?: React.ReactNode
  /** The landmark's name; defaults to the title. */
  label?: string
  /** One line of figures instead of the list, and a smaller board: for a screen whose own controls come first. */
  compact?: boolean
  /** Where the player's games are, for the compact line. */
  link?: { to: string; text: string }
}) {
  const stats = useQuery({ queryKey: ['dashboard-stats'], queryFn: api.dashboard.stats })
  const drills = useQuery({ queryKey: ['drills', 'teaching'], queryFn: () => api.drills.list(4) })
  const s = stats.data
  const positions = (drills.data ?? []).slice(0, compact ? 2 : 4)
  const synced = s?.total_games ?? 0
  const analysed = s?.games_analyzed ?? 0
  const board = compact ? 96 : 120

  return (
    <section aria-label={label ?? title} className="card teaching"
             style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto', gap: 28, alignItems: 'start' }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
        <h3 style={{ fontSize: 'var(--fs-section)', fontWeight: 700, margin: 0 }}>{title}</h3>
        {promise && <p style={{ margin: 0, color: 'var(--text-secondary)', fontSize: '0.88rem' }}>{promise}</p>}
        {compact ? (
          <p style={{ margin: 0, fontSize: '0.82rem', color: 'var(--text-secondary)', display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'baseline' }}>
            <span className="mono">{synced} synced · {analysed} analysed</span>
            {link && <Link to={link.to} style={{ fontSize: '0.78rem' }}>{link.text}</Link>}
          </p>
        ) : (
          <ul aria-label="Built from" style={{ listStyle: 'none', margin: '4px 0 0', padding: 0, display: 'flex', flexDirection: 'column', gap: 6, fontSize: '0.82rem' }}>
            <Step done={synced > 0} text={synced > 0 ? `${synced} games synced` : 'Games synced'}
                  fix={synced > 0 ? undefined : { to: '/settings', text: 'Settings' }} />
            <Step done={analysed > 0} text={`${analysed} analysed`} />
            <Step done={false} text={note} />
          </ul>
        )}
        {action && <div style={{ marginTop: 4 }}>{action}</div>}
      </div>

      <figure style={{ margin: 0, display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'flex-start' }}>
        {positions.length > 0 ? (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: `repeat(2, ${board}px)`, gap: 8 }}>
              {positions.map(p => <StaticBoard key={p.id} fen={p.fen} size={board} label={`${p.move_played}, from your games`} />)}
            </div>
            <figcaption style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>From your games</figcaption>
          </>
        ) : (
          <>
            <StaticBoard fen={SAMPLE_FEN} size={compact ? 160 : 248} label="A sample position" />
            <figcaption style={{ fontSize: '0.72rem', color: 'var(--text-muted)', display: 'flex', gap: 10 }}>
              {/* The compact card names the sample once, in its link, when there is one. */}
              {!(compact && sampleAvailable && !sampleOn()) && <span>Sample</span>}
              <SampleLink label={compact ? 'Try the sample →' : undefined} />
            </figcaption>
          </>
        )}
      </figure>
    </section>
  )
}

function Step({ done, text, fix }: { done: boolean; text: string; fix?: { to: string; text: string } }) {
  return (
    <li style={{ display: 'flex', gap: 8, alignItems: 'baseline' }}>
      <span aria-hidden="true" style={{ width: 14, color: done ? 'var(--green)' : 'var(--text-tertiary)' }}>{done ? '✓' : '–'}</span>
      <span style={{ color: done ? 'var(--text-primary)' : 'var(--text-secondary)' }}>{text}</span>
      {fix && <Link to={fix.to} style={{ fontSize: '0.75rem' }}>{fix.text} →</Link>}
    </li>
  )
}
