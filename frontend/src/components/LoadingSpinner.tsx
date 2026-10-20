// Imported, not referenced by URL. Vite resolves and fingerprints it, so a
// missing or renamed file is a build error rather than a silently broken image
// — and there is exactly one copy, which is what went wrong before: a
// transparent export sat in asset/ while public/ served an older opaque one.
import balancingLoop from '../assets/praxis_balancing_loop.gif'
// Its first frame, for reduced motion (U8): the mark holds still and the label says it is working.
import balancingStill from '../assets/praxis_balancing_still.png'

export function LoadingSpinner({ size = 72, label = 'Loading…' }: { size?: number; label?: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', padding: '40px 0', gap: 12 }}>
      <picture>
        <source srcSet={balancingStill} media="(prefers-reduced-motion: reduce)" />
        <img
          src={balancingLoop}
          alt={label}
          style={{ width: size, height: size, objectFit: 'contain' }}
        />
      </picture>
      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)', letterSpacing: '0.02em' }}>
        {label}
      </span>
    </div>
  )
}
