import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { useHelp } from './report/ReportParts'

const muted: React.CSSProperties = { color: 'var(--text-secondary)', fontSize: '0.78rem', lineHeight: 1.5 }

/**
 * Settings → Player: the Chess.com account this install reads. Saving asks
 * Chess.com whether the account exists; a name already in the library keeps
 * the spelling its games were stored under.
 */
export function PlayerCard() {
  const qc = useQueryClient()
  const { data: player } = useQuery({ queryKey: ['settings-player'], queryFn: api.settings.player })
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => { if (player) setName(player.username) }, [player])
  const [help, helpPanel] = useHelp('usernames',
    'Games are kept per username: switching shows that player’s games, and the others stay stored.')

  // Today's checklist links here as /settings#player.
  useEffect(() => {
    if (player && window.location.hash === '#player') {
      document.getElementById('player')?.scrollIntoView({ block: 'start' })
    }
  }, [player])

  const save = useMutation({
    mutationFn: (u: string) => api.settings.savePlayer(u),
    onSuccess: r => {
      if (!r.ok) { setError(r.error); setNotice(null); return }
      setError(null)
      setName(r.saved.player.username)
      setNotice(r.saved.warning ?? 'Saved. Sync to fetch this player’s games.')
      // Nearly every page is about this player: refetch everything.
      qc.invalidateQueries()
    },
  })

  if (!player) return null
  const unchanged = name.trim() === player.username

  return (
    <section id="player" className="card" aria-labelledby="player-title">
      <h2 id="player-title" style={{ fontSize: '1rem', marginBottom: 10 }}>Player</h2>
      <form onSubmit={e => { e.preventDefault(); if (!unchanged) save.mutate(name) }}
            style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: '0.78rem', flex: '1 1 220px', minWidth: 0 }}>
          Chess.com username
          <input value={name} onChange={e => { setName(e.target.value); setError(null); setNotice(null) }}
                 placeholder="e.g. hikaru" autoComplete="off" spellCheck={false}
                 aria-invalid={!!error} aria-describedby="player-help"
                 style={{ padding: '7px 10px', fontSize: '0.88rem' }} />
        </label>
        <button type="submit" className={player.source === 'none' ? 'solid' : undefined}
                disabled={save.isPending || unchanged || !name.trim()}>
          {save.isPending ? 'Checking…' : 'Save'}
        </button>
      </form>
      {error && <p role="alert" style={{ ...muted, color: 'var(--red)', marginTop: 8 }}>{error}</p>}
      {notice && <p role="status" style={{ ...muted, marginTop: 8 }}>{notice}</p>}
      <p id="player-help" style={{ ...muted, marginTop: 8 }}>
        {player.source === 'config' && <>Currently from <span className="mono">application.yml</span>; saving here takes over.</>}
        {player.source === 'none' && <>Public games only: no account or password.</>}
        {help}
      </p>
      {helpPanel}
    </section>
  )
}
