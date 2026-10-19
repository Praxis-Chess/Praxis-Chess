package com.praxis.report;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the Training Plan from the report's facts. Pure: tested directly.
 *
 * <p>Priorities are the report's top causes, in its order; the pattern is its
 * top tactic. The week is three sessions of six positions (about three minutes
 * each), drawn from those, costliest first, never the same position twice:
 * <pre>
 *   session 1: 4 × priority 1, 2 × pattern
 *   session 2: 4 × priority 2, 2 × priority 1
 *   session 3: 3 × priority 3, 3 × pattern
 * </pre>
 * A slot whose source has run out takes the next position in priority order.
 */
public final class TrainingPlanBuilder {

    static final int SESSIONS_PER_WEEK = 3;
    static final int MINUTES_PER_SESSION = 20;
    static final int POSITIONS_PER_SESSION = 6;

    private TrainingPlanBuilder() {}

    public static PlanFacts build(ReportFacts facts) {
        List<PlanFacts.Priority> priorities = new ArrayList<>();
        int rank = 1;
        for (ReportFacts.Weakness w : facts.topCauses()) priorities.add(priority(rank++, w));
        ReportFacts.Weakness tactic = distinctTactic(facts.topCauses(), facts.topTactics());
        PlanFacts.Priority pattern = tactic == null ? null : priority(0, tactic);

        List<ReportFacts.OpeningRow> openings = facts.openings().stream().limit(2).toList();
        return new PlanFacts(facts.gamesAnalysed(), SESSIONS_PER_WEEK, MINUTES_PER_SESSION, facts.perGame(),
                priorities, pattern, sessions(facts.topCauses(), tactic), openings);
    }

    static PlanFacts.Priority priority(int rank, ReportFacts.Weakness w) {
        return new PlanFacts.Priority(rank, w.kind(), w.key(), w.title(), why(w), w.drill(), w.count(),
                w.count(), w.base(), w.pct(), w.blunders(), w.avgLost());
    }

    /**
     * The tactic to add, unless it is mostly the priorities again: one mistake can
     * be both "moves that hand your opponent a tactic" and "forks", and two
     * entries over the same positions read as a duplicate.
     */
    static ReportFacts.Weakness distinctTactic(List<ReportFacts.Weakness> causes, List<ReportFacts.Weakness> tactics) {
        Set<String> covered = new HashSet<>();
        for (ReportFacts.Weakness c : causes) for (ReportFacts.Example e : c.positions()) covered.add(e.gameId() + ":" + e.ply());
        for (ReportFacts.Weakness t : tactics) {
            long shared = t.positions().stream().filter(e -> covered.contains(e.gameId() + ":" + e.ply())).count();
            if (shared * 2 <= t.positions().size()) return t;
        }
        return null;
    }

    /** One line per focus: what a session trains, said as an action. */
    static final Map<String, String> GOALS = Map.ofEntries(
            Map.entry("IGNORED_THREAT", "Spot the threat before you move"),
            Map.entry("MISSED_OPPORTUNITY", "Find your own chances first"),
            Map.entry("CREATED_TACTIC", "Check what your move allows"),
            Map.entry("MOVED_INTO_ATTACK", "Move only to safe squares"),
            Map.entry("REMOVED_DEFENDER", "Keep your defenders in place"),
            Map.entry("LOSING_CAPTURE", "Count before you capture"),
            Map.entry("UNDER_30S", "Keep time in hand"),
            Map.entry("FORK", "See the fork coming"),
            Map.entry("HANGING_PIECE", "Leave nothing undefended"),
            Map.entry("PIN", "Watch the line to your king"),
            Map.entry("SKEWER", "Keep king and queen off open lines"),
            Map.entry("DISCOVERED_ATTACK", "Look behind the piece in front"),
            Map.entry("BACK_RANK", "Give your king air"));

    static String why(ReportFacts.Weakness w) {
        String blunders = w.blunders() == 1 ? "1 of them a blunder" : w.blunders() + " of them blunders";
        if ("TIME_PRESSURE".equals(w.kind())) {
            return w.count() + " of " + w.base() + " serious mistakes with a recorded clock (" + w.pct()
                    + "%) came with " + ReportFactsBuilder.TIME_PRESSURE_SECONDS + " seconds or less left, "
                    + blunders + ".";
        }
        return w.count() + " of " + w.base() + " serious mistakes (" + w.pct() + "%), " + blunders + ".";
    }

    /** Slot plan per session: queue index and count. Queues: 0-2 = priorities, 3 = pattern. */
    private static final int[][][] SLOTS = {
            {{0, 4}, {3, 2}},
            {{1, 4}, {0, 2}},
            {{2, 3}, {3, 3}},
    };

    static List<PlanFacts.Session> sessions(List<ReportFacts.Weakness> causes, ReportFacts.Weakness tactic) {
        List<Deque<ReportFacts.Example>> queues = new ArrayList<>();
        List<ReportFacts.Weakness> sources = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ReportFacts.Weakness w = i < causes.size() ? causes.get(i) : null;
            queues.add(new ArrayDeque<>(w == null ? List.of() : w.positions()));
            sources.add(w);
        }
        queues.add(new ArrayDeque<>(tactic == null ? List.of() : tactic.positions()));
        sources.add(tactic);

        Set<String> used = new HashSet<>();
        List<PlanFacts.Session> out = new ArrayList<>();
        for (int s = 0; s < SESSIONS_PER_WEEK; s++) {
            List<ReportFacts.Example> picked = new ArrayList<>();
            Set<ReportFacts.Weakness> focus = new LinkedHashSet<>();
            for (int[] slot : SLOTS[s]) {
                for (int k = 0; k < slot[1]; k++) {
                    int from = take(queues, slot[0], used, picked);
                    if (from < 0) break;
                    focus.add(sources.get(from));
                }
            }
            if (picked.isEmpty()) break;
            ReportFacts.Weakness lead = focus.iterator().next();
            out.add(new PlanFacts.Session(s + 1, GOALS.getOrDefault(lead.key(), lead.title()),
                    focus.stream().map(ReportFacts.Weakness::title).toList(), picked));
        }
        return out;
    }

    /** One position from the preferred queue, else the next queue in order; returns the queue used or -1. */
    private static int take(List<Deque<ReportFacts.Example>> queues, int preferred, Set<String> used,
                            List<ReportFacts.Example> into) {
        List<Integer> order = new ArrayList<>(List.of(preferred, 0, 1, 2, 3));
        for (int q : order) {
            Deque<ReportFacts.Example> queue = queues.get(q);
            while (!queue.isEmpty()) {
                ReportFacts.Example e = queue.poll();
                if (used.add(e.gameId() + ":" + e.ply())) {
                    into.add(e);
                    return q;
                }
            }
        }
        return -1;
    }
}
