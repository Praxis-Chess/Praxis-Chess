package com.praxis.report;

import com.praxis.report.ReportFactsBuilder.GameRow;
import com.praxis.report.ReportFactsBuilder.Mistake;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * "Since this plan" (F10): for each focus of a Training Plan, how often it
 * happens in games played since the plan was made, against games before it.
 *
 * Rates are serious mistakes with that cause (or that tactic) per 100 of the
 * player's own moves, the Pattern Report's unit, so more games is not more
 * mistakes. Verified or silent: with fewer than {@link ReportFactsBuilder#MIN_SAMPLE}
 * diagnosed serious mistakes in the games since, there has not been enough play
 * to compare, and {@code enough} is false; the page then says so instead of
 * showing a change.
 */
public final class SincePlan {

    private SincePlan() {}

    /** One side of the comparison. */
    public record Window(int games, int moves, int count, double perHundred) {}

    public record Change(String key, String kind, Window before, Window after) {}

    /**
     * @param since        the first day counted as "since the plan"
     * @param seriousAfter diagnosed serious mistakes, of any cause, in games since
     * @param enough       seriousAfter reached the sample threshold
     */
    public record Result(LocalDate since, int seriousAfter, int minSample, boolean enough, List<Change> foci) {}

    public static Result compute(List<Mistake> mistakes, List<GameRow> games, Map<UUID, LocalDate> playedOn,
                                 LocalDate since, List<PlanFacts.Priority> foci) {
        Set<UUID> after = new HashSet<>(), before = new HashSet<>();
        for (GameRow g : games) {
            LocalDate d = playedOn.get(g.id());
            if (d == null) continue;
            (d.isBefore(since) ? before : after).add(g.id());
        }
        List<Mistake> counted = mistakes.stream().filter(m -> m.serious() && m.diagnosed()).toList();
        int seriousAfter = (int) counted.stream().filter(m -> after.contains(m.gameId())).count();

        List<Change> changes = new ArrayList<>();
        for (PlanFacts.Priority f : foci) {
            Predicate<Mistake> is = switch (f.kind()) {
                case "CAUSE" -> m -> f.key().equals(m.mechanism());
                case "TACTIC" -> m -> f.key().equals(m.motif());
                default -> null;   // time trouble needs the clock, not a cause: not compared here
            };
            if (is == null) continue;
            changes.add(new Change(f.key(), f.kind(),
                    window(before, games, counted, is), window(after, games, counted, is)));
        }
        return new Result(since, seriousAfter, ReportFactsBuilder.MIN_SAMPLE,
                seriousAfter >= ReportFactsBuilder.MIN_SAMPLE, changes);
    }

    private static Window window(Set<UUID> ids, List<GameRow> games, List<Mistake> counted, Predicate<Mistake> is) {
        int moves = 0;
        for (GameRow g : games) {
            if (!ids.contains(g.id())) continue;
            for (int n : g.movesByPhase()) moves += n;
        }
        int count = (int) counted.stream().filter(m -> ids.contains(m.gameId())).filter(is).count();
        double perHundred = moves == 0 ? 0 : Math.round(1000.0 * count / moves) / 10.0;
        return new Window(ids.size(), moves, count, perHundred);
    }
}
