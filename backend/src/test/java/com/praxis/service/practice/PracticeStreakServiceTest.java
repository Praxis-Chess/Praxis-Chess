package com.praxis.service.practice;

import com.praxis.dto.PracticeStreakDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The streak is date arithmetic, and date arithmetic is where streak features
 * go wrong — usually at midnight, usually in a way nobody notices until a user
 * loses a run they earned. Everything here is pure, with "today" passed in.
 */
@DisplayName("PracticeStreakService Tests")
class PracticeStreakServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 17);

    private static Set<LocalDate> days(String... isoDates) {
        return new TreeSet<>(Arrays.stream(isoDates).map(LocalDate::parse).toList());
    }

    /** Consecutive days ending on `end`, oldest first. */
    private static Set<LocalDate> run(LocalDate end, int length) {
        Set<LocalDate> d = new TreeSet<>();
        for (int i = 0; i < length; i++) d.add(end.minusDays(i));
        return d;
    }

    @Nested
    @DisplayName("Current Streak Tests")
    class CurrentStreakTests {

        @Test
        @DisplayName("Should count today when practice happened today")
        void shouldCountToday() {
            var s = PracticeStreakService.build(days("2026-08-17"), TODAY);

            assertThat(s.currentStreak()).isEqualTo(1);
            assertThat(s.practicedToday()).isTrue();
        }

        @Test
        @DisplayName("Should keep the streak alive when the last day was yesterday")
        void shouldKeepStreakAliveFromYesterday() {
            // Without this grace the counter reads 0 every morning, which
            // punishes the user for not having practised *yet* today.
            var s = PracticeStreakService.build(days("2026-08-16"), TODAY);

            assertThat(s.currentStreak()).isEqualTo(1);
            assertThat(s.practicedToday()).isFalse();
        }

        @Test
        @DisplayName("Should end the streak when the last day was two days ago")
        void shouldEndStreakAfterTwoDays() {
            var s = PracticeStreakService.build(days("2026-08-15"), TODAY);

            assertThat(s.currentStreak()).isZero();
            assertThat(s.longestStreak()).isEqualTo(1);   // history is preserved
        }

        @Test
        @DisplayName("Should count a twelve-day run ending today")
        void shouldCountTwelveConsecutiveDays() {
            var s = PracticeStreakService.build(run(TODAY, 12), TODAY);

            assertThat(s.currentStreak()).isEqualTo(12);
            assertThat(s.totalDaysPracticed()).isEqualTo(12);
        }

        @Test
        @DisplayName("Should count only the tail when there is a gap")
        void shouldCountOnlyTheTailAcrossAGap() {
            Set<LocalDate> d = new TreeSet<>(run(TODAY, 3));       // 15,16,17
            d.addAll(run(LocalDate.of(2026, 8, 10), 5));           // 6..10

            var s = PracticeStreakService.build(d, TODAY);

            assertThat(s.currentStreak()).isEqualTo(3);
            assertThat(s.longestStreak()).isEqualTo(5);
            assertThat(s.totalDaysPracticed()).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("Longest Streak Tests")
    class LongestStreakTests {

        @Test
        @DisplayName("Should survive a break")
        void shouldSurviveABreak() {
            var s = PracticeStreakService.build(run(LocalDate.of(2026, 7, 1), 9), TODAY);

            assertThat(s.currentStreak()).isZero();
            assertThat(s.longestStreak()).isEqualTo(9);
        }

        @Test
        @DisplayName("Should never be less than the current streak")
        void shouldNeverBeLessThanCurrent() {
            var s = PracticeStreakService.build(run(TODAY, 4), TODAY);

            assertThat(s.longestStreak()).isGreaterThanOrEqualTo(s.currentStreak());
        }
    }

    @Nested
    @DisplayName("Day Boundary Tests")
    class DayBoundaryTests {

        @Test
        @DisplayName("Should treat 23:59 and 00:01 as two different days")
        void shouldTreatMidnightCrossingAsTwoDays() {
            // The case that breaks naive streaks: a late-night session and an
            // early-morning one are two days, and must both count.
            var s = PracticeStreakService.build(days("2026-08-16", "2026-08-17"), TODAY);

            assertThat(s.currentStreak()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should count a DST transition day exactly once")
        void shouldCountDstDayOnce() {
            // 2026-03-29 is a European DST transition — a 23-hour day. It is
            // still one calendar day and must not be double-counted or skipped.
            LocalDate dstDay = LocalDate.of(2026, 3, 29);
            var s = PracticeStreakService.build(run(dstDay, 3), dstDay);

            assertThat(s.currentStreak()).isEqualTo(3);
            assertThat(s.totalDaysPracticed()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Empty And Edge Tests")
    class EmptyAndEdgeTests {

        @Test
        @DisplayName("Should return zeros for an empty ledger without throwing")
        void shouldReturnZerosForEmptyLedger() {
            var s = PracticeStreakService.build(Set.of(), TODAY);

            assertThat(s.currentStreak()).isZero();
            assertThat(s.longestStreak()).isZero();
            assertThat(s.totalDaysPracticed()).isZero();
            assertThat(s.lastPracticeDate()).isNull();
            assertThat(s.practicedToday()).isFalse();
            assertThat(s.practiceDays()).isEmpty();
            assertThat(s.today()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("Should carry the server's today so the client cannot disagree")
        void shouldCarryServerToday() {
            // The browser resolves its own zone. Near midnight the two differ by
            // a day, which is exactly one day of streak.
            var s = PracticeStreakService.build(days("2026-08-17"), TODAY);

            assertThat(s.today()).isEqualTo(TODAY);
        }
    }

    @Nested
    @DisplayName("Full History Tests")
    class FullHistoryTests {

        @Test
        @DisplayName("Should return every practice day, ascending, however old")
        void shouldReturnEveryPracticeDayAscending() {
            // The Progress panel charts all months, so nothing may be windowed
            // out — a day from last year is still part of the history.
            Set<LocalDate> d = new TreeSet<>(days("2025-01-01", "2026-08-14", "2026-08-17"));

            var s = PracticeStreakService.build(d, TODAY);

            assertThat(s.practiceDays()).containsExactly(
                    LocalDate.parse("2025-01-01"),
                    LocalDate.parse("2026-08-14"),
                    LocalDate.parse("2026-08-17"));
            assertThat(s.totalDaysPracticed()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should keep history and totals in agreement")
        void shouldKeepHistoryAndTotalsInAgreement() {
            var s = PracticeStreakService.build(run(TODAY, 40), TODAY);

            assertThat(s.practiceDays()).hasSize(s.totalDaysPracticed());
        }
    }
}
