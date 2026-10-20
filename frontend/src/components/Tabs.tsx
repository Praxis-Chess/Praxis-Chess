import { useRef } from 'react'
import { NavLink } from 'react-router-dom'

export interface TabItem {
  key: string
  label: string
  /** A tab that is its own address (Insights' sections): rendered as a link. */
  to?: string
}

/**
 * The app's one tab bar: an underline under the active tab, pink for the
 * active state only, on a hairline. Two kinds behind one look:
 *
 *  - Link tabs (every item has `to`): each tab is a page with its own address,
 *    so they are links in a labelled group, the current one `aria-current`.
 *    Back and forward work, and a tab can be bookmarked.
 *  - In-page tabs (no `to`): views of one page, as an ARIA tablist, with the
 *    arrow keys, Home and End moving between tabs. Give the matching panel
 *    `id={tabPanelId(idBase, key)}` and `aria-labelledby={tabId(idBase, key)}`.
 */
export function Tabs({ label, items, active, onSelect, idBase }: {
  label: string
  items: TabItem[]
  active: string
  onSelect?: (key: string) => void
  /** Prefix for tab and panel ids (in-page tabs). */
  idBase?: string
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])

  if (items.every(i => i.to)) {
    return (
      <div role="group" aria-label={label} className="tabs">
        {items.map(t => (
          <NavLink key={t.key} to={t.to!} end className="tab" aria-current={active === t.key ? 'page' : undefined}>
            {t.label}
          </NavLink>
        ))}
      </div>
    )
  }

  const base = idBase ?? label.toLowerCase().replace(/\W+/g, '-')
  const move = (from: number, to: number) => {
    const i = (to + items.length) % items.length
    if (i === from) return
    onSelect?.(items[i].key)
    refs.current[i]?.focus()
  }
  return (
    <div role="tablist" aria-label={label} className="tabs">
      {items.map((t, i) => {
        const on = active === t.key
        return (
          <button key={t.key} ref={el => { refs.current[i] = el }} type="button" role="tab" className="tab"
                  id={tabId(base, t.key)} aria-controls={tabPanelId(base, t.key)} aria-selected={on}
                  tabIndex={on ? 0 : -1}
                  onClick={() => onSelect?.(t.key)}
                  onKeyDown={e => {
                    if (e.key === 'ArrowRight') { e.preventDefault(); move(i, i + 1) }
                    else if (e.key === 'ArrowLeft') { e.preventDefault(); move(i, i - 1) }
                    else if (e.key === 'Home') { e.preventDefault(); move(i, 0) }
                    else if (e.key === 'End') { e.preventDefault(); move(i, items.length - 1) }
                  }}>
            {t.label}
          </button>
        )
      })}
    </div>
  )
}

export const tabId = (base: string, key: string) => `${base}-tab-${key || 'default'}`
export const tabPanelId = (base: string, key: string) => `${base}-panel-${key || 'default'}`
