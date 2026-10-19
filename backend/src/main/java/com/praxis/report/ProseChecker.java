package com.praxis.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds report prose to the facts it was written from (Phase 12, step 4).
 *
 * <p>Every number in the prose must be a number the facts contain, and every
 * tactic or time-trouble claim must be one the facts support. Anything else is a
 * violation, listed in words a model can act on when it is asked to try again.
 *
 * <p>Deliberately simple and strict: a sentence with no numbers and no tactic
 * names always passes, so the cost of strictness is only that a model must
 * quote, not paraphrase, its figures.
 */
public final class ProseChecker {

    private ProseChecker() {}

    public record Result(boolean passed, List<String> violations) {}

    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])(\\d+(?:\\.\\d+)?)(?!\\w)");

    /** Tactic names a sentence may only use when the facts contain that motif. */
    private static final Map<String, Pattern> MOTIF_WORDS = new LinkedHashMap<>();
    static {
        MOTIF_WORDS.put("FORK", Pattern.compile("\\bfork", Pattern.CASE_INSENSITIVE));
        MOTIF_WORDS.put("PIN", Pattern.compile("\\bpin(s|ned|ning)?\\b", Pattern.CASE_INSENSITIVE));
        MOTIF_WORDS.put("SKEWER", Pattern.compile("\\bskewer", Pattern.CASE_INSENSITIVE));
        MOTIF_WORDS.put("BACK_RANK", Pattern.compile("\\bback[- ]rank", Pattern.CASE_INSENSITIVE));
        MOTIF_WORDS.put("DISCOVERED_ATTACK", Pattern.compile("\\bdiscover(ed|y)", Pattern.CASE_INSENSITIVE));
        MOTIF_WORDS.put("HANGING_PIECE", Pattern.compile("\\b(hanging|hung|en prise)\\b", Pattern.CASE_INSENSITIVE));
    }
    /**
     * A tactic in these facts is one the player fell for or missed: the rules
     * label the move that cost them. "You make forks" says the opposite.
     */
    private static final Pattern PLAYER_MADE_TACTIC = Pattern.compile(
            "\\byou (make|made|play|played|create|created|use|used) (a |an )?"
                    + "(forks?|pins?|skewers?|discovered attacks?|back[- ]rank)",
            Pattern.CASE_INSENSITIVE);
    /** The facts hold no results: prose may not claim an effect on them. */
    private static final Pattern RESULT_WORDS = Pattern.compile(
            "\\b(losses|lost games|losing games|wins|won games|win rate|rating|elo|results)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_WORDS =
            Pattern.compile("\\b(time (trouble|pressure)|clock|seconds left|low on time)\\b", Pattern.CASE_INSENSITIVE);

    public static Result check(String prose, ReportFacts facts) {
        return check(prose, numbers(facts), motifs(facts), timeTroubleSupported(facts));
    }

    /** Plan prose may also quote the plan's own numbers: sessions, minutes, positions, ranks. */
    public static Result checkPlan(String prose, ReportFacts facts, PlanFacts plan) {
        Set<String> numbers = numbers(facts);
        add(numbers, plan.basedOnGames(), plan.sessionsPerWeek(), plan.minutesPerSession());
        for (var p : plan.priorities()) add(numbers, p.rank(), p.positions());
        if (plan.pattern() != null) add(numbers, plan.pattern().positions());
        for (var x : plan.sessions()) add(numbers, x.number(), x.positions().size());
        for (var o : plan.openingsToReview()) {
            add(numbers, o.games(), o.serious(), o.blunders());
            numbers.add(normalise(Double.toString(o.seriousPerGame())));
        }
        return check(prose, numbers, motifs(facts), timeTroubleSupported(facts));
    }

    static Result check(String prose, Set<String> numbers, Set<String> motifs, boolean timeTrouble) {
        List<String> violations = new ArrayList<>();
        if (prose == null || prose.isBlank()) return new Result(true, violations);

        Set<String> unknown = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(prose);
        while (m.find()) {
            String n = normalise(m.group(1));
            if (!numbers.contains(n)) unknown.add(m.group(1));
        }
        for (String n : unknown) violations.add("the number " + n + " is not in the facts");

        MOTIF_WORDS.forEach((motif, words) -> {
            if (words.matcher(prose).find() && !motifs.contains(motif)) {
                violations.add("it names " + motif.toLowerCase(Locale.ROOT).replace('_', ' ')
                        + ", which the facts do not contain");
            }
        });
        if (PLAYER_MADE_TACTIC.matcher(prose).find()) {
            violations.add("it says you made the tactic; these are tactics that caught you or that you missed");
        }
        if (RESULT_WORDS.matcher(prose).find()) {
            violations.add("it talks about results or rating, which the facts do not contain");
        }
        if (!timeTrouble && TIME_WORDS.matcher(prose).find()) {
            violations.add("it mentions time trouble, which the facts do not show");
        }
        return new Result(violations.isEmpty(), violations);
    }

    /** Every number the facts state, as text: "75", "2.3", plus the move ranges and the 30-second line. */
    static Set<String> numbers(ReportFacts f) {
        Set<String> out = new LinkedHashSet<>();
        add(out, f.gamesAnalysed(), f.mistakes(), f.blunders(), f.seriousMistakes(), f.inaccuracies(),
                f.serious(), f.diagnosed(), f.diagnosedSerious(), f.playerMoves(), f.noNamedTactic(),
                f.noCause(), f.minSample());
        for (var r : f.byPhase()) {
            add(out, r.count(), r.blunders(), r.moves());
            out.add(normalise(Double.toString(r.perHundred())));
        }
        for (var r : f.byMoveRange()) {
            add(out, r.count(), r.blunders(), r.moves());
            out.add(normalise(Double.toString(r.perHundred())));
        }
        for (var c : f.causes()) add(out, c.count(), c.blunders(), c.pct());
        for (var c : f.tactics()) add(out, c.count(), c.blunders(), c.pct());
        add(out, 1, 10, 11, 20, 21, 30, 31, 100, ReportFactsBuilder.TIME_PRESSURE_SECONDS);
        var tp = f.timePressure();
        add(out, tp.withClock(), tp.underThirty(), tp.blundersUnderThirty(), tp.pct());
        out.add(normalise(Double.toString(f.perGame())));
        for (var o : f.openings()) {
            add(out, o.games(), o.serious(), o.blunders());
            out.add(normalise(Double.toString(o.seriousPerGame())));
        }
        for (var w : weaknesses(f)) {
            add(out, w.count(), w.base(), w.pct(), w.blunders(), w.lost(), (int) Math.round(w.avgLost()));
            out.add(normalise(Double.toString(w.avgLost())));
            for (var e : w.positions()) add(out, (e.ply() + 1) / 2, (int) Math.round(e.lost()));
        }
        return out;
    }

    static List<ReportFacts.Weakness> weaknesses(ReportFacts f) {
        List<ReportFacts.Weakness> all = new ArrayList<>(f.topCauses());
        all.addAll(f.topTactics());
        return all;
    }

    static Set<String> motifs(ReportFacts f) {
        Set<String> out = new LinkedHashSet<>();
        for (var c : f.tactics()) if (c.count() > 0) out.add(c.key());
        return out;
    }

    static boolean timeTroubleSupported(ReportFacts f) {
        return f.timePressure().underThirty() > 0;
    }

    private static void add(Set<String> out, int... values) {
        for (int v : values) out.add(Integer.toString(v));
    }

    /** "2.0" and "2" are the same number; "07" is "7". */
    static String normalise(String n) {
        return new java.math.BigDecimal(n).stripTrailingZeros().toPlainString();
    }
}
