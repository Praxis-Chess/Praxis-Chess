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
    <Chessboard
      position={safeFen}
      boardOrientation={playerColor === 'black' ? 'black' : 'white'}
      arePiecesDraggable={false}
      customDarkSquareStyle={{ backgroundColor: '#4A4340' }}
      customLightSquareStyle={{ backgroundColor: '#E3DBD1' }}
      customBoardStyle={{ borderRadius: '2px', border: '1px solid rgba(255,255,255,0.08)' }}
      boardWidth={360}
      customArrows={customArrows}
      customArrowColor="rgba(226,102,74,0.85)"
    />
  )
}
