import { Link, NavLink, Outlet } from 'react-router-dom'
import { SampleBanner } from '../sample/SampleLink'
import { SyncChips, SyncStatusBanner } from './SyncStatusBanner'
import { useLibraryActions } from '../hooks/useLibraryActions'

const SECTIONS = [
  { to: '/',          label: 'Today'    },
  { to: '/progress',  label: 'Progress' },
  { to: '/library',   label: 'Library'  },
  { to: '/play',      label: 'Play'     },
  { to: '/ask',       label: 'Ask Prax' },
  { to: '/insights',  label: 'Insights' },
]

export function Layout() {
  // One instance, so the nav chip and the banner share a sync's state and its failure.
  const lib = useLibraryActions()
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Three zones: brand left, sections centred, settings right behind a hairline.
          Centred by a 1fr · auto · 1fr grid, so the menu stays in the middle
          whatever the two ends hold. On a phone the menu takes its own row. */}
      <nav className="topnav">
        <Link to="/" className="topnav-brand" aria-label="Praxis, home">
          <img src="/Praxis-text-logo-transparent.png" alt="" />
        </Link>
        <div className="topnav-menu">
          {SECTIONS.map(({ to, label }) => (
            <NavLink key={to} to={to} end={to === '/'} className="topnav-link">
              {label}
            </NavLink>
          ))}
        </div>
        <div className="topnav-end">
        {/* Idle library news (D2): ghost chips, before Settings. */}
        <SyncChips lib={lib} />
        {/* Settings is the far end rather than a seventh section: it's visited rarely. */}
        <NavLink to="/settings" aria-label="Settings" className="topnav-settings">
          <span className="topnav-end-label">Settings</span>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
          </svg>
        </NavLink>
        </div>
      </nav>
      <SampleBanner />
      <SyncStatusBanner lib={lib} />
      {/*
        Each page centres itself on its width (components/Page.tsx). Nothing
        reserves room for Prax's card any more: a reserve that came and went
        would shift a centred page sideways each time a card opened. The card
        shows only where the margin beside the page can hold it (PraxStack).
      */}
      <main
        style={{
          flex: 1,
          // A little more above the content now that nav-named pages have no visible title.
          padding: '32px 24px 24px',
          maxWidth: 1280,
          width: '100%',
          margin: '0 auto',
        }}
      >
        <Outlet />
      </main>
    </div>
  )
}
