import { NavLink, Outlet } from 'react-router-dom'
import { SyncStatusBanner } from './SyncStatusBanner'

export function Layout() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <nav style={{
        background: 'var(--canvas-deep)',
        borderBottom: '1px solid var(--hairline)',
        padding: '0 24px',
        display: 'flex',
        alignItems: 'center',
        // Was a flat 32. A sixth tab pushed the last one past the right edge on
        // a phone, where it resolved in the DOM but could not be clicked — the
        // nav is fixed-height and did not wrap or scroll. Shrinks with the
        // viewport, and scrolls horizontally rather than truncating if a
        // seventh ever arrives.
        gap: 'clamp(14px, 3.2vw, 32px)',
        height: 52,
        overflowX: 'auto',
        position: 'sticky',
        top: 0,
        zIndex: 100,
      }}>
        <img
          src="/praxis_logo.png"
          alt="Praxis"
          style={{ height: 28, width: 'auto', marginRight: 8, display: 'block' }}
        />
        {[
          { to: '/',          label: 'Today'    },
          { to: '/progress',  label: 'Progress' },
          { to: '/library',   label: 'Library'  },
          { to: '/play',      label: 'Play'     },
          { to: '/ask',       label: 'Ask Prax' },
          { to: '/insights',  label: 'Insights' },
        ].map(({ to, label }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            style={({ isActive }) => ({
              color: isActive ? 'var(--orchid)' : 'var(--text-secondary)',
              fontWeight: isActive ? 600 : 400,
              fontSize: '0.85rem',
              letterSpacing: '-0.01em',
              borderBottom: isActive ? '1px solid var(--orchid)' : '1px solid transparent',
              paddingBottom: 3,
              // A tab must never wrap mid-label or be squeezed to nothing when
              // the row runs out of room; it scrolls instead.
              whiteSpace: 'nowrap',
              flexShrink: 0,
            })}
          >
            {label}
          </NavLink>
        ))}
        {/* Settings is a gear at the far end rather than a seventh tab: it's
            visited rarely, and a seventh label is what overflows the nav. */}
        <NavLink
          to="/settings"
          aria-label="Settings"
          title="Settings"
          style={({ isActive }) => ({
            marginLeft: 'auto',
            display: 'flex',
            alignItems: 'center',
            flexShrink: 0,
            color: isActive ? 'var(--orchid)' : 'var(--text-secondary)',
          })}
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
          </svg>
        </NavLink>
      </nav>
      <SyncStatusBanner />
      {/*
        `--prax-gutter` is set by PraxStack while a Prax card is on screen and
        the viewport is wide enough to give up the room. Reserving the strip
        makes the content REFLOW out of the card's way; previously the card
        simply floated on top of it, and because the card takes pointer events
        it turned the buttons underneath into dead buttons.
      */}
      <main
        style={{
          flex: 1,
          padding: '24px',
          paddingRight: 'calc(24px + var(--prax-gutter, 0px))',
          maxWidth: 1280,
          width: '100%',
          margin: '0 auto',
          transition: 'padding-right 220ms cubic-bezier(0.2, 0.8, 0.3, 1)',
        }}
      >
        <Outlet />
      </main>
    </div>
  )
}
