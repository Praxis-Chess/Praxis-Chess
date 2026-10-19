package com.praxis.report;

import java.util.List;

/**
 * The Training Plan, decided in code from the report's facts (Phase 12).
 *
 * <p>A week of sessions made of the player's own positions: what to work on,
 * in which order, and exactly which positions each session holds.
 *
 * @param priorities the causes to fix, in order, at most three
 * @param pattern    the tactic to learn to see, or null when none stands out apart from the priorities
 * @param sessions   this week, each about {@code minutesPerSession} long
 * @param openingsToReview openings well above the player's own rate, at most two
 */
public record PlanFacts(int basedOnGames, int sessionsPerWeek, int minutesPerSession, double perGame,
                        List<Priority> priorities, Priority pattern, List<Session> sessions,
                        List<ReportFacts.OpeningRow> openingsToReview) {

    /**
     * @param why       the evidence, one sentence in code from the facts
     * @param positions how many of the player's own positions there are for it
     */
    public record Priority(int rank, String kind, String key, String focus, String why, String drill,
                           int positions, int count, int base, int pct, int blunders, double avgLost) {}

    /**
     * @param goal  one line: what the session trains, in plain words
     * @param focus the priorities it draws its positions from
     */
    public record Session(int number, String goal, List<String> focus, List<ReportFacts.Example> positions) {}
}
