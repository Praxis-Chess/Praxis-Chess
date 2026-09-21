import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

export function useGameReview(gameId: string | null) {
  return useQuery({
    queryKey: ['review', gameId],
    queryFn: () => api.games.review(gameId!),
    enabled: !!gameId,
  })
}
