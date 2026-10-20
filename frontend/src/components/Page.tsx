import type { ReactNode } from 'react'

/**
 * The page frame (POLISH_PLAN.md U1, decisions D1 and D6): every page is centred
 * on one of three widths, declared once.
 *
 * - `reading` (760 px): a thread or a single task (Ask Prax).
 * - `standard` (1200 px): most pages.
 * - `wide`: the whole of `main` (Game Analysis, practice review, the live game).
 *
 * `rail` is the slot for context beside the page's main column: from 1200 px of
 * viewport it is a 12-column grid, 8 columns of content and 4 of rail; narrower,
 * the rail stacks under the content. Pages adopt it from U3.
 */
export type PageWidth = 'reading' | 'standard' | 'wide'

export function Page({ width = 'standard', rail, children }: {
  width?: PageWidth
  rail?: ReactNode
  children: ReactNode
}) {
  return (
    <div className={`page page-${width}`} data-page={width}>
      {rail
        ? (
          <div className="page-grid">
            <div className="page-main">{children}</div>
            <aside className="page-rail" aria-label="Context">{rail}</aside>
          </div>
        )
        : children}
    </div>
  )
}
