// Imported, not referenced by URL. Vite resolves and fingerprints it, so a
// missing or renamed file is a build error rather than a silently broken image
// — and there is exactly one copy, which is what went wrong before: a
// transparent export sat in asset/ while public/ served an older opaque one.
import balancingLoop from '../assets/praxis_balancing_loop.gif'

export function LoadingSpinner({ size = 72, label = 'Loading…' }: { size?: number; label?: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', padding: '40px 0', gap: 12 }}>
      <img
        src={balancingLoop}
        alt={label}
        style={{ width: size, height: size, objectFit: 'contain' }}
      />
      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)', letterSpacing: '0.02em' }}>
        {label}
      </span>
    </div>
  )
}
