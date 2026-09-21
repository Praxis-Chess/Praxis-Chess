package com.praxis.service.practice;

import com.praxis.config.PraxisClock;
import com.praxis.domain.MetricSnapshot;
import com.praxis.dto.PracticeStreakDto;
import com.praxis.repository.MetricSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Derives the streak from the ledger. Nothing is stored but days, so there is
 * no counter that can drift out of step with the record.
 */
@Service
public class PracticeStreakService {

    private final MetricSnapshotRepository snapshots;
    private final PraxisClock clock;

    public PracticeStreakService(MetricSnapshotRepository snapshots, PraxisClock clock) {
        this.snapshots = snapshots;
        this.clock = clock;
    }

    public PracticeStreakDto compute(String username) {
        Set<LocalDate> days = new TreeSet<>();
        for (MetricSnapshot s : snapshots.findByUsernameOrderBySnapshotDateDesc(username)) {
            // A row can exist without being a practice day — rows written before
            // the ledger, or by anything that touches the snapshot for another
            // reason. Only marked days count.
            if (s.isPracticeDay() && s.getSnapshotDate() != null) days.add(s.getSnapshotDate());
        }
        return build(days, clock.today());
    }

    /** Pure, so midnight and DST are testable with a fixed clock. */
    static PracticeStreakDto build(Set<LocalDate> days, LocalDate today) {
        if (days.isEmpty()) {
            return new PracticeStreakDto(0, 0, 0, null, false, today, List.of());
        }

        LocalDate last = days.stream().max(LocalDate::compareTo).orElseThrow();
        boolean practicedToday = days.contains(today);

        // Alive if the last day is today OR yesterday. Without the yesterday
        // grace the counter reads 0 all morning — punishing you for not having
        // practised *yet today*, which is not a broken streak.
        int current = 0;
        if (!last.isBefore(today.minusDays(1))) {
            LocalDate cursor = practicedToday ? today : today.minusDays(1);
            while (days.contains(cursor)) {
                current++;
                cursor = cursor.minusDays(1);
            }
        }

        int longest = 0, run = 0;
        LocalDate prev = null;
        for (LocalDate d : days) {                 // TreeSet — ascending
            run = (prev != null && prev.plusDays(1).equals(d)) ? run + 1 : 1;
            longest = Math.max(longest, run);
            prev = d;
        }

        return new PracticeStreakDto(current, Math.max(longest, current), days.size(),
                last, practicedToday, today, List.copyOf(days));
    }
}
