package com.praxis.service.settings;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * An inclusive date range for syncing, translated into Chess.com's unit of
 * storage: the monthly archive.
 *
 * @param from first day included (never null — see {@link #lastMonths})
 * @param to   last day included (never null)
 */
public record SyncWindow(LocalDate from, LocalDate to) {

    public SyncWindow {
        if (from == null || to == null) throw new IllegalArgumentException("both bounds are required");
        if (from.isAfter(to)) throw new IllegalArgumentException("from is after to");
    }

    /**
     * The pre-Settings behaviour, as a window: the current month and the
     * {@code months - 1} before it. Sync Now used 1, Re-Sync 3.
     */
    public static SyncWindow lastMonths(int months, LocalDate today) {
        int n = Math.max(1, months);
        return new SyncWindow(today.withDayOfMonth(1).minusMonths(n - 1L), today);
    }

    /**
     * The monthly archives to fetch, newest first — the same order the sync
     * loop has always used. A window reaching into the future stops at the
     * current month: there is no archive for a month that hasn't happened.
     */
    public List<YearMonth> months(LocalDate today) {
        YearMonth first = YearMonth.from(from);
        YearMonth last = YearMonth.from(to.isAfter(today) ? today : to);
        List<YearMonth> out = new ArrayList<>();
        for (YearMonth ym = last; !ym.isBefore(first); ym = ym.minusMonths(1)) out.add(ym);
        return out;
    }

    /**
     * Whether this window includes every day of {@code ym}.
     *
     * The sync caches a completed month as "done" and never fetches it again.
     * If a window starting on the 15th were allowed to cache its month, the
     * 1st–14th would never be fetched, even after the range was widened. So
     * only a month covered from its first day to its last may be cached.
     */
    public boolean coversWholeMonth(YearMonth ym) {
        return !from.isAfter(ym.atDay(1)) && !to.isBefore(ym.atEndOfMonth());
    }

    /**
     * Whether a game played on {@code day} belongs to the window.
     *
     * Takes a date, not a timestamp: the caller converts with PraxisClock, so a
     * game played at 01:00 on the 22nd local time counts as the 22nd, as the
     * streaks already do — not as the 21st in UTC.
     */
    public boolean contains(LocalDate day) {
        return day != null && !day.isBefore(from) && !day.isAfter(to);
    }
}
