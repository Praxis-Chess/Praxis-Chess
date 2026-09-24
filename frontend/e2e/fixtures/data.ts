/**
 * Fixture data for the mocked suite.
 *
 * Every export is typed with the application's OWN interface, imported from
 * src/api/types.ts. That is deliberate and is the main thing keeping this file
 * honest: if a field is renamed, added as required, or changes type, `npm run
 * test:e2e:types` fails here rather than the suite passing against a shape the
 * server stopped sending months ago.
 *
 * Values are plausible but synthetic. No real Chess.com username, no real game
 * ids — tests must not depend on the operator's personal data, and this file is
 * committed.
 */
import type {
  AnalysisProgress,
  MistakeWhy,
  LabelCard,
  RuleReport,
  EvidenceReport,
  AppSettingsView,
  Coverage,
  SettingsEstimate,
  GameAnalysisProgress,
  DashboardStats,
  GameReview,
  GameSummary,
  ImprovementReport,
  Insights,
  MoveResult,
  OpponentProfile,
  PracticeGameSummary,
  PracticePatternReport,
  PlaySession,
  PracticeStreak,
  Progress,
  SyncStatus,
  TodayInsight,
} from '../../src/api/types'

export const PLAYER = 'testplayer'

/** Fixed so date maths is reproducible. The server supplies `today`, never the browser. */
export const TODAY = '2026-08-21'

export const SESSION_ID = '3f2a91c4-6b5d-4e18-9a77-2c1e8d4b0f36'

/**
 * The archived Game the practice session becomes. A distinct id from SESSION_ID
 * on purpose — they are different rows, and a fixture that reused one id would
 * hide a client sending the practice id to an endpoint that wants the game id.
 */
export const PRACTICE_GAME_ID = 'b81c50e7-4d29-4a63-8f14-7e05a2c9d3b8'

export const startFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'

export const todayInsight: TodayInsight = {
  title: 'You lose material in the middlegame more than anywhere else',
  evidence: { metric: 'Blunders in middlegame', value: '97 of 154', sample_size: 101 },
  action: 'Drill 8 tactical positions from your own games',
  expected_minutes: 12,
}

/** Five consecutive days ending today, plus an older island to exercise gaps. */
export const practiceStreak: PracticeStreak = {
  current_streak: 5,
  longest_streak: 11,
  total_days_practiced: 34,
  last_practice_date: TODAY,
  practiced_today: true,
  today: TODAY,
  practice_days: [
    '2026-07-28', '2026-07-29', '2026-07-30',
    '2026-08-17', '2026-08-18', '2026-08-19', '2026-08-20', TODAY,
  ],
}

export const syncStatus: SyncStatus = {
  state: 'IDLE',
  games_fetched: 101,
  games_analyzed: 101,
  games_pending: 0,
  last_synced_at: '2026-08-21T07:36:48Z',
}

export const analysisIdle: AnalysisProgress = {
  running: false,
  pattern_generating: false,
  queued: false,
  stopping: false,
  completed: 0,
  total: 0,
  percent_complete: 0,
  eta_seconds: 0,
}

/** A run in flight — used to assert Prax reacts and the banner reports progress. */
export const analysisRunning: AnalysisProgress = {
  running: true,
  pattern_generating: false,
  queued: false,
  stopping: false,
  completed: 47,
  total: 101,
  percent_complete: 46.5,
  eta_seconds: 320,
}

export const games: GameSummary[] = [
  {
    id: 'a1000000-0000-4000-8000-000000000001',
    chess_com_id: '1000000001',
    played_at: '2026-08-20T18:12:00Z',
    time_class: 'blitz',
    time_control: '300',
    player_color: 'white',
    result: 'win',
    opening_eco: 'A01',
    opening_name: 'Nimzovich-Larsen Attack',
    analysis_status: 'ANALYZED',
    player_rating: 1284,
    accuracy: 71.4,
    mistake_count: 3,
  },
  {
    id: 'a1000000-0000-4000-8000-000000000002',
    chess_com_id: '1000000002',
    played_at: '2026-08-20T17:44:00Z',
    time_class: 'blitz',
    time_control: '300',
    player_color: 'black',
    result: 'loss',
    opening_eco: 'B20',
    opening_name: 'Sicilian Defense',
    analysis_status: 'ANALYZED',
    player_rating: 1271,
    accuracy: 58.9,
    mistake_count: 7,
  },
  {
    id: 'a1000000-0000-4000-8000-000000000003',
    chess_com_id: '1000000003',
    played_at: '2026-08-19T21:02:00Z',
    time_class: 'rapid',
    time_control: '600',
    player_color: 'white',
    result: 'draw',
    opening_eco: 'C50',
    opening_name: 'Italian Game',
    analysis_status: 'PENDING',
    player_rating: 1279,
    accuracy: null,
    mistake_count: 0,
  },
]

export const dashboardStats: DashboardStats = {
  total_games: 101,
  wins: 48,
  losses: 44,
  draws: 9,
  games_analyzed: 101,
  current_rating: 1284,
  rating_delta: 13,
  avg_accuracy: 68.2,
  best_accuracy: 91.3,
  blunder_count: 154,
  opening_stats: [
    { eco: 'A01', name: 'Nimzovich-Larsen Attack', games: 12, wins: 4, win_pct: 33.3 },
    { eco: 'B20', name: 'Sicilian Defense', games: 21, wins: 11, win_pct: 52.4 },
  ],
  rating_history: [
    { date: '2026-08-14', rating: 1252 },
    { date: '2026-08-17', rating: 1266 },
    { date: '2026-08-20', rating: 1284 },
  ],
  recent_games: [],
  white_games: 52,
  white_win_pct: 51.9,
  black_games: 49,
  black_win_pct: 44.9,
  form_streak: 2,
  time_control_stats: [
    { time_class: 'blitz', games: 74, wins: 35, win_pct: 47.3 },
    { time_class: 'rapid', games: 27, wins: 13, win_pct: 48.1 },
  ],
}

export const insights: Insights = {
  opponent_strength: [
    { bucket: 'Below 1200', games: 24, wins: 16, win_pct: 66.7, avg_accuracy: 72.1 },
    { bucket: '1200-1400', games: 58, wins: 26, win_pct: 44.8, avg_accuracy: 67.4 },
    { bucket: 'Above 1400', games: 19, wins: 6, win_pct: 31.6, avg_accuracy: 63.0 },
  ],
  accuracy_trend: [
    { date: '2026-08-10', accuracy: 63.1, moving_avg: 64.0 },
    { date: '2026-08-14', accuracy: 66.8, moving_avg: 65.2 },
    { date: '2026-08-17', accuracy: 70.2, moving_avg: 66.9 },
    { date: '2026-08-20', accuracy: 71.4, moving_avg: 68.2 },
  ],
  time_of_day: [
    { label: 'Morning', games: 18, wins: 10, win_pct: 55.6 },
    { label: 'Evening', games: 61, wins: 27, win_pct: 44.3 },
  ],
  day_of_week: [
    { label: 'Mon', games: 14, wins: 7, win_pct: 50.0 },
    { label: 'Sat', games: 22, wins: 9, win_pct: 40.9 },
  ],
  phase_accuracy: { opening: 74.8, middlegame: 64.1, endgame: 69.7 },
  time_management: {
    avg_move_seconds: 4.2,
    total_blunders: 154,
    blunders_in_time_pressure: 21,
    time_trouble_rate: 13.6,
  },
  conversion: {
    winning_games: 41,
    converted: 29,
    conversion_pct: 70.7,
    blown_games: [
      {
        game_id: 'a1000000-0000-4000-8000-000000000002',
        opening_name: 'Sicilian Defense',
        max_advantage: 5.3,
        result: 'loss',
        played_at: '2026-08-20T17:44:00Z',
      },
    ],
  },
  missed_tactics: [
    { motif: 'POSITIONAL', count: 154 },
    { motif: 'HANGING_PIECE', count: 63 },
    { motif: 'FORK', count: 28 },
  ],
  tilt: {
    after_win_games: 44,
    after_win_win_pct: 52.3,
    after_loss_games: 41,
    after_loss_win_pct: 39.0,
  },
  openings: [
    { eco: 'A01', name: 'Nimzovich-Larsen Attack', games: 12, win_pct: 33.3, avg_accuracy: 49.2 },
    { eco: 'B20', name: 'Sicilian Defense', games: 21, win_pct: 52.4, avg_accuracy: 68.8 },
  ],
}

export const progress: Progress = {
  deck_summary: {
    total_cards: 154,
    new_cards: 38,
    learning_cards: 12,
    review_cards: 96,
    suspended_cards: 8,
  },
  history: [
    {
      date: '2026-08-19',
      reviewed: 14, correct: 9, again: 5, accuracy: 64.3, avg_interval_days: 3.1,
      phases: {
        opening_correct: 3, opening_total: 4,
        middlegame_correct: 4, middlegame_total: 8,
        endgame_correct: 2, endgame_total: 2,
      },
    },
    {
      date: '2026-08-20',
      reviewed: 20, correct: 15, again: 5, accuracy: 75.0, avg_interval_days: 3.4,
      phases: {
        opening_correct: 5, opening_total: 6,
        middlegame_correct: 7, middlegame_total: 10,
        endgame_correct: 3, endgame_total: 4,
      },
    },
  ],
}

export const opponentProfile: OpponentProfile = {
  skill_level: 9,
  target_eco: 'A01',
  target_opening: 'Nimzovich-Larsen Attack',
  target_phase: 'MIDDLEGAME',
  target_motif: 'POSITIONAL',
  targeted_weakness: 'opening:A01',
  rationale: [
    'You score 49% accuracy in the Nimzovich-Larsen Attack across 12 games — your weakest opening with enough games to judge. This opponent will steer toward it.',
    'Most of your blunders land in the middlegame (97 of them).',
    'Your most frequent mistake pattern is Positional, 154 times.',
    'Strength is set to 9 of 20, calibrated to your 68% average accuracy — a game you can lose, not one you cannot win.',
  ],
  personalised: true,
}

/**
 * Snake_case on purpose, and this is the point of the fixture.
 *
 * The server returned camelCase `sessionId` for a while, because Jackson's
 * SNAKE_CASE strategy does not apply to Map keys. The frontend read
 * `session_id`, got undefined, and every move went to
 * /api/play/session/undefined/move. This shape is the contract being pinned.
 */
export const playSession: PlaySession = {
  session_id: SESSION_ID,
  fen: startFen,
  san_moves: '',
  player_color: 'white',
  skill_level: 9,
  target_eco: 'A01',
  target_opening: 'Nimzovich-Larsen Attack',
  status: 'IN_PROGRESS',
  result: null,
  end_reason: null,
  rated: true,
}

/** What /api/play/report/{id} returns while the engine is still working. */
export const pendingReport: ImprovementReport = {
  practice_game_id: SESSION_ID,
  // Set, because archiving precedes measurement — which is exactly why the UI
  // must gate the analysis link on `analysed` rather than on this id existing.
  game_id: PRACTICE_GAME_ID,
  progress: { stage: 'SWEEPING', done: 12, total: 78 } satisfies GameAnalysisProgress,
  analysed: false,
  rated: true,
  verdict: 'Still analysing this game.',
  comparisons: [],
  improved: [],
  still_to_work: [],
  sample_size: 0,
  trend_claimable: false,
  caveat: null,
}

/** The same report once the engine has actually measured the game. */
export const analysedReport: ImprovementReport = {
  practice_game_id: SESSION_ID,
  game_id: PRACTICE_GAME_ID,
  progress: null,
  analysed: true,
  rated: true,
  verdict: 'Your best practice game yet.',
  comparisons: [
    {
      label: 'Accuracy',
      value: '80.0%',
      baseline: '83.5%',
      direction: 'WORSE',
      note: 'Against your recent practice games, not your Chess.com games.',
    },
  ],
  improved: [],
  still_to_work: ['Accuracy'],
  sample_size: 6,
  trend_claimable: true,
  caveat: null,
}

/**
 * A short game where exactly one move is flagged. Two unflagged moves sit either
 * side of it so a test can tell "every move is listed" apart from "the mistake
 * list is listed" — the distinction the review view exists for.
 */
export const gameReview: GameReview = {
  game_id: PRACTICE_GAME_ID,
  player_color: 'black',
  opening_eco: 'A01',
  opening_name: 'Nimzovich-Larsen Attack',
  result: '1-0',
  accuracy: 80.0,
  analysis_status: 'ANALYZED',
  moves: [
    {
      ply: 1, move_number: 1, san: 'b3', color: 'white', by_player: false,
      fen_before: startFen,
      fen_after: 'rnbqkbnr/pppppppp/8/8/8/1P6/P1PPPPPP/RNBQKBNR b KQkq - 0 1',
      mistake: null,
    },
    {
      ply: 2, move_number: 1, san: 'e5', color: 'black', by_player: true,
      fen_before: 'rnbqkbnr/pppppppp/8/8/8/1P6/P1PPPPPP/RNBQKBNR b KQkq - 0 1',
      fen_after: 'rnbqkbnr/pppp1ppp/8/4p3/8/1P6/P1PPPPPP/RNBQKBNR w KQkq - 0 2',
      mistake: null,
    },
    {
      ply: 3, move_number: 2, san: 'Bb2', color: 'white', by_player: false,
      fen_before: 'rnbqkbnr/pppp1ppp/8/4p3/8/1P6/P1PPPPPP/RNBQKBNR w KQkq - 0 2',
      fen_after: 'rnbqkbnr/pppp1ppp/8/4p3/8/1P6/PBPPPPPP/RN1QKBNR b KQkq - 1 2',
      mistake: null,
    },
    {
      ply: 4, move_number: 2, san: 'Nc6', color: 'black', by_player: true,
      fen_before: 'rnbqkbnr/pppp1ppp/8/4p3/8/1P6/PBPPPPPP/RN1QKBNR b KQkq - 1 2',
      fen_after: 'r1bqkbnr/pppp1ppp/2n5/4p3/8/1P6/PBPPPPPP/RN1QKBNR w KQkq - 2 3',
      mistake: {
        id: 'c4f1a0d2-9e77-4b31-8a55-6d02e9c41b7a',
        move_number: 4,
        move_played: 'Nc6',
        better_move: 'd7d5',
        fen_position: 'rnbqkbnr/pppp1ppp/8/4p3/8/1P6/PBPPPPPP/RN1QKBNR b KQkq - 1 2',
        severity: 'MISTAKE',
        tactical_motif: 'POSITIONAL',
        explanation: 'Nc6 blocks the c-pawn and leaves e5 loose against the long diagonal.',
        game_phase: 'OPENING',
        clock_remaining: null,
        analysis_state: 'EXPLAINED',
      },
    },
  ],
}

/**
 * Practice history as it really looks: a few finished games among a pile of
 * sessions that were started and never played. The junk rows are the point —
 * the panel has to filter them, and a fixture of only clean rows would not
 * catch a regression that stopped filtering.
 */
export const practiceHistory: PracticeGameSummary[] = [
  {
    id: '11111111-1111-4111-8111-111111111111',
    game_id: PRACTICE_GAME_ID,
    started_at: '2026-08-28T13:38:32Z',
    finished_at: '2026-08-28T13:42:53Z',
    status: 'FINISHED',
    result: 'loss',
    skill_level: 9,
    target_opening: 'Nimzovich-Larsen Attack',
    rated: true,
    analysed: true,
  },
  {
    id: '22222222-2222-4222-8222-222222222222',
    game_id: '99999999-9999-4999-8999-999999999999',
    started_at: '2026-08-27T10:00:00Z',
    finished_at: '2026-08-27T10:20:00Z',
    status: 'FINISHED',
    result: 'win',
    skill_level: 9,
    target_opening: 'Nimzovich-Larsen Attack',
    rated: false,
    analysed: true,
  },
  {
    // Archived but never measured — must not offer a review of an empty analysis.
    id: '33333333-3333-4333-8333-333333333333',
    game_id: '44444444-4444-4444-8444-444444444444',
    started_at: '2026-08-26T09:00:00Z',
    finished_at: '2026-08-26T09:15:00Z',
    status: 'FINISHED',
    result: 'loss',
    skill_level: 9,
    target_opening: 'Nimzovich-Larsen Attack',
    rated: true,
    analysed: false,
  },
  {
    // Tab closed mid-game. Never a game; must not appear.
    id: '55555555-5555-4555-8555-555555555555',
    game_id: null,
    started_at: '2026-08-25T08:00:00Z',
    finished_at: null,
    status: 'IN_PROGRESS',
    result: null,
    skill_level: 9,
    target_opening: 'Nimzovich-Larsen Attack',
    rated: true,
    analysed: false,
  },
  {
    id: '66666666-6666-4666-8666-666666666666',
    game_id: null,
    started_at: '2026-08-24T08:00:00Z',
    finished_at: '2026-08-24T08:00:06Z',
    status: 'ABANDONED',
    result: null,
    skill_level: 9,
    target_opening: 'Nimzovich-Larsen Attack',
    rated: true,
    analysed: false,
  },
]

/**
 * Two patterns: one drillable (motif, position-backed) and one not (castling,
 * a habit with no position whose best move is the lesson). The pair is the
 * point — the UI must offer "Practise this" for exactly one of them.
 */
export const practicePatterns: PracticePatternReport = {
  games_considered: 6,
  claimable: true,
  caveat: null,
  opening_caveat:
    'Every one of these games is the Nimzovich-Larsen Attack, because your opponent ' +
    'is built to play your weakest opening. Some of what follows may be specific to ' +
    'that opening rather than a habit you carry everywhere.',
  patterns: [
    {
      id: 'castling-delay',
      title: 'You castle late',
      finding: 'You castled after move 10 in 4 of 6 games, and never castled in 1 of them.',
      why: 'An uncastled king stays on the file the opponent will open.',
      what_to_do: 'Aim to castle inside the first ten moves.',
      strength: 'EMERGING',
      games_affected: 4,
      games_considered: 6,
      drill_motif: null,
      evidence: [
        { game_id: PRACTICE_GAME_ID, ply: 23, label: 'Castled on move 12 (2026-08-28)' },
        { game_id: PRACTICE_GAME_ID, ply: null, label: 'Never castled (2026-08-27)' },
      ],
    },
    {
      id: 'recurring-motif',
      title: 'The same tactic keeps catching you',
      finding: 'Hanging pieces showed up in 5 of 6 games, across 31 written-up mistakes.',
      why: 'A tactic you miss once is bad luck; the same one repeatedly is a shape you are not seeing.',
      what_to_do: 'Drill this motif from your own positions.',
      strength: 'EMERGING',
      games_affected: 5,
      games_considered: 6,
      drill_motif: 'HANGING_PIECE',
      evidence: [
        { game_id: PRACTICE_GAME_ID, ply: 14, label: 'Hanging pieces on move 7 (2026-08-28)' },
      ],
    },
  ],
  not_measured: [
    'Time and decision-making — practice games are untimed, so no clock is recorded.',
  ],
}

export const resignResult: MoveResult = {
  fen: startFen,
  opponent_move: null,
  san_moves: '',
  status: 'FINISHED',
  result: 'loss',
  end_reason: 'RESIGNATION',
  player_to_move: false,
}

// ── Settings ─────────────────────────────────────────────────────────────────
// The coverage totals are the sums of their months, and each month the sum of
// its days — the page derives nothing, so a fixture that disagreed with itself
// would test nothing.

export const settingsView: AppSettingsView = {
  sync_from: null,
  sync_to: null,
  analysis_from: null,
  analysis_to: null,
  library: {
    id: 1, label: 'library-v0', sweep_move_time_ms: 100, multi_pv_depth: 18,
    multi_pv_lines: 3, max_explanations: 3, created_at: '2026-09-22T10:00:00Z',
  },
  practice: {
    id: 2, label: 'practice-v0', sweep_move_time_ms: 200, multi_pv_depth: 20,
    multi_pv_lines: 4, max_explanations: 10, created_at: '2026-09-22T10:00:00Z',
  },
  bounds: {
    sweep_min: 50, sweep_max: 1000, depth_min: 12, depth_max: 30, lines_min: 1, lines_max: 10,
    explanations_max: 50, earliest_date: '2007-01-01', practice_budget_ms: 300000,
  },
}

export const coverage: Coverage = {
  first_game: '2026-07-03',
  last_game: '2026-08-20',
  synced: 5, analyzed: 4, pending: 1, failed: 0,
  months: [
    {
      month: '2026-08', synced: 3, analyzed: 2, pending: 1, failed: 0,
      analyzed_with: [{ settings_id: 1, label: 'library-v0', games: 2 }],
      days: [
        { date: '2026-08-20', synced: 2, analyzed: 1, pending: 1, failed: 0 },
        { date: '2026-08-05', synced: 1, analyzed: 1, pending: 0, failed: 0 },
      ],
    },
    {
      month: '2026-07', synced: 2, analyzed: 2, pending: 0, failed: 0,
      analyzed_with: [{ settings_id: 1, label: 'library-v0', games: 2 }],
      days: [{ date: '2026-07-03', synced: 2, analyzed: 2, pending: 0, failed: 0 }],
    },
  ],
  practice: { played: 3, analyzed: 3 },
  active_library_settings_id: 1,
  mixed_settings: false,
  analyzed_with: [{ settings_id: 1, label: 'library-v0', games: 4 }],
  outdated_in_range: 0,
}

/** After a depth change: one game re-analysed with v1, three still on v0. */
export const coverageMixed: Coverage = {
  ...coverage,
  active_library_settings_id: 3,
  mixed_settings: true,
  analyzed_with: [
    { settings_id: 1, label: 'library-v0', games: 3 },
    { settings_id: 3, label: 'library-v1', games: 1 },
  ],
  outdated_in_range: 3,
}

export const estimate: SettingsEstimate = {
  library: { measured: true, samples: 12, basis_label: 'library-v0', per_game_ms: 45600, explanations_measured: true },
  games_in_range: 5,
  library_total_ms: 228000,
  practice: { measured: true, samples: 12, basis_label: 'library-v0', per_game_ms: 90000, explanations_measured: true },
  practice_budget_ms: 300000,
  practice_within_budget: true,
}

export const estimateUnmeasured: SettingsEstimate = {
  library: { measured: false, samples: 0, basis_label: null, per_game_ms: null, explanations_measured: false },
  games_in_range: 5,
  library_total_ms: null,
  practice: { measured: false, samples: 0, basis_label: null, per_game_ms: null, explanations_measured: false },
  practice_budget_ms: 300000,
  practice_within_budget: null,
}

// ── Evidence lab ─────────────────────────────────────────────────────────────
// snake_case, matching what the backend actually sends: Jackson's naming
// strategy covers records too. The first version of this fixture was camelCase,
// agreed with equally wrong types, and every test passed against a page that
// showed nothing but "undefined" on the real API.
//
// Three mistakes, one per threat-probe outcome, because that is the field that
// decides which lesson applies — and a fixture with fewer would let a page that
// muddled them pass.

export const evidenceReport: EvidenceReport = {
  game_id: '11111111-1111-1111-1111-111111111111',
  player_color: 'white',
  opening: "Queen's Gambit Declined",
  played_at: '2026-08-20T10:00:00Z',
  model: 'praxis-phase1',
  flagged_total: 7,
  explained: [
    {
      // Ply 31 is White's 16th move.
      move_number: 31,
      played_san: 'Bd2',
      severity: 'BLUNDER',
      block: [
        'FEN: 4k3/8/8/q7/8/2N5/3B4/4K3 w - - 0 1',
        'Player: White',
        'Played: Bd2',
        'Reply: Qxc3',
        '',
        'State in one sentence what the move allows.',
      ].join('\n'),
      explanation: 'Bd2 leaves the knight on c3 undefended, and Qxc3 takes it: 3 points.',
      model_error: null,
      // A free move was worth almost nothing: the move created the problem.
      threat: { probed: true, skip_reason: null, move_uci: 'a5b4', score: 0.1 },
      threat_cost: 0.1,
      threatened: false,
      reply_threat_cost: 0.1,
      threat_is_reply: false,
      reply_uci: 'a5c3',
      tactics: [],
      visibility_depth: 6,
      visibility: 'MEDIUM',
      engine_ms: 240,
      model_ms: 580,
    },
    {
      // Ply 6 is Black's 3rd move.
      move_number: 6,
      played_san: 'Nf6',
      severity: 'BLUNDER',
      block: [
        'FEN: r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3',
        'Player: Black',
        'Played: Nf6',
        'Reply: Qxf7# (check)',
        'Outcome: mate in 1',
        '',
        'State in one sentence what the move allows.',
      ].join('\n'),
      explanation: 'Nf6 allows Qxf7, and the checks do not stop: mate in 1.',
      model_error: null,
      // The mate was on before the move, and it is the same move that refutes it.
      threat: { probed: true, skip_reason: null, move_uci: 'h5f7', score: 100 },
      threat_cost: 100,
      threatened: true,
      reply_threat_cost: 100,
      threat_is_reply: true,
      reply_uci: 'h5f7',
      tactics: [],
      visibility_depth: 1,
      visibility: 'SHALLOW',
      engine_ms: 210,
      model_ms: 560,
    },
    {
      move_number: 41,
      played_san: 'Rd1',
      severity: 'MISTAKE',
      block: [
        'FEN: 6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 21',
        'Player: White',
        'Played: Rd1',
        'Reply: Rb8',
        'Outcome: no material lost within 8 plies',
        '',
        'State in one sentence what the move allows.',
      ].join('\n'),
      explanation: 'Rd1 allows Rb8, which takes over the open file.',
      model_error: null,
      // A real threat existed, but the refutation is something else.
      threat: { probed: true, skip_reason: null, move_uci: 'e8e1', score: -1.8 },
      threat_cost: 1.8,
      threatened: true,
      // Clears a pawn only because a free tempo flatters any move.
      reply_threat_cost: 1.1,
      threat_is_reply: false,
      reply_uci: 'a8b8',
      tactics: [],
      visibility_depth: 14,
      visibility: 'DEEP',
      engine_ms: 225,
      model_ms: 590,
    },
  ],
  engine_ms_total: 675,
  model_ms_total: 1730,
  engine_ms_per_mistake: 225,
  model_ms_per_mistake: 576,
}

// ── Rule validation (Phase 3) ────────────────────────────────────────────────
// snake_case, as the backend sends it. The card carries NO rule verdict: that is
// the contract the page's blindness rests on, and a fixture that included one
// would let a page that displayed it pass.

export const labelCard: LabelCard = {
  id: '22222222-2222-2222-2222-222222222222',
  fen: 'r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3',
  player: 'BLACK',
  move_label: '3... Nf6',
  played_san: 'Nf6',
  played_uci: 'g8f6',
  best_san: 'g6',
  best_uci: 'g7g6',
  severity: 'BLUNDER',
  phase: 'OPENING',
  played_line: ['Nf6', 'Qxf7#'],
  best_line: ['g6', 'Qf3', 'Nf6'],
  evidence: [
    'GRAPH v1 · player: Black · move 3 · phase OPENING · severity BLUNDER',
    '[P0]  before the move · Black to move · Black win ≈ 47%',
    '[PC]  consequence · mate in 1 · consequence ply 1',
    '[R1]  critical reply Qxf7# (check) · line Qxf7#',
  ].join('\n'),
  labelled: 12,
  built: 60,
  target: 100,
}

export const ruleReport: RuleReport = {
  built: 60,
  labelled: 12,
  target: 100,
  stale: 0,
  consequence_agreement: 0.9167,
  consequence_ci: [0.646, 0.985],
  single_cause_accuracy: 0.75,
  single_cause_labelled: 8,
  mechanisms: [
    { mechanism: 'IGNORED_THREAT', true_positives: 3, false_positives: 1, false_negatives: 0,
      precision: 0.75, precision_ci: [0.301, 0.954], recall: 1, recall_ci: [0.438, 1] },
    { mechanism: 'REMOVED_DEFENDER', true_positives: 0, false_positives: 0, false_negatives: 1,
      precision: null, precision_ci: null, recall: 0, recall_ci: [0, 0.793] },
    { mechanism: 'MOVED_INTO_ATTACK', true_positives: 1, false_positives: 0, false_negatives: 0,
      precision: 1, precision_ci: [0.207, 1], recall: 1, recall_ci: [0.207, 1] },
    { mechanism: 'LOSING_CAPTURE', true_positives: 0, false_positives: 0, false_negatives: 0,
      precision: null, precision_ci: null, recall: null, recall_ci: null },
    { mechanism: 'CREATED_TACTIC', true_positives: 1, false_positives: 1, false_negatives: 1,
      precision: 0.5, precision_ci: [0.095, 0.905], recall: 0.5, recall_ci: [0.095, 0.905] },
    { mechanism: 'MISSED_OPPORTUNITY', true_positives: 3, false_positives: 0, false_negatives: 0,
      precision: 1, precision_ci: [0.438, 1], recall: 1, recall_ci: [0.438, 1] },
  ],
  composite_rate: 0.2333,
  not_concrete_rate: 0.35,
  rule_diagnoses_failing_verification: 0,
  budget: { graphs: 60, over_budget: 0, max_items: 25, median_tokens: 409, p95_tokens: 508, max_tokens: 588 },
  disagreements: [
    { id: '33333333-3333-3333-3333-333333333333', move_label: '16. a3',
      human_consequence: 'LOST_MATERIAL', rule_consequence: 'LOST_MATERIAL',
      human_mechanism: 'IGNORED_THREAT', rule_mechanisms: 'CREATED_TACTIC',
      note: 'Ne5 was coming anyway' },
  ],
}

// ── "Why?" (Phase 4) ─────────────────────────────────────────────────────────
// Scholar's mate, shaped exactly as the backend's WhyView serialises it: the
// rules' verified explanation, one step per claim, the board for each step.

export const WHY_GAME_ID = '7c3c9a2e-1111-4a22-8b33-445566778899'
const SCHOLAR_FEN = 'r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3'

export const scholarsMistake = {
  id: 'e1111111-1111-1111-1111-111111111111',
  move_number: 6,
  player_color: 'black',
  move_played: 'Nf6',
  better_move: 'g7g6',
  fen_position: SCHOLAR_FEN,
  severity: 'BLUNDER',
  tactical_motif: 'OTHER',
  game_phase: 'OPENING',
  explanation: 'Nf6 leaves f7 to the queen.',
  analysis_state: 'EXPLAINED',
}

export const scholarsWhy: MistakeWhy = {
  game_id: WHY_GAME_ID,
  ply: 6,
  move_label: '3... Nf6',
  diagnosis: {
    player: 'BLACK',
    played_san: 'Nf6', played_uci: 'g8f6', best_san: 'g6', best_uci: 'g7g6',
    eval_before: 0.3, eval_after: 100,
    consequence: 'MATED', mechanism: 'IGNORED_THREAT', motif: 'OTHER', visibility: 'SHALLOW',
    composite: false,
    explanation: 'Before Nf6, White already threatened Qxf7#. Nf6 does not deal with it: Qxf7# follows, and it is mate in 1. After g6, Qxf7# would not be possible.',
    verified: true,
    diagnosed_by: 'RULES',
    critical_reply: 'Qxf7#',
    lands_at_ply: 1,
    played_line: ['Nf6', 'Qxf7#'],
    best_line: ['g6', 'Qf3', 'Nf6'],
    steps: [
      { type: 'THREAT_EXISTS', text: 'Before Nf6, White already threatened Qxf7#.', board: 'P0',
        arrows: [{ from: 'h5', to: 'f7', kind: 'THREAT' }] },
      { type: 'DOES_NOT_ADDRESS', text: 'Nf6 does not deal with it.', board: 'P0',
        arrows: [{ from: 'g8', to: 'f6', kind: 'PLAYED' }, { from: 'h5', to: 'f7', kind: 'THREAT' }] },
      { type: 'CRITICAL_REPLY', text: 'Qxf7# is the critical reply.', board: 'PP',
        arrows: [{ from: 'h5', to: 'f7', kind: 'REPLY' }] },
      { type: 'MATE_IN', text: 'Mate in 1.', board: 'PC', arrows: [] },
      { type: 'COUNTERFACTUAL', text: 'After g6, Qxf7# is not possible.', board: 'PB', arrows: [] },
      { type: 'VISIBILITY', text: 'Visibility shallow.', board: 'P0',
        arrows: [{ from: 'g8', to: 'f6', kind: 'PLAYED' }, { from: 'g7', to: 'g6', kind: 'BEST' }] },
    ],
    boards: {
      P0: { id: 'P0', title: 'Before your move', fen: SCHOLAR_FEN },
      PP: { id: 'PP', title: 'After Nf6', fen: 'r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4' },
      PB: { id: 'PB', title: "After g6, the engine's move", fen: 'r1bqkbnr/pppp1p1p/2n3p1/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 0 4' },
      PC: { id: 'PC', title: 'After Qxf7#, where it costs you', fen: 'r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4' },
    },
  },
  facts: [],
}
