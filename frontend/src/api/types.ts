export type AnalysisStatus = 'PENDING' | 'ANALYZING' | 'ANALYZED' | 'FAILED'
export type Severity = 'BLUNDER' | 'MISTAKE' | 'INACCURACY'
export type TacticalMotif = 'FORK' | 'PIN' | 'SKEWER' | 'BACK_RANK' | 'DISCOVERED_ATTACK' | 'HANGING_PIECE' | 'POSITIONAL' | 'OTHER'
export type GamePhase = 'OPENING' | 'MIDDLEGAME' | 'ENDGAME'

export interface GameSummary {
  id: string
  chess_com_id: string
  played_at: string
  time_class: string
  time_control: string
  player_color: string
  result: string
  opening_eco: string | null
  opening_name: string | null
  analysis_status: AnalysisStatus
  player_rating: number
  accuracy: number | null
  mistake_count: number
}

export type AnalysisState = 'EXPLAINED' | 'SKIPPED' | 'LLM_FAILED'

export interface MoveError {
  id: string
  move_number: number
  move_played: string
  better_move: string | null   // UCI format from engine (e.g. "e2e4") or null
  fen_position: string
  severity: Severity
  tactical_motif: TacticalMotif | null
  explanation: string | null
  game_phase: GamePhase | null
  clock_remaining: number | null
  analysis_state: AnalysisState | null  // null for legacy rows before migration
}

export interface SyncStatus {
  state: 'IDLE' | 'SYNCING' | 'ANALYZING'
  games_fetched: number
  games_analyzed: number
  games_pending: number
  last_synced_at: string
}

export interface AnalysisProgress {
  running: boolean
  pattern_generating: boolean
  queued: boolean
  /** Stop requested; ends after the game currently in flight. */
  stopping: boolean
  completed: number
  total: number
  percent_complete: number
  eta_seconds: number
}

export interface RatingPoint {
  date: string
  rating: number
}

export interface OpeningStat {
  eco: string
  name: string | null
  games: number
  wins: number
  win_pct: number
}

export interface RecentGame {
  id: string
  played_at: string | null
  time_class: string | null
  player_color: string
  result: string
  opening_eco: string | null
  opening_name: string | null
  accuracy: number | null
}

export interface TimeControlStat {
  time_class: string
  games: number
  wins: number
  win_pct: number
}

export interface DashboardStats {
  total_games: number
  wins: number
  losses: number
  draws: number
  games_analyzed: number
  current_rating: number
  rating_delta: number
  avg_accuracy: number | null
  best_accuracy: number | null
  blunder_count: number
  opening_stats: OpeningStat[]
  rating_history: RatingPoint[]
  recent_games: RecentGame[]
  // Coach additions
  white_games: number
  white_win_pct: number
  black_games: number
  black_win_pct: number
  form_streak: number
  time_control_stats: TimeControlStat[]
}

export interface Pattern {
  id: string
  games_analyzed: number
  computed_at: string
  mistakes_moves1to10: number
  mistakes_moves11to20: number
  mistakes_moves21to30: number
  mistakes_moves31_plus: number
  mistakes_opening: number
  mistakes_middlegame: number
  mistakes_endgame: number
  motif_frequency: string | null
  opening_accuracy: string | null
  primary_weakness: string | null
  secondary_weakness: string | null
  tertiary_weakness: string | null
  critical_move_range: string | null
  dominant_motif: string | null
  opening_assessment: string | null
}

export interface TrainingPriority {
  focus: string
  action: string
  reason: string
}

export interface TrainingPlanJson {
  priority_1: TrainingPriority
  priority_2: TrainingPriority
  priority_3: TrainingPriority
  openings_to_drill: string[]
  tactical_patterns_to_study: string[]
}

export interface TrainingPlan {
  id: string
  generated_at: string
  based_on_games: number
  plan_json: string
  openings_to_drill: string | null
  tactical_patterns: string | null
}

// --- Insights (practical analytics) ---

export interface OpponentBucket {
  bucket: string
  games: number
  wins: number
  win_pct: number
  avg_accuracy: number | null
}

export interface AccuracyTrendPoint {
  date: string
  accuracy: number
  moving_avg: number
}

export interface TimeBucket {
  label: string
  games: number
  wins: number
  win_pct: number
}

export interface PhaseAccuracy {
  opening: number
  middlegame: number
  endgame: number
}

export interface TimeManagement {
  avg_move_seconds: number | null
  total_blunders: number
  blunders_in_time_pressure: number
  time_trouble_rate: number
}

export interface BlownGame {
  game_id: string
  opening_name: string | null
  max_advantage: number
  result: string
  played_at: string | null
}

export interface Conversion {
  winning_games: number
  converted: number
  conversion_pct: number
  blown_games: BlownGame[]
}

export interface MotifCount {
  motif: string
  count: number
}

export interface Tilt {
  after_win_games: number
  after_win_win_pct: number
  after_loss_games: number
  after_loss_win_pct: number
}

export interface OpeningInsight {
  eco: string
  name: string | null
  games: number
  win_pct: number
  avg_accuracy: number | null
}

export interface Insights {
  opponent_strength: OpponentBucket[]
  accuracy_trend: AccuracyTrendPoint[]
  time_of_day: TimeBucket[]
  day_of_week: TimeBucket[]
  phase_accuracy: PhaseAccuracy
  time_management: TimeManagement
  conversion: Conversion
  missed_tactics: MotifCount[]
  tilt: Tilt
  openings: OpeningInsight[]
}

// --- Drills ---

export interface Drill {
  id: string
  fen: string
  best_move: string
  move_played: string
  severity: Severity
  tactical_motif: TacticalMotif | null
  game_phase: GamePhase | null
  explanation: string | null
  player_color: string
  game_id: string | null
}

// --- Drill sessions (FSRS) ---

export type CardStatus = 'NEW' | 'LEARNING' | 'REVIEW' | 'SUSPENDED'
export type AttemptRating = 'AGAIN' | 'HARD' | 'GOOD' | 'EASY'

export interface Card {
  id: string
  fen_position: string
  /** The mistake move, SAN. */
  move_played: string | null
  /** Engine best move, UCI. */
  better_move: string | null
  severity: Severity | null
  tactical_motif: TacticalMotif | null
  game_phase: GamePhase | null
  player_color: string | null
  explanation: string | null
  status: CardStatus
  interval_days: number
  due_date: string
  review_count: number
  lapse_count: number
  game_id: string | null
}

export interface Session {
  id: string
  cards_total: number
  cards_completed: number
  budget_minutes: number
  completed: boolean
  started_at: string
  completed_at: string | null
}

export interface AttemptRequest {
  card_id: string
  /** UCI, or null when the answer was revealed. */
  move_played: string | null
  correct: boolean
  rating: AttemptRating
  response_ms: number | null
}

// --- Progress ---

export interface DeckSummary {
  total_cards: number
  new_cards: number
  learning_cards: number
  review_cards: number
  suspended_cards: number
}

export interface PhaseBreakdown {
  opening_correct: number
  opening_total: number
  middlegame_correct: number
  middlegame_total: number
  endgame_correct: number
  endgame_total: number
}

export interface DailyStat {
  date: string
  reviewed: number
  correct: number
  again: number
  accuracy: number
  avg_interval_days: number | null
  phases: PhaseBreakdown
}

export interface Progress {
  deck_summary: DeckSummary
  /** Last 30 days, ascending. */
  history: DailyStat[]
}

// --- Today page (LLM insight) ---

export interface TodayEvidence {
  metric: string
  value: string
  sample_size: number
}

export interface TodayInsight {
  title: string
  evidence: TodayEvidence
  action: string
  expected_minutes: number
}
