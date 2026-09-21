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
