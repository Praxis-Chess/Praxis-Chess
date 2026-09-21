package com.praxis.play.patterns;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A detector that asks the same question of each game independently.
 *
 * Subclasses answer only "did this game show it?"; recurrence, strength banding
 * and the decision to speak at all are applied here. That keeps one copy of the
 * rule — six detectors each re-deriving "≥3 of N" would eventually be five
 * detectors and one that drifted.
 */
public abstract class PerGameDetector implements PatternDetector {

    /** A tendency seen twice is a thing that happened twice, not a pattern. */
    public static final int MIN_RECURRENCE = 3;

    /** Below this many games the report names no tendency at all. */
    public static final int MIN_SAMPLE = 5;

    /** Enough games to state a tendency plainly rather than hedge. */
    public static final int ESTABLISHED_AT = 10;

    /**
     * Did this game show the tendency?
     *
     * @return the occurrence to cite, or empty. A game that could not be judged
     *         at all — too short to have an opening, say — should also return
     *         empty AND be excluded via {@link #judgeable(AnalysedGame)}, so it
     *         does not silently count as evidence against.
     */
    protected abstract Optional<Occurrence> examine(AnalysedGame game);

    /**
     * Whether this game can be judged by this detector at all.
     *
     * Distinct from "did not show the tendency": a ten-ply game did not fail to
     * castle, it never reached the point where castling was the question. Such a
     * game must leave the denominator, or every short game quietly becomes
     * evidence of good habits.
     */
    protected boolean judgeable(AnalysedGame game) {
        return true;
    }

    /** One sentence with the numbers in it. */
    protected abstract String finding(int affected, int considered, List<Occurrence> occurrences);

    @Override
    public Optional<PracticePattern> detect(List<AnalysedGame> window) {
        List<AnalysedGame> judged = window.stream().filter(this::judgeable).toList();
        if (judged.size() < MIN_SAMPLE) return Optional.empty();

        List<Occurrence> hits = new ArrayList<>();
        for (AnalysedGame game : judged) {
            examine(game).ifPresent(hits::add);
        }
        if (hits.size() < MIN_RECURRENCE) return Optional.empty();

        return Optional.of(new PracticePattern(
                id(), title(),
                finding(hits.size(), judged.size(), hits),
                why(), whatToDo(),
                strengthFor(judged.size()),
                hits.size(), judged.size(),
                drillMotif(),
                List.copyOf(hits)));
    }

    /** "4 of 6 games" / "1 of 6 games" — used in most findings. */
    public static String ratio(int affected, int considered) {
        return affected + " of " + considered + (considered == 1 ? " game" : " games");
    }

    /**
     * The strength band for a given window size. Public so detectors that build
     * their own pattern rather than going through {@link #detect} band it the
     * same way — two rules for one voice is how a UI starts contradicting itself.
     */
    public static Strength strengthFor(int gamesConsidered) {
        return gamesConsidered >= ESTABLISHED_AT ? Strength.ESTABLISHED : Strength.EMERGING;
    }
}
