import { ChessPositionCard, MoveComparisonCard } from './ChessPositionCard'
import { ChartCard, TableCard } from './StatCards'
import type { PraxArtifact } from './types'

/**
 * artifact.type → component.
 *
 * An UNKNOWN type renders nothing and does not throw. That rule matters more
 * than it looks: a backend that ships a new artifact type must never white-screen
 * a frontend that predates it. The worst outcome of a version mismatch should be
 * a missing diagram, not a broken page.
 */
export function ArtifactView({ artifact }: { artifact: PraxArtifact }) {
  switch (artifact.type) {
    case 'CHESS_POSITION':
      return <ChessPositionCard artifact={artifact} />
    case 'MOVE_COMPARISON':
      return <MoveComparisonCard artifact={artifact} />
    case 'CHART':
      return <ChartCard artifact={artifact} />
    case 'TABLE':
      return <TableCard artifact={artifact} />
    default:
      warnOnce((artifact as { type?: string }).type)
      return null
  }
}

/** A list of artifacts, or nothing at all. */
export function ArtifactList({ artifacts }: { artifacts?: PraxArtifact[] }) {
  if (!artifacts || artifacts.length === 0) return null
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, marginTop: 12 }}>
      {artifacts.map(a => (
        <ArtifactView key={a.id} artifact={a} />
      ))}
    </div>
  )
}

/** Once per unknown type per session — a render loop must not spam the console. */
const warned = new Set<string>()
function warnOnce(type?: string) {
  const key = type ?? 'undefined'
  if (warned.has(key)) return
  warned.add(key)
  console.warn(`[prax] no renderer for artifact type "${key}" — ignoring`)
}
