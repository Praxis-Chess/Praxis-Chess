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
        gap: 32,
        height: 52,
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
            })}
          >
            {label}
          </NavLink>
        ))}
      </nav>
      <SyncStatusBanner />
      <main style={{ flex: 1, padding: '24px', maxWidth: 1280, width: '100%', margin: '0 auto' }}>
        <Outlet />
      </main>
    </div>
  )
}
