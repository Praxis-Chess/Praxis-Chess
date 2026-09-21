import type { ChartArtifact, TableArtifact } from './types'

/**
 * A count per category, as bars.
 *
 * Hand-drawn with divs rather than pulled through Recharts: these are six
 * horizontal bars with a label and a number, and a charting library would bring
 * an axis system, a tooltip layer and a responsive container to draw them. The
 * heavier tool earns its place on the Insights page, where the series are real
 * time series.
 */
export function ChartCard({ artifact }: { artifact: ChartArtifact }) {
  // Scaled against the largest bar, not against a round number: the finding is
  // the RATIO between categories, and a fixed axis flattens it.
  const max = Math.max(...artifact.bars.map(b => b.value), 1)

  return (
    <figure style={{ margin: 0 }}>
      <Caption>{artifact.title}</Caption>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        {artifact.bars.map(bar => (
          <div key={bar.label}>
            <div style={{
              display: 'flex', justifyContent: 'space-between', gap: 10,
              fontSize: '0.72rem', marginBottom: 3,
            }}>
              <span style={{ color: 'var(--text-secondary, #B4AEBE)' }}>{bar.label}</span>
              <span style={{
                color: 'var(--text-tertiary, #625C6D)',
                fontVariantNumeric: 'tabular-nums',
              }}>
                {bar.value} {artifact.unit}
              </span>
            </div>
            <div style={{
              height: 6, borderRadius: 3,
              background: 'var(--surface-2, #1B1920)', overflow: 'hidden',
            }}>
              <div style={{
                width: `${(bar.value / max) * 100}%`,
                height: '100%',
                background: 'var(--orchid, #E7A6D6)',
                borderRadius: 3,
              }} />
            </div>
          </div>
        ))}
      </div>
      {artifact.caption && <Note>{artifact.caption}</Note>}
    </figure>
  )
}

/**
 * Rows to scan.
 *
 * Values arrive pre-formatted from the backend, which knows a win rate is one
 * decimal and a game count is an integer. Re-deciding that here is how
 * "52.4%" becomes "52.400000000000006%".
 */
export function TableCard({ artifact }: { artifact: TableArtifact }) {
  const alignOf = (i: number) =>
    (artifact.align?.[i] === 'RIGHT' ? 'right' : 'left') as 'left' | 'right'

  return (
    <figure style={{ margin: 0 }}>
      <Caption>{artifact.title}</Caption>
      {/* Wide tables scroll inside their own box; the page must never scroll
          sideways because one column was long. */}
      <div style={{ overflowX: 'auto' }}>
        <table style={{
          borderCollapse: 'collapse', width: '100%',
          fontSize: '0.74rem', fontVariantNumeric: 'tabular-nums',
        }}>
          <thead>
            <tr>
              {artifact.columns.map((c, i) => (
                <th key={c} scope="col" style={{
                  textAlign: alignOf(i), padding: '4px 8px 6px 0',
                  borderBottom: '1px solid var(--hairline, #26232B)',
                  color: 'var(--text-tertiary, #625C6D)', fontWeight: 500,
                  whiteSpace: 'nowrap',
                }}>
                  {c}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {artifact.rows.map((row, r) => (
              <tr key={r}>
                {row.map((cell, i) => (
                  <td key={i} style={{
                    textAlign: alignOf(i), padding: '5px 8px 5px 0',
                    color: i === 0
                      ? 'var(--text, #EDEAF0)'
                      : 'var(--text-secondary, #B4AEBE)',
                    whiteSpace: 'nowrap',
                  }}>
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {artifact.caption && <Note>{artifact.caption}</Note>}
    </figure>
  )
}

function Caption({ children }: { children: React.ReactNode }) {
  return (
    <figcaption style={{
      fontSize: '0.6rem', letterSpacing: '0.08em', textTransform: 'uppercase',
      color: 'var(--text-tertiary, #625C6D)', marginBottom: 7,
    }}>
      {children}
    </figcaption>
  )
}

function Note({ children }: { children: React.ReactNode }) {
  return (
    <p style={{
      margin: '7px 0 0', fontSize: '0.7rem', lineHeight: 1.4,
      color: 'var(--text-secondary, #B4AEBE)',
    }}>
      {children}
    </p>
  )
}
