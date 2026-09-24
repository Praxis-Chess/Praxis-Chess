import type {
  AnalysisProgress,
  MistakeWhy,
  AppSettingsView,
  Coverage,
  EngineConfig,
  SettingsEstimate,
  SettingsSaved,
  SettingsUpdate,
  AttemptRequest,
  Card,
  DashboardStats,
  Drill,
  GameReview,
  GameSummary,
  Insights,
  MoveError,
  ImprovementReport,
  MoveResult,
  OpponentProfile,
  Pattern,
  PlaySession,
  PracticeGameSummary,
  PracticePatternReport,
  PracticeStreak,
  Progress,
  RatingPoint,
  Session,
  SyncStatus,
  TodayInsight,
  TrainingPlan,
  EvidenceReport,
  BuildResult,
  LabelCard,
  RuleReport,
} from './types'


const BASE = '/api'

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`API error ${res.status}: ${text}`)
  }
  if (res.status === 204) return null as T
  return res.json() as Promise<T>
}

export const api = {
  sync: {
    trigger: (months = 1) =>
      request<{ message: string; new_games: number }>('/sync', {
        method: 'POST',
        body: JSON.stringify({ months }),
      }),
    forceResync: (months = 3) =>
      request<{ message: string; gamesUpdated: number }>('/sync/force-resync', {
        method: 'POST',
        body: JSON.stringify({ months }),
      }),
    status: () => request<SyncStatus>('/sync/status'),
    /** One Chess.com call per 10 min (server-cached). Returns games played but not yet in DB. */
    newCount: () => request<{ count: number }>('/sync/new-count'),
  },

  games: {
    list: () => request<GameSummary[]>('/games'),
    get: (id: string) => request<GameSummary>(`/games/${id}`),
    /** Every move in order, with the engine's findings attached to the flagged ones. */
    review: (id: string) => request<GameReview>(`/games/${id}/review`),
    reanalyze: (id: string) =>
      request<{ message: string }>(`/games/${id}/analyze`, { method: 'POST' }),
  },

  dashboard: {
    stats: () => request<DashboardStats>('/dashboard/stats'),
    ratingHistory: () => request<RatingPoint[]>('/dashboard/rating-history'),
  },

  analysis: {
    moveErrors: (gameId: string) => request<MoveError[]>(`/analysis/${gameId}`),
    analyzePending: () =>
      request<{ message: string; games_queued: number }>('/analysis/analyze-pending', { method: 'POST' }),
    /**
     * Re-analyse the games inside the analysis range (every game when no range
     * is set). `outdatedOnly` limits it to games not yet analysed with the
     * current engine settings.
     */
    reanalyzeAll: (outdatedOnly = false) =>
      request<{ message: string; games_queued: number }>(
        `/analysis/reanalyze${outdatedOnly ? '?outdated_only=true' : ''}`, { method: 'POST' }),
    progress: () => request<AnalysisProgress>('/analysis/progress'),
    /** Honoured between games — the run ends after the one in flight. */
    stop: () =>
      request<{ stopping: boolean; completed: number; total: number }>('/analysis/stop', {
        method: 'POST',
      }),
  },

  insights: {
    get: () => request<Insights>('/insights'),
  },

  drills: {
    list: (limit = 20) => request<Drill[]>(`/drills?limit=${limit}`),
  },

  patterns: {
    latest: () => request<Pattern | null>('/patterns'),
  },

  trainingPlan: {
    latest: () => request<TrainingPlan | null>('/training-plan'),
    generate: () =>
      request<TrainingPlan>('/training-plan/generate', { method: 'POST' }),
  },

  today: {
    insight: () => request<TodayInsight>('/today'),
  },

  sessions: {
    start: () => request<Session>('/sessions', { method: 'POST' }),
    get: (id: string) => request<Session>(`/sessions/${id}`),
    nextCard: (id: string) => request<Card | null>(`/sessions/${id}/next`),
    recordAttempt: (id: string, req: AttemptRequest) =>
      request<Session>(`/sessions/${id}/attempt`, {
        method: 'POST',
        body: JSON.stringify(req),
      }),
  },

  progress: {
    get: () => request<Progress>('/progress'),
  },

  practice: {
    streak: () => request<PracticeStreak>('/practice/streak'),
  },

  play: {
    status: () => request<{ available: boolean }>('/play/status'),
    preview: (skill?: number) =>
      request<OpponentProfile>(`/play/preview${skill != null ? `?skill=${skill}` : ''}`),
    start: (body: { color?: string; skill?: number }) =>
      request<PlaySession>('/play/session', { method: 'POST', body: JSON.stringify(body) }),
    get: (id: string) => request<PlaySession>(`/play/session/${id}`),
    move: (id: string, uci: string) =>
      request<MoveResult>(`/play/session/${id}/move`, {
        method: 'POST', body: JSON.stringify({ uci }),
      }),
    undo: (id: string) => request<MoveResult>(`/play/session/${id}/undo`, { method: 'POST' }),
    resign: (id: string) => request<MoveResult>(`/play/session/${id}/resign`, { method: 'POST' }),
    report: (id: string) => request<ImprovementReport>(`/play/report/${id}`),
    /** Recurring tendencies across recent practice games. Always 200. */
    patterns: () => request<PracticePatternReport>('/play/patterns'),
    history: () => request<PracticeGameSummary[]>('/play/history'),
  },

  settings: {
    get: () => request<AppSettingsView>('/settings'),
    /**
     * A 400 carries a message per invalid field. It's RETURNED, not thrown, so
     * the form can put each message under its own input instead of showing one
     * opaque "API error 400".
     */
    save: async (
      body: SettingsUpdate,
    ): Promise<{ ok: true; saved: SettingsSaved } | { ok: false; errors: Record<string, string> }> => {
      const res = await fetch(`${BASE}/settings`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (res.status === 400) {
        const j = await res.json().catch(() => ({}))
        return { ok: false, errors: j.errors ?? { body: 'The settings were rejected.' } }
      }
      if (!res.ok) throw new Error(`API error ${res.status}: ${await res.text()}`)
      return { ok: true, saved: (await res.json()) as SettingsSaved }
    },
    coverage: () => request<Coverage>('/settings/coverage'),
    /** Measured time estimate for a PROPOSED configuration. Changes nothing. */
    estimate: (body: { library: EngineConfig | null; practice: EngineConfig | null }) =>
      request<SettingsEstimate>('/settings/estimate', { method: 'POST', body: JSON.stringify(body) }),
  },

  diagnosis: {
    /** Build evidence graphs for up to `limit` more mistakes. Slow: one engine run each. */
    build: (limit = 25) => request<BuildResult>(`/diagnosis/build?limit=${limit}`, { method: 'POST' }),
    /** Rebuild graphs from an older builder in place; hand labels are kept. */
    rebuild: (limit = 100) => request<BuildResult>(`/diagnosis/rebuild?limit=${limit}`, { method: 'POST' }),
    /** Null when every built mistake has been labelled (the server answers 204). */
    next: () => request<LabelCard | null>('/diagnosis/next'),
    label: (id: string, body: { consequence: string; mechanism: string; note: string | null }) =>
      request<{ saved: boolean }>(`/diagnosis/label/${id}`, { method: 'POST', body: JSON.stringify(body) }),
    report: () => request<RuleReport>('/diagnosis/report'),
    /** Why one mistake was a mistake. Built on first view (about a second), stored after. */
    why: (gameId: string, ply: number) => request<MistakeWhy>(`/diagnosis/why/${gameId}/${ply}`),
  },

  evidence: {
    /**
     * Re-diagnose one game from backend-rendered evidence. Slow by nature:
     * it runs a fresh engine search per mistake, which is the cost being
     * measured.
     */
    forGame: (gameId: string, limit = 3, model = 'praxis-phase1') =>
      request<EvidenceReport>(
        `/evidence/${gameId}?limit=${limit}&model=${encodeURIComponent(model)}`,
      ),
  },
}
