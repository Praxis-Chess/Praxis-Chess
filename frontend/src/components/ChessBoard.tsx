import { Chessboard } from 'react-chessboard'
import { Chess } from 'chess.js'

interface Arrow {
  from: string
  to: string
  color: string
}

interface Props {
  fen?: string
  playerColor?: string
  arrows?: Arrow[]
}

export function ChessBoard({ fen, playerColor = 'white', arrows }: Props) {
  const safeFen = (() => {
    if (!fen) return undefined
    try {
      new Chess(fen)
      return fen
    } catch {
      return undefined
    }
  })()

  // react-chessboard's Arrow type expects chess Square literals; cast via unknown
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const customArrows: any[] = arrows ? arrows.map(a => [a.from, a.to, a.color]) : []

  return (
    // Fluid up to 360px rather than a fixed 360: with boardWidth omitted,
    // react-chessboard sizes itself to this wrapper via a ResizeObserver. A
    // fixed 360 overflowed its card on a phone, where the column is ~336px.
    <div style={{ width: '100%', maxWidth: 360 }}>
    <Chessboard
      position={safeFen}
      boardOrientation={playerColor === 'black' ? 'black' : 'white'}
      arePiecesDraggable={false}
      customDarkSquareStyle={{ backgroundColor: '#4A4340' }}
      customLightSquareStyle={{ backgroundColor: '#E3DBD1' }}
      customBoardStyle={{ borderRadius: '2px', border: '1px solid rgba(255,255,255,0.08)' }}
      customArrows={customArrows}
      customArrowColor="rgba(226,102,74,0.85)"
    />
    </div>
  )
}
