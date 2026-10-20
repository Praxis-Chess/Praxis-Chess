import type { ComponentProps } from 'react'
import { Chessboard } from 'react-chessboard'

/**
 * Every chess board in the app, in one theme (POLISH_PLAN.md U1 item 4, N5):
 * bone and graphite squares (`--board-light`, `--board-dark`), a hairline edge,
 * a 2px radius. Callers pass everything else (position, orientation, arrows,
 * square highlights, size); the theme is applied last so no board drifts.
 * `data-board` marks the root for the density harness.
 */
type Props = Omit<ComponentProps<typeof Chessboard>, 'customDarkSquareStyle' | 'customLightSquareStyle' | 'customBoardStyle'>

export function ThemedBoard(props: Props) {
  return (
    <div data-board="">
      <Chessboard
        {...props}
        customDarkSquareStyle={{ backgroundColor: 'var(--board-dark)' }}
        customLightSquareStyle={{ backgroundColor: 'var(--board-light)' }}
        customBoardStyle={{ borderRadius: '2px', border: '1px solid rgba(255,255,255,0.08)' }}
      />
    </div>
  )
}
