package com.praxis.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * The practice streak, derived entirely from the daily ledger.
 *
 * Named practiceStreak, never streak: DashboardStatsDto already exposes
 * formStreak, which is consecutive wins or losses. Two things called "streak"
 * in one API is a support burden.
 *
 * @param currentStreak      consecutive practice days ending today or yesterday
 * @param longestStreak      best run ever; survives a break
 * @param totalDaysPracticed every day ever recorded
 * @param practicedToday     whether today already counts
 * @param today              the server's calendar date, in the user's zone
 * @param practiceDays       EVERY day ever practised, ascending
 */
public record PracticeStreakDto(
        int currentStreak,
        int longestStreak,
        int totalDaysPracticed,
        LocalDate lastPracticeDate,
        boolean practicedToday,
        LocalDate today,
        List<LocalDate> practiceDays
) {
    // `today` travels with the payload so the browser and the backend cannot
    // disagree about which day it is. They resolve the zone independently, and
    // near midnight that difference is exactly one day of streak.
    //
    // `practiceDays` is the whole history rather than a fixed window: the week
    // strip on Today and the all-months panel on Progress are both slices of it,
    // and a few years of daily practice is still only a few thousand dates.
}
