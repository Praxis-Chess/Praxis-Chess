package com.praxis.report;

import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.report.ReportFacts.Count;
import com.praxis.report.ReportFacts.Example;
import com.praxis.report.ReportFacts.OpeningRow;
import com.praxis.report.ReportFacts.Rate;
import com.praxis.report.ReportFacts.TimePressure;
import com.praxis.report.ReportFacts.Weakness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Computes {@link ReportFacts} from the player's mistakes. Pure: no database, no
 * model, so every figure and every ranking is tested directly.
 *
 * <p>Causes and tactics are separate axes, ranked separately: one mistake can be
 * an ignored threat and a fork at once. Where mistakes happen is a rate per 100
 * of the player's moves, because raw counts mostly show where the moves are.
 */
public final class ReportFactsBuilder {

    /**
     * The one sample threshold, applied everywhere: a weakness needs this many
     * serious mistakes behind it to be ranked, and a rate, a clock figure or an
     * opening with fewer is marked as too few or left out.
     */
    public static final int MIN_SAMPLE = Thresholds.MIN_MISTAKES;
    /** An opening is listed only with this many analysed games (the games threshold)… */
    static final int MIN_OPENING_GAMES = Thresholds.MIN_GAMES;
    /** …and at least this far above the player's own rate (1.2 = a fifth more). */
    static final double OPENING_ABOVE = 1.2;
    static final int TIME_PRESSURE_SECONDS = 30;
    /** Time trouble is a weakness from this share of serious mistakes with a clock… */
    static final int TIME_PRESSURE_PCT = 25;

    private ReportFactsBuilder() {}

    /** One mistake, as the builder needs it. {@code motif}/{@code mechanism} null when undiagnosed. */
    public record Mistake(UUID gameId, int ply, Severity severity, GamePhase phase, Integer clockSeconds,
                          Double winPctDrop, String fen, String san, String betterMove, LocalDate playedOn,
                          String opening, boolean diagnosed, String motif, String mechanism) {
        boolean serious() {
            return severity == Severity.BLUNDER || severity == Severity.MISTAKE;
        }
        boolean blunder() {
            return severity == Severity.BLUNDER;
        }
    }

    /**
     * One analysed game.
     *
     * @param movesByPhase the player's moves per phase, in {@link GamePhase} order
     * @param movesByRange the player's moves in full moves 1-10, 11-20, 21-30, 31+
     */
    public record GameRow(UUID id, String eco, String opening, int[] movesByPhase, int[] movesByRange) {
        public GameRow(UUID id, String eco, String opening) {
            this(id, eco, opening, new int[3], new int[4]);
        }
    }

    static final List<String> RANGES = List.of("1-10", "11-20", "21-30", "31+");

    static final Map<String, String[]> CAUSES = new LinkedHashMap<>();
    static final Map<String, String> TACTICS = new LinkedHashMap<>();
    static final Map<String, String> TACTIC_DRILLS = new LinkedHashMap<>();
    static final Map<String, String> SHORT_DRILLS = new LinkedHashMap<>();
    static {
        CAUSES.put("IGNORED_THREAT", new String[]{"Ignoring your opponent's threat",
                "Before each move, name what your opponent's last move threatens."});
        CAUSES.put("MISSED_OPPORTUNITY", new String[]{"Missing your own chances",
                "Look at your own checks, captures and threats before anything else."});
        CAUSES.put("CREATED_TACTIC", new String[]{"Moves that hand your opponent a tactic",
                "Before you play a move, check what it allows."});
        CAUSES.put("MOVED_INTO_ATTACK", new String[]{"Moving pieces where they can be taken",
                "Check the attackers and defenders of every square you move to."});
        CAUSES.put("REMOVED_DEFENDER", new String[]{"Taking away a key defender",
                "Before moving a piece, ask what it was defending."});
        CAUSES.put("LOSING_CAPTURE", new String[]{"Captures that lose material",
                "Count attackers and defenders before every capture."});
        // The rules name the tactic your OPPONENT had after your move (geometry is
        // never read for a missed chance), or a piece of YOURS left hanging.
        TACTICS.put("FORK", "Forks");
        TACTICS.put("HANGING_PIECE", "Your pieces left hanging");
        TACTICS.put("PIN", "Pins");
        TACTICS.put("SKEWER", "Skewers");
        TACTICS.put("DISCOVERED_ATTACK", "Discovered attacks");
        TACTICS.put("BACK_RANK", "Back-rank mates");
        TACTIC_DRILLS.put("FORK", "Before you move, look for a square where one enemy piece attacks two of yours.");
        TACTIC_DRILLS.put("HANGING_PIECE", "After choosing a move, count who defends each piece you leave behind.");
        TACTIC_DRILLS.put("PIN", "Check whether a piece you rely on stands in front of your king or queen.");
        TACTIC_DRILLS.put("SKEWER", "Keep your king and queen off lines an enemy rook, bishop or queen can reach.");
        TACTIC_DRILLS.put("DISCOVERED_ATTACK", "Ask what an enemy piece would attack if the piece in front of it moved.");
        TACTIC_DRILLS.put("BACK_RANK", "Give your king an escape square before the back rank gets crowded.");
        // The same advice as an imperative of at most eight words: what the
        // pages show by default, with the full sentence one tap away.
        SHORT_DRILLS.put("IGNORED_THREAT", "Name the threat before each move");
        SHORT_DRILLS.put("MISSED_OPPORTUNITY", "Check your own checks and captures first");
        SHORT_DRILLS.put("CREATED_TACTIC", "Check what your move allows");
        SHORT_DRILLS.put("MOVED_INTO_ATTACK", "Count attackers on the square you move to");
        SHORT_DRILLS.put("REMOVED_DEFENDER", "Ask what the piece was defending");
        SHORT_DRILLS.put("LOSING_CAPTURE", "Count attackers and defenders before capturing");
        SHORT_DRILLS.put("FORK", "Find the square that hits two pieces");
        SHORT_DRILLS.put("HANGING_PIECE", "Count defenders of every piece you leave");
        SHORT_DRILLS.put("PIN", "Look behind the piece you rely on");
        SHORT_DRILLS.put("SKEWER", "Keep king and queen off open lines");
        SHORT_DRILLS.put("DISCOVERED_ATTACK", "Ask what moves when the front piece moves");
        SHORT_DRILLS.put("BACK_RANK", "Give your king an escape square");
    }

    /**
     * Short drills by cause or tactic key. Derived, never stored: a report made
     * before this existed gets them too, without a rebuild.
     */
    public static Map<String, String> shortDrills() {
        return java.util.Collections.unmodifiableMap(SHORT_DRILLS);
    }


    public static ReportFacts build(List<Mistake> all, List<GameRow> games) {
        List<Mistake> serious = all.stream().filter(Mistake::serious).toList();
        List<Mistake> diagnosedSerious = serious.stream().filter(Mistake::diagnosed).toList();
        int blunders = (int) all.stream().filter(Mistake::blunder).count();
        int inaccuracies = (int) all.stream().filter(m -> m.severity() == Severity.INACCURACY).count();

        int[] phaseMoves = new int[3];
        int[] rangeMoves = new int[4];
        for (GameRow g : games) {
            for (int i = 0; i < 3; i++) phaseMoves[i] += g.movesByPhase()[i];
            for (int i = 0; i < 4; i++) rangeMoves[i] += g.movesByRange()[i];
        }
        int playerMoves = phaseMoves[0] + phaseMoves[1] + phaseMoves[2];

        List<Rate> byPhase = new ArrayList<>();
        for (GamePhase p : GamePhase.values()) {
            byPhase.add(rate(p.name(), p.name().toLowerCase(Locale.ROOT), serious, m -> m.phase() == p,
                    phaseMoves[p.ordinal()]));
        }
        List<Rate> byRange = new ArrayList<>();
        for (int i = 0; i < RANGES.size(); i++) {
            String r = RANGES.get(i);
            byRange.add(rate(r, "moves " + r, serious, m -> range(m.ply()).equals(r), rangeMoves[i]));
        }

        List<Count> causes = new ArrayList<>();
        for (String c : List.of("IGNORED_THREAT", "MISSED_OPPORTUNITY", "CREATED_TACTIC", "MOVED_INTO_ATTACK",
                "REMOVED_DEFENDER", "LOSING_CAPTURE", "UNCLEAR")) {
            Count n = count(c, causeLabel(c), diagnosedSerious, m -> c.equals(m.mechanism()), diagnosedSerious.size());
            if (n.count() > 0) causes.add(n);
        }
        causes.sort(Comparator.comparingInt(Count::count).reversed());
        List<Count> tactics = new ArrayList<>();
        for (String t : TACTICS.keySet()) {
            Count n = count(t, TACTICS.get(t).toLowerCase(Locale.ROOT), diagnosedSerious, m -> t.equals(m.motif()),
                    diagnosedSerious.size());
            if (n.count() > 0) tactics.add(n);
        }
        tactics.sort(Comparator.comparingInt(Count::count).reversed());
        int noNamedTactic = (int) diagnosedSerious.stream()
                .filter(m -> "POSITIONAL".equals(m.motif()) || "OTHER".equals(m.motif())).count();
        int noCause = (int) diagnosedSerious.stream().filter(m -> "NONE".equals(m.mechanism())).count();

        List<Mistake> withClock = serious.stream().filter(m -> m.clockSeconds() != null).toList();
        List<Mistake> pressed = withClock.stream().filter(m -> m.clockSeconds() <= TIME_PRESSURE_SECONDS).toList();
        TimePressure tp = new TimePressure(withClock.size(), pressed.size(),
                (int) pressed.stream().filter(Mistake::blunder).count(), pct(pressed.size(), withClock.size()),
                withClock.size() >= MIN_SAMPLE);

        double perGame = games.isEmpty() ? 0 : round1((double) serious.size() / games.size());

        return new ReportFacts(
                games.size(), all.size(), blunders, serious.size() - blunders, inaccuracies,
                serious.size(), (int) all.stream().filter(Mistake::diagnosed).count(), diagnosedSerious.size(),
                playerMoves, byPhase, byRange, causes, tactics, noNamedTactic, noCause, MIN_SAMPLE, tp,
                topCauses(diagnosedSerious, withClock, pressed, tp), topTactics(diagnosedSerious),
                perGame, openings(serious, games, perGame),
                worst(byPhase), worst(byRange));
    }

    // ── weaknesses ──────────────────────────────────────────────────────────

    private record Scored(Weakness w, int score) {}

    static List<Weakness> topCauses(List<Mistake> diagnosedSerious, List<Mistake> withClock,
                                    List<Mistake> pressed, TimePressure tp) {
        List<Scored> scored = new ArrayList<>();
        CAUSES.forEach((key, words) -> {
            Weakness w = weakness("CAUSE", key, words[0], words[1], diagnosedSerious,
                    m -> key.equals(m.mechanism()), diagnosedSerious.size());
            if (w != null) scored.add(new Scored(w, score(w)));
        });
        if (tp.enough() && tp.pct() >= TIME_PRESSURE_PCT) {
            Weakness w = weakness("TIME_PRESSURE", "UNDER_30S", "Mistakes in time trouble",
                    "Spend less time early, keep 30 seconds in hand, and under a minute check only checks and captures.",
                    withClock, m -> m.clockSeconds() <= TIME_PRESSURE_SECONDS, withClock.size());
            if (w != null) scored.add(new Scored(w, score(w)));
        }
        return ranked(scored, 3);
    }

    static List<Weakness> topTactics(List<Mistake> diagnosedSerious) {
        List<Scored> scored = new ArrayList<>();
        TACTICS.forEach((key, label) -> {
            Weakness w = weakness("TACTIC", key, label, TACTIC_DRILLS.get(key),
                    diagnosedSerious, m -> key.equals(m.motif()), diagnosedSerious.size());
            if (w != null) scored.add(new Scored(w, score(w)));
        });
        return ranked(scored, 2);
    }

    private static Weakness weakness(String kind, String key, String title, String drill, List<Mistake> pool,
                                     Predicate<Mistake> test, int base) {
        List<Mistake> hits = pool.stream().filter(test).toList();
        if (hits.size() < MIN_SAMPLE) return null;
        int b = (int) hits.stream().filter(Mistake::blunder).count();
        double lost = hits.stream().mapToDouble(ReportFactsBuilder::lost).sum();
        return new Weakness(kind, key, title, hits.size(), base, pct(hits.size(), base), b,
                (int) Math.round(lost), round1(lost / hits.size()), drill, positions(hits));
    }

    /** Ranked by what it cost: winning chances lost in total, not how often. */
    private static int score(Weakness w) {
        return w.lost();
    }

    private static double lost(Mistake m) {
        return m.winPctDrop() == null ? 0 : Math.abs(m.winPctDrop());
    }

    private static List<Weakness> ranked(List<Scored> scored, int n) {
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparing(s -> s.w().key()))
                .limit(n)
                .map(Scored::w)
                .toList();
    }

    /** Every position, costliest first. */
    static List<Example> positions(List<Mistake> hits) {
        return hits.stream()
                .sorted(Comparator.comparingDouble((Mistake m) -> m.winPctDrop() == null ? 0 : m.winPctDrop()).reversed()
                        .thenComparing(Mistake::ply))
                .map(m -> new Example(m.gameId(), m.ply(), moveLabel(m.ply(), m.san()), m.severity().name(), m.fen(),
                        m.betterMove(), m.playedOn() == null ? null : m.playedOn().toString(), m.opening(),
                        round1(lost(m))))
                .toList();
    }

    // ── openings ────────────────────────────────────────────────────────────

    static List<OpeningRow> openings(List<Mistake> serious, List<GameRow> games, double perGame) {
        Map<String, List<GameRow>> byOpening = new LinkedHashMap<>();
        for (GameRow g : games) {
            String label = openingLabel(g);
            if (label != null) byOpening.computeIfAbsent(label, k -> new ArrayList<>()).add(g);
        }
        List<OpeningRow> rows = new ArrayList<>();
        byOpening.forEach((label, gs) -> {
            if (gs.size() < MIN_OPENING_GAMES || perGame <= 0) return;
            Set<UUID> ids = new HashSet<>();
            gs.forEach(g -> ids.add(g.id()));
            List<Mistake> in = serious.stream().filter(m -> ids.contains(m.gameId())).toList();
            double rate = round1((double) in.size() / gs.size());
            double vs = Math.round(100.0 * rate / perGame) / 100.0;
            if (vs < OPENING_ABOVE || in.size() < MIN_SAMPLE) return;
            rows.add(new OpeningRow(gs.get(0).eco(), label, gs.size(), in.size(),
                    (int) in.stream().filter(Mistake::blunder).count(), rate, vs));
        });
        rows.sort(Comparator.comparingDouble(OpeningRow::vsAverage).reversed().thenComparing(OpeningRow::name));
        return rows.stream().limit(5).toList();
    }

    private static String openingLabel(GameRow g) {
        if (g.opening() != null && !g.opening().isBlank()) return g.opening();
        return g.eco() == null || g.eco().isBlank() ? null : g.eco();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Count count(String key, String label, List<Mistake> pool, Predicate<Mistake> test, int base) {
        List<Mistake> in = pool.stream().filter(test).toList();
        return new Count(key, label, in.size(), (int) in.stream().filter(Mistake::blunder).count(), pct(in.size(), base));
    }

    private static Rate rate(String key, String label, List<Mistake> serious, Predicate<Mistake> test, int moves) {
        List<Mistake> in = serious.stream().filter(test).toList();
        double per = moves == 0 ? 0 : round1(100.0 * in.size() / moves);
        return new Rate(key, label, in.size(), (int) in.stream().filter(Mistake::blunder).count(), moves, per,
                in.size() >= MIN_SAMPLE);
    }

    private static String worst(List<Rate> rates) {
        // Only a rate with enough behind it can be the worst: the same rule as everywhere.
        return rates.stream().filter(r -> r.moves() > 0 && r.enough())
                .max(Comparator.comparingDouble(Rate::perHundred)).map(Rate::key).orElse(null);
    }

    /** Whole percentage; 0 of 0 is 0. */
    static int pct(int part, int whole) {
        return whole == 0 ? 0 : Math.round(100f * part / whole);
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Full move range of a ply: plies 1-20 are moves 1-10. */
    static String range(int ply) {
        int move = (ply + 1) / 2;
        return move <= 10 ? "1-10" : move <= 20 ? "11-20" : move <= 30 ? "21-30" : "31+";
    }

    public static int rangeIndex(int ply) {
        return RANGES.indexOf(range(ply));
    }

    /** "12.Nf6" for White's 12th move, "12...Nf6" for Black's. Plies are 1-based. */
    static String moveLabel(int ply, String san) {
        return ((ply + 1) / 2) + (ply % 2 == 1 ? "." : "...") + Objects.requireNonNullElse(san, "?");
    }

    static String causeLabel(String cause) {
        return switch (cause) {
            case "IGNORED_THREAT" -> "ignored a threat";
            case "MISSED_OPPORTUNITY" -> "missed a chance";
            case "CREATED_TACTIC" -> "allowed a tactic";
            case "MOVED_INTO_ATTACK" -> "moved into an attack";
            case "REMOVED_DEFENDER" -> "removed a defender";
            case "LOSING_CAPTURE" -> "losing capture";
            case "UNCLEAR" -> "unclear";
            default -> cause.toLowerCase(Locale.ROOT);
        };
    }
}
