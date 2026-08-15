import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { TrainingPlanJson, TrainingPriority } from '../api/types'
import { LoadingSpinner } from '../components/LoadingSpinner'

const MISSION_COLORS = ['var(--loss)', 'var(--warn)', 'var(--orchid)']

function MissionCard({
  num, priority, done, onToggle,
}: {
  num: number
  priority: TrainingPriority
  done: boolean
  onToggle: () => void
}) {
  const color = MISSION_COLORS[num - 1] ?? 'var(--orchid)'
  const rank  = String(num).padStart(2, '0')

  return (
    <div
      className="card"
      style={{
        opacity: done ? 0.55 : 1,
        transition: 'opacity 0.2s',
        borderLeft: `3px solid ${color}`,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
        {/* Checkbox */}
        <button
          onClick={onToggle}
          style={{
            width: 22, height: 22, borderRadius: 2, flexShrink: 0,
            border: `1px solid ${done ? color : 'var(--hairline)'}`,
            background: done ? color : 'transparent',
            color: 'var(--canvas)', cursor: 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: '0.75rem', fontWeight: 700, marginTop: 2,
          }}
        >
          {done ? '✓' : ''}
        </button>

        <div style={{ flex: 1 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
            <span className="stat-value" style={{ fontSize: '0.72rem', color, letterSpacing: '0.08em' }}>{rank}</span>
            <h3 style={{
              fontSize: '0.95rem', fontWeight: 600,
              textDecoration: done ? 'line-through' : 'none',
              color: done ? 'var(--text-muted)' : 'var(--text)',
            }}>
              {priority.focus}
            </h3>
            <span style={{
              marginLeft: 'auto', fontSize: '0.65rem', fontWeight: 600,
              textTransform: 'uppercase', letterSpacing: '0.05em',
              color, padding: '2px 8px', border: `1px solid ${color}`,
              borderRadius: 10,
            }}>
              Priority {num}
            </span>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <div style={{
              background: 'var(--surface-2)', borderRadius: 6, padding: '8px 12px',
            }}>
              <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 4 }}>
                Action
              </div>
              <p style={{ fontSize: '0.82rem', color: 'var(--text)', lineHeight: 1.5 }}>
                {priority.action}
              </p>
            </div>
            <div style={{
              background: 'var(--surface-2)', borderRadius: 6, padding: '8px 12px',
            }}>
              <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 4 }}>
                Why
              </div>
              <p style={{ fontSize: '0.82rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>
                {priority.reason}
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

export function TrainingPlan() {
  const queryClient = useQueryClient()
  const [doneMissions, setDoneMissions] = useState<Set<number>>(new Set())

  const { data: plan, isLoading } = useQuery({
    queryKey: ['training-plan'],
    queryFn: api.trainingPlan.latest,
  })

  const generateMutation = useMutation({
    mutationFn: api.trainingPlan.generate,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['training-plan'] })
      setDoneMissions(new Set())
    },
  })

  const parsed: TrainingPlanJson | null = (() => {
    if (!plan?.plan_json) return null
    try { return JSON.parse(plan.plan_json) } catch { return null }
  })()

  const openings: string[] = (() => {
    if (!plan?.openings_to_drill) return []
    try { return JSON.parse(plan.openings_to_drill) } catch { return [] }
  })()

  const tactics: string[] = (() => {
    if (!plan?.tactical_patterns) return []
    try { return JSON.parse(plan.tactical_patterns) } catch { return [] }
  })()

  const toggleMission = (n: number) => {
    setDoneMissions(prev => {
      const next = new Set(prev)
      if (next.has(n)) next.delete(n)
      else next.add(n)
      return next
    })
  }

  const completedCount = doneMissions.size
  const totalMissions  = parsed ? 3 : 0

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        <div>
          <h1 style={{ fontSize: '1.3rem', fontWeight: 700 }}>Training Plan</h1>
          {plan && (
            <p style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: 2 }}>
              Generated {new Date(plan.generated_at).toLocaleString()} · {plan.based_on_games} games
            </p>
          )}
        </div>

        {/* Mission progress */}
        {totalMissions > 0 && (
          <div style={{ marginLeft: 16, display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{ height: 8, width: 120, background: 'var(--surface-2)', borderRadius: 4, overflow: 'hidden' }}>
              <div style={{
                height: '100%',
                width: `${(completedCount / totalMissions) * 100}%`,
                background: completedCount === totalMissions ? 'var(--green)' : 'var(--accent)',
                borderRadius: 4, transition: 'width 0.3s',
              }} />
            </div>
            <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
              {completedCount}/{totalMissions} done
            </span>
          </div>
        )}

        <button
          onClick={() => generateMutation.mutate()}
          disabled={generateMutation.isPending}
          style={{ marginLeft: 'auto' }}
        >
          {generateMutation.isPending ? 'Generating...' : 'Regenerate Plan'}
        </button>
      </div>

      {isLoading && <LoadingSpinner label="Loading training plan…" />}

      {!isLoading && !plan && (
        <div className="card">
          <p style={{ color: 'var(--text-muted)', marginBottom: 12 }}>
            No training plan yet. Analyze your games first, then generate a plan.
          </p>
          <button onClick={() => generateMutation.mutate()} disabled={generateMutation.isPending}>
            {generateMutation.isPending ? 'Generating...' : 'Generate First Plan'}
          </button>
          {generateMutation.isError && (
            <p style={{ color: 'var(--red)', marginTop: 10, fontSize: '0.82rem' }}>
              {(generateMutation.error as Error).message}
            </p>
          )}
        </div>
      )}

      {plan && parsed && (
        <>
          {/* Mission cards */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {[1, 2, 3].map((n) => {
              const priority = parsed[`priority_${n}` as keyof TrainingPlanJson] as TrainingPriority
              return priority ? (
                <MissionCard
                  key={n}
                  num={n}
                  priority={priority}
                  done={doneMissions.has(n)}
                  onToggle={() => toggleMission(n)}
                />
              ) : null
            })}
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
            {openings.length > 0 && (
              <div className="card">
                <h2 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: 12 }}>
                  Openings to Drill
                </h2>
                <ul style={{ listStyle: 'none' }}>
                  {openings.map((o, i) => (
                    <li key={i} style={{
                      fontSize: '0.85rem', padding: '7px 0',
                      borderBottom: '1px solid var(--border)',
                      display: 'flex', alignItems: 'center', gap: 8,
                    }}>
                      <span style={{ color: 'var(--accent)', fontSize: '0.7rem' }}>▶</span>
                      {o}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {tactics.length > 0 && (
              <div className="card">
                <h2 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: 12 }}>
                  Tactical Patterns to Study
                </h2>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                  {tactics.map((t, i) => (
                    <span key={i} className="badge" style={{
                      background: 'var(--surface-2)', color: 'var(--text)',
                      border: '1px solid var(--border)', fontSize: '0.78rem', padding: '5px 12px',
                      borderRadius: 16,
                    }}>
                      {t}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  )
}
