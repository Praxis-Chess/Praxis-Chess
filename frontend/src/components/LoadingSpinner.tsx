export function LoadingSpinner({ size = 72, label = 'Loading…' }: { size?: number; label?: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', padding: '40px 0', gap: 12 }}>
      <img
        src="/praxis_balancing_loop.gif"
        alt={label}
        style={{ width: size, height: size, objectFit: 'contain' }}
      />
      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)', letterSpacing: '0.02em' }}>
        {label}
      </span>
    </div>
  )
}
