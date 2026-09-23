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

export interface ReviewMove {
  /** 1-indexed half-move — the same numbering `MoveError.move_number` uses. */
  ply: number
  /** Full move number, for display. */
  move_number: number
  san: string
  color: 'white' | 'black'
  /** False for the opponent's moves — shown for continuity, never annotated. */
  by_player: boolean
  fen_before: string
  fen_after: string
  /**
   * Null unless the move was flagged. Absent means the move cost less than the
   * inaccuracy threshold — examined and unremarkable, not unexamined.
   */
  mistake: MoveError | null
}

export interface GameReview {
  game_id: string
  player_color: string
  opening_eco: string | null
  opening_name: string | null
  result: string
  accuracy: number | null
  analysis_status: AnalysisStatus
  moves: ReviewMove[]
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

// --- Practice streak ---

/**
 * Days on which real work happened. Named practice_streak, not streak —
 * DashboardStats.form_streak already means consecutive wins/losses.
 */
export interface PracticeStreak {
  current_streak: number
  longest_streak: number
  total_days_practiced: number
  last_practice_date: string | null
  practiced_today: boolean
  /** The SERVER's date, in the user's zone. Use this, never `new Date()`. */
  today: string
  /** Every day ever practised, ascending. Both views are slices of it. */
  practice_days: string[]
}

// --- Play & Improve ---

export type PracticeStatus = 'IN_PROGRESS' | 'FINISHED' | 'ABANDONED'

/**
 * The opponent Praxis built from your history. `personalised` is false when
 * there are not enough analysed games to shape anything — in which case the
 * rationale says so rather than inventing a reason.
 */
export interface OpponentProfile {
  skill_level: number
  target_eco: string | null
  target_opening: string | null
  target_phase: string | null
  target_motif: string | null
  targeted_weakness: string | null
  rationale: string[]
  personalised: boolean
}

export interface PlaySession {
  session_id: string
  fen: string
  san_moves: string
  player_color: 'white' | 'black'
  skill_level: number
  target_eco: string | null
  target_opening: string | null
  status: PracticeStatus
  result: string | null
  end_reason: string | null
  rated: boolean
}

export interface MoveResult {
  fen: string
  /** SAN, or null when the game ended on your move. */
  opponent_move: string | null
  san_moves: string
  status: PracticeStatus
  result: string | null
  end_reason: string | null
  player_to_move: boolean
}

export type ImprovementDirection = 'BETTER' | 'WORSE' | 'UNCHANGED' | 'UNKNOWN'

export interface Comparison {
  label: string
  value: string
  baseline: string
  direction: ImprovementDirection
  note: string
}

export type AnalysisStage = 'SWEEPING' | 'ENRICHING' | 'EXPLAINING'

export interface GameAnalysisProgress {
  stage: AnalysisStage
  done: number
  /** Always a counted total, never an estimate. */
  total: number
}

export interface ImprovementReport {
  practice_game_id: string
  /**
   * The archived Game row — the id `/analysis/{id}` and `/games/{id}` take.
   * Null before the game is archived, and set well before it is measured, so
   * gate the analysis link on `analysed`, never on this being present.
   */
  game_id: string | null
  /**
   * Live stage counts while `analysed` is false; null once finished, and also
   * null while the game waits its turn on the executor. Null means "no counts",
   * which must render as an indeterminate wait — not as zero progress.
   */
  progress: GameAnalysisProgress | null
  analysed: boolean
  rated: boolean
  verdict: string
  comparisons: Comparison[]
  improved: string[]
  still_to_work: string[]
  /** Rated practice games backing the baseline. */
  sample_size: number
  /** False when the sample is too small to claim a direction at all. */
  trend_claimable: boolean
  caveat: string | null
}

/** Whether the sample supports stating a tendency plainly, or only hedging at it. */
export type PatternStrength = 'EMERGING' | 'ESTABLISHED'

export interface PatternOccurrence {
  game_id: string
  /** Null when the finding is about the game, not a move — "never castled". */
  ply: number | null
  label: string
}

export interface PracticePattern {
  id: string
  title: string
  finding: string
  /** Authored constant: what this costs. Not about this player. */
  why: string
  /** Authored constant: the corrective principle, not a move. */
  what_to_do: string
  strength: PatternStrength
  games_affected: number
  games_considered: number
  /**
   * The motif to practise, or null when no position's best move is the lesson.
   * Habit patterns carry null on purpose — see PRACTICE_PATTERNS_PLAN.md §12 Q5.
   */
  drill_motif: string | null
  evidence: PatternOccurrence[]
}

export interface PracticePatternReport {
  games_considered: number
  claimable: boolean
  caveat: string | null
  /** Set when every game in the window is one opening. Qualifies everything below it. */
  opening_caveat: string | null
  patterns: PracticePattern[]
  /** What this report does not look at, and why. */
  not_measured: string[]
}

export interface PracticeGameSummary {
  id: string
  /** The archived Game — what the review route needs. Null until archived. */
  game_id: string | null
  started_at: string
  finished_at: string | null
  status: PracticeStatus
  result: string | null
  skill_level: number
  target_opening: string | null
  rated: boolean
  analysed: boolean
}

// ── Settings ─────────────────────────────────────────────────────────────────
// Snake_case to match the backend records (Jackson SNAKE_CASE). Dates are ISO
// `YYYY-MM-DD` strings.

export interface EngineConfig {
  sweep_move_time_ms: number
  multi_pv_depth: number
  multi_pv_lines: number
  /** Written explanations per game; null means every flagged move. */
  max_explanations: number | null
}

/** A saved, immutable engine configuration — the "ruler" games record. */
export interface EngineVersion extends EngineConfig {
  id: number
  label: string
  created_at: string | null
}

export interface SettingsBounds {
  sweep_min: number
  sweep_max: number
  depth_min: number
  depth_max: number
  lines_min: number
  lines_max: number
  explanations_max: number
  earliest_date: string
  practice_budget_ms: number
}

export interface AppSettingsView {
  sync_from: string | null
  sync_to: string | null
  analysis_from: string | null
  analysis_to: string | null
  library: EngineVersion
  practice: EngineVersion
  bounds: SettingsBounds
}

export interface SettingsUpdate {
  sync_from: string | null
  sync_to: string | null
  analysis_from: string | null
  analysis_to: string | null
  library: EngineConfig | null
  practice: EngineConfig | null
}

export interface SettingsSaved {
  settings: AppSettingsView
  library_version_created: boolean
  practice_version_created: boolean
}

export interface SettingsCount {
  settings_id: number | null
  label: string
  games: number
}

export interface DayCoverage {
  date: string
  synced: number
  analyzed: number
  pending: number
  failed: number
}

export interface MonthCoverage {
  month: string
  synced: number
  analyzed: number
  pending: number
  failed: number
  analyzed_with: SettingsCount[]
  days: DayCoverage[]
}

export interface Coverage {
  first_game: string | null
  last_game: string | null
  synced: number
  analyzed: number
  pending: number
  failed: number
  months: MonthCoverage[]
  practice: { played: number; analyzed: number }
  active_library_settings_id: number | null
  /** Analysed library games span more than one settings version. */
  mixed_settings: boolean
  analyzed_with: SettingsCount[]
  /** Analysed games inside the analysis range not analysed with the active version. */
  outdated_in_range: number
}

export interface KindEstimate {
  /** False until enough games have timings — then no number is shown. */
  measured: boolean
  samples: number
  basis_label: string | null
  per_game_ms: number | null
  explanations_measured: boolean
}

export interface SettingsEstimate {
  library: KindEstimate
  games_in_range: number
  library_total_ms: number | null
  practice: KindEstimate
  practice_budget_ms: number
  practice_within_budget: boolean | null
}


// ── Evidence lab (Phase 2) ───────────────────────────────────────────────────
// One game re-diagnosed from an evidence block the BACKEND rendered, rather
// than from the pipeline's stored explanation. Read-only: nothing here is saved.
//
// snake_case throughout, because Jackson's naming strategy applies to Java
// records too. The first version of these types was camelCase, the mocks agreed
// with it, every test passed — and against the real backend every field was
// undefined.

export interface ThreatProbeResult {
  probed: boolean
  skip_reason: string | null
  move_uci: string | null
  /** Pawns, White's point of view, with the opponent given a free move. */
  score: number | null
}

export interface ExplainedMistake {
  /** A ply index, as everywhere else in the API — odd is White. */
  move_number: number
  played_san: string
  severity: string
  /** Exactly what the model was shown. Displayed so a claim can be checked. */
  block: string
  explanation: string | null
  model_error: string | null
  threat: ThreatProbeResult
  /** Pawns the player would lose by passing. Null when the probe could not run. */
  threat_cost: number | null
  /** The free move was worth at least a pawn — a real threat, not just a move. */
  threatened: boolean
  /** What the refutation itself would have won with a free move. Null when it
      was not even legal before the move (a recapture, say). */
  reply_threat_cost: number | null
  /** The refutation WAS the threat: about as strong as the best free move. */
  threat_is_reply: boolean
  reply_uci: string | null
  tactics: string[]
  visibility_depth: number | null
  visibility: string
  engine_ms: number
  model_ms: number
}

export interface EvidenceReport {
  game_id: string
  player_color: string
  opening: string
  played_at: string
  model: string
  flagged_total: number
  explained: ExplainedMistake[]
  engine_ms_total: number
  model_ms_total: number
  engine_ms_per_mistake: number
  model_ms_per_mistake: number
}

// ── Rule validation (Phase 3) ────────────────────────────────────────────────
// Hand labels for the player's own mistakes, and how the rules score against
// them. snake_case: the backend serialises records through Jackson's naming
// strategy, and the Phase 2 lab shipped camelCase types once already.

export interface BuildResult {
  built: number
  failed: number
  total_built: number
  remaining: number
  millis: number
}

/** A mistake to label. Deliberately carries no rule verdict. */
export interface LabelCard {
  id: string
  fen: string
  player: string
  move_label: string
  played_san: string
  played_uci: string
  best_san: string
  best_uci: string
  severity: string | null
  phase: string
  played_line: string[]
  best_line: string[]
  /** The R3 evidence graph, as text — facts only. */
  evidence: string
  labelled: number
  built: number
  target: number
}

export interface MechanismScore {
  mechanism: string
  true_positives: number
  false_positives: number
  false_negatives: number
  precision: number | null
  precision_ci: [number, number] | null
  recall: number | null
  recall_ci: [number, number] | null
}

export interface RuleReport {
  built: number
  labelled: number
  target: number
  /** Graphs from an older builder: their verdicts predate the current rules. */
  stale: number
  consequence_agreement: number | null
  consequence_ci: [number, number] | null
  single_cause_accuracy: number | null
  single_cause_labelled: number
  mechanisms: MechanismScore[]
  composite_rate: number
  not_concrete_rate: number
  rule_diagnoses_failing_verification: number
  budget: {
    graphs: number
    over_budget: number
    max_items: number
    median_tokens: number
    p95_tokens: number
    max_tokens: number
  }
  disagreements: {
    id: string
    move_label: string
    human_consequence: string
    rule_consequence: string
    human_mechanism: string
    rule_mechanisms: string
    note: string | null
  }[]
}
