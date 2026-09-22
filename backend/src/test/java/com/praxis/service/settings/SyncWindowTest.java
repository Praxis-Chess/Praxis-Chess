package com.praxis.service.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SyncWindow")
class SyncWindowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    @Test
    @DisplayName("lastMonths(1) is the current month — the old Sync Now")
    void syncNowIsTheCurrentMonth() {
        SyncWindow w = SyncWindow.lastMonths(1, TODAY);
        assertThat(w.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(w.to()).isEqualTo(TODAY);
        assertThat(w.months(TODAY)).containsExactly(YearMonth.of(2026, 9));
    }

    @Test
    @DisplayName("lastMonths(3) is three archives, newest first — the old Re-Sync")
    void resyncIsThreeMonthsNewestFirst() {
        assertThat(SyncWindow.lastMonths(3, TODAY).months(TODAY))
                .containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 8), YearMonth.of(2026, 7));
    }

    @Test
    @DisplayName("a range maps to every monthly archive it touches, even partially")
    void rangeTouchesPartialMonths() {
        SyncWindow w = new SyncWindow(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 8, 3));
        assertThat(w.months(TODAY))
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 7), YearMonth.of(2026, 6));
    }

    @Test
    @DisplayName("a range into the future stops at the current month")
    void futureRangeStopsAtToday() {
        SyncWindow w = new SyncWindow(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 1, 31));
        assertThat(w.months(TODAY)).containsExactly(YearMonth.of(2026, 9));
    }

    /**
     * The rule that keeps widening a range safe. A completed month is cached as
     * synced and never fetched again — so a month the window only partly covers
     * must NOT be cached, or its uncovered days could never be synced later.
     */
    @Test
    @DisplayName("only a month covered from its first day to its last may be cached")
    void onlyWholeMonthsAreCacheable() {
        SyncWindow w = new SyncWindow(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 8, 31));
        assertThat(w.coversWholeMonth(YearMonth.of(2026, 6))).as("starts on the 15th").isFalse();
        assertThat(w.coversWholeMonth(YearMonth.of(2026, 7))).isTrue();
        assertThat(w.coversWholeMonth(YearMonth.of(2026, 8))).as("ends on the 31st").isTrue();
    }

    @Test
    @DisplayName("the in-progress month is never cacheable under the default window")
    void currentMonthIsNeverCached() {
        // Today is the 22nd: games can still arrive on the 23rd–30th. Caching the
        // month now would skip them once it's over.
        SyncWindow w = SyncWindow.lastMonths(3, TODAY);
        assertThat(w.coversWholeMonth(YearMonth.of(2026, 9))).isFalse();
        assertThat(w.coversWholeMonth(YearMonth.of(2026, 8))).isTrue();
    }

    @Test
    @DisplayName("contains is inclusive at both ends")
    void containsIsInclusive() {
        SyncWindow w = new SyncWindow(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(w.contains(LocalDate.of(2026, 7, 1))).isTrue();
        assertThat(w.contains(LocalDate.of(2026, 7, 31))).isTrue();
        assertThat(w.contains(LocalDate.of(2026, 6, 30))).isFalse();
        assertThat(w.contains(LocalDate.of(2026, 8, 1))).isFalse();
        assertThat(w.contains(null)).isFalse();
    }

    @Test
    @DisplayName("a backwards or open-ended window is rejected")
    void rejectsInvalidWindows() {
        assertThatThrownBy(() -> new SyncWindow(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncWindow(null, TODAY)).isInstanceOf(IllegalArgumentException.class);
    }
}
