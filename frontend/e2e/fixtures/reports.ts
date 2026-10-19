import type { Pattern, PlanRecall, ReportExample, ReportWeakness, SincePlan, TrainingPlan } from '../../src/api/types'

/**
 * Phase 12: the Pattern Report and Training Plan, computed in code. Typed with
 * the application's own interfaces, like data.ts. Synthetic values.
 */

const GAME = '7c3c9a2e-1111-4a22-8b33-445566778899'

/** As the server derives them from ReportFactsBuilder: never stored, so every report has them. */
export const drillShort: Record<string, string> = {
  IGNORED_THREAT: 'Name the threat before each move',
  MISSED_OPPORTUNITY: 'Check your own checks and captures first',
  FORK: 'Find the square that hits two pieces',
  PIN: 'Look behind the piece you rely on',
}

const pos = (ply: number, move: string): ReportExample => ({
  game_id: GAME, ply, move, severity: 'BLUNDER', fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
  better_move: 'e2e4', played_on: '2026-09-06', opening: 'Scandinavian Defense', lost: 34.2,
})

const threat: ReportWeakness = {
  kind: 'CAUSE', key: 'IGNORED_THREAT', title: "Ignoring your opponent's threat", count: 32, base: 145, pct: 22,
  blunders: 19, lost: 860, avg_lost: 26.9, drill: "Before each move, name what your opponent's last move threatens.",
  positions: [pos(45, '23.Qxc6'), pos(34, '17...g6'), pos(60, '30...Qe4'), pos(21, '11.Nf3'), pos(23, '12.Bd3')],
}
const chances: ReportWeakness = {
  kind: 'CAUSE', key: 'MISSED_OPPORTUNITY', title: 'Missing your own chances', count: 22, base: 145, pct: 15,
  blunders: 9, lost: 410, avg_lost: 18.6, drill: 'Look at your own checks, captures and threats before anything else.',
  positions: [pos(36, '18...Rxf8'), pos(27, '14.exd5')],
}
const forks: ReportWeakness = {
  kind: 'TACTIC', key: 'FORK', title: 'Forks', count: 14, base: 145, pct: 10, blunders: 11, lost: 420, avg_lost: 30,
  drill: 'Before you move, look for a square where one enemy piece attacks two of yours.',
  positions: [pos(45, '23.Qxc6'), pos(49, '25.g5')],
}

export const pattern: Pattern = {
  id: 'p1', games_analyzed: 31, computed_at: '2026-10-16T08:00:00Z',
  mistakes_moves1to10: 40, mistakes_moves11to20: 120, mistakes_moves21to30: 80, mistakes_moves31_plus: 36,
  mistakes_opening: 90, mistakes_middlegame: 170, mistakes_endgame: 16,
  motif_frequency: null, opening_accuracy: null,
  primary_weakness: threat.title, secondary_weakness: chances.title, tertiary_weakness: null,
  critical_move_range: 'Moves 11-20', dominant_motif: 'FORK', opening_assessment: null,
  facts: {
    games_analysed: 31, mistakes: 276, blunders: 68, serious_mistakes: 77, inaccuracies: 131, serious: 145,
    diagnosed: 276, diagnosed_serious: 145, player_moves: 1068,
    by_phase: [
      { key: 'OPENING', label: 'opening', count: 40, blunders: 18, moves: 372, per_hundred: 10.8, enough: true },
      { key: 'MIDDLEGAME', label: 'middlegame', count: 96, blunders: 51, moves: 547, per_hundred: 17.6, enough: true },
      { key: 'ENDGAME', label: 'endgame', count: 9, blunders: 7, moves: 149, per_hundred: 6, enough: false },
    ],
    by_move_range: [
      { key: '1-10', label: 'moves 1-10', count: 31, blunders: 8, moves: 310, per_hundred: 10, enough: true },
      { key: '11-20', label: 'moves 11-20', count: 53, blunders: 26, moves: 297, per_hundred: 17.8, enough: true },
      { key: '21-30', label: 'moves 21-30', count: 35, blunders: 22, moves: 201, per_hundred: 17.4, enough: true },
      { key: '31+', label: 'moves 31+', count: 26, blunders: 12, moves: 260, per_hundred: 10, enough: true },
    ],
    causes: [
      { key: 'IGNORED_THREAT', label: 'ignored a threat', count: 32, blunders: 19, pct: 22 },
      { key: 'MISSED_OPPORTUNITY', label: 'missed a chance', count: 22, blunders: 9, pct: 15 },
      { key: 'UNCLEAR', label: 'unclear', count: 17, blunders: 6, pct: 12 },
    ],
    tactics: [
      { key: 'FORK', label: 'forks', count: 14, blunders: 11, pct: 10 },
      { key: 'PIN', label: 'pins', count: 5, blunders: 3, pct: 3 },
    ],
    no_named_tactic: 96,
    no_cause: 40,
    min_sample: 10,
    time_pressure: { with_clock: 4, under_thirty: 0, blunders_under_thirty: 0, pct: 0, enough: false },
    top_causes: [threat, chances],
    top_tactics: [forks],
    per_game: 4.7,
    openings: [{ eco: 'C21', name: 'Center Game', games: 5, serious: 30, blunders: 15, serious_per_game: 6, vs_average: 1.28 }],
    worst_phase: 'MIDDLEGAME', worst_range: '11-20',
  },
  prose: null,
  prose_status: null,
  prose_model: null,
  drill_short: drillShort,
}

export const trainingPlan: TrainingPlan = {
  id: 't1', generated_at: '2026-10-16T08:05:00Z', based_on_games: 31,
  plan_json: '{}', openings_to_drill: '["Center Game"]', tactical_patterns: '["FORK"]',
  plan: {
    based_on_games: 31, sessions_per_week: 3, minutes_per_session: 20, per_game: 4.7,
    priorities: [
      { rank: 1, kind: 'CAUSE', key: 'IGNORED_THREAT', focus: threat.title,
        why: '32 of 145 serious mistakes (22%), 19 of them blunders.', drill: threat.drill, positions: 32,
        count: 32, base: 145, pct: 22, blunders: 19, avg_lost: 26.9 },
      { rank: 2, kind: 'CAUSE', key: 'MISSED_OPPORTUNITY', focus: chances.title,
        why: '22 of 145 serious mistakes (15%), 9 of them blunders.', drill: chances.drill, positions: 22,
        count: 22, base: 145, pct: 15, blunders: 9, avg_lost: 18.6 },
    ],
    pattern: { rank: 0, kind: 'TACTIC', key: 'FORK', focus: 'Forks',
      why: '14 of 145 serious mistakes (10%), 11 of them blunders.', drill: forks.drill, positions: 14,
      count: 14, base: 145, pct: 10, blunders: 11, avg_lost: 30 },
    sessions: [
      { number: 1, goal: 'Spot the threat before you move', focus: [threat.title, 'Forks'],
        positions: [pos(45, '23.Qxc6'), pos(34, '17...g6'), pos(49, '25.g5')] },
      { number: 2, goal: 'Find your own chances first', focus: [chances.title],
        positions: [pos(36, '18...Rxf8'), pos(27, '14.exd5')] },
    ],
    openings_to_review: [{ eco: 'C21', name: 'Center Game', games: 5, serious: 30, blunders: 15, serious_per_game: 6, vs_average: 1.28 }],
  },
  prose: null,
  prose_status: null,
  prose_model: null,
  progress: { '1': { done_at: '2026-10-17T10:00:00Z', solved: 2, total: 3 } },
  drill_short: drillShort,
}

/** F10, the usual first week: too little play since the plan to compare. */
export const sinceTooSoon: SincePlan = {
  since: '2026-10-16', serious_after: 4, min_sample: 10, enough: false,
  foci: [
    { key: 'IGNORED_THREAT', kind: 'CAUSE', before: { games: 31, moves: 1068, count: 32, per_hundred: 3 }, after: { games: 2, moves: 70, count: 1, per_hundred: 1.4 } },
  ],
}

/** F10, enough play since: a figure to compare. */
export const sinceEnough: SincePlan = {
  since: '2026-10-16', serious_after: 12, min_sample: 10, enough: true,
  foci: [
    { key: 'IGNORED_THREAT', kind: 'CAUSE', before: { games: 31, moves: 1068, count: 32, per_hundred: 3 }, after: { games: 6, moves: 240, count: 3, per_hundred: 1.3 } },
    { key: 'MISSED_OPPORTUNITY', kind: 'CAUSE', before: { games: 31, moves: 1068, count: 22, per_hundred: 2.1 }, after: { games: 6, moves: 240, count: 6, per_hundred: 2.5 } },
    { key: 'FORK', kind: 'TACTIC', before: { games: 31, moves: 1068, count: 14, per_hundred: 1.3 }, after: { games: 6, moves: 240, count: 2, per_hundred: 0.8 } },
  ],
}

/** F11: session 1 has two of its three positions in the review deck; session 2 none. */
export const recall: PlanRecall = {
  since: '2026-10-16T08:05:00Z',
  sessions: [
    { number: 1, positions: 3, tracked: 2, reviews: 4, correct: 3, detail: [] },
    { number: 2, positions: 2, tracked: 0, reviews: 0, correct: 0, detail: [] },
  ],
}
