package com.praxis.play;

import com.praxis.prax.intelligence.ChessIntelligence;
import com.praxis.prax.intelligence.ChessIntelligence.MotifStat;
import com.praxis.prax.intelligence.ChessIntelligence.OpeningStat;
import com.praxis.prax.intelligence.ChessIntelligence.PhaseStat;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the opponent out of the player's own measured history.
 *
 * This is the difference between this feature and a chess bot. Every field
 * below comes from a count in the database, and the rationale shown to the
 * player quotes those counts — the same discipline as the Prax evidence layer.
 * Nothing here is invented, and when there is not enough data to justify a
 * choice the profile says so instead of guessing.
 */
@Service
public class OpponentProfileService {

    /**
     * Below this an opening's win rate is noise, so it cannot justify steering a
     * whole game. Mirrors Evidence.MIN_SAMPLE in the reasoning layer.
     */
    static final int MIN_GAMES_FOR_TARGET = 5;

    /** Never fully random, never full strength: both make the game pointless. */
    static final int MIN_SKILL = 1;
    static final int MAX_SKILL = 14;

    private final ChessIntelligence intel;

    public OpponentProfileService(ChessIntelligence intel) {
        this.intel = intel;
    }

    /**
     * @param skillLevel        Stockfish skill, calibrated to the player
     * @param targetEco         opening to steer toward, or null when unjustified
     * @param targetOpening     its human name
     * @param targetPhase       weakest game phase, or null
     * @param targetMotif       most frequent blunder motif, or null
     * @param targetedWeakness  short label persisted with the game
     * @param rationale         player-facing sentences, built from real counts
     * @param personalised      false when there is not enough history to shape anything
     */
    public record OpponentProfile(
            int skillLevel,
            String targetEco,
            String targetOpening,
            String targetPhase,
            String targetMotif,
            String targetedWeakness,
            List<String> rationale,
            boolean personalised
    ) {}

    public OpponentProfile build(String username, Integer requestedSkill) {
        Map<String, Object> profile = intel.playerProfile(username);
        int analysed = asInt(profile.get("analyzedGames"));

        if (analysed == 0) {
            return new OpponentProfile(
                    requestedSkill != null ? clampSkill(requestedSkill) : 5,
                    null, null, null, null, null,
                    List.of("No analysed games yet, so this opponent is not shaped by your play. "
                            + "Sync and analyse some games and it will be."),
                    false);
        }

        // No top-level accuracy exists: playerProfile reports it per colour, so
        // combine the two weighted by games actually played as each.
        Double avgAccuracy = weightedAccuracy(profile);
        int skill = requestedSkill != null ? clampSkill(requestedSkill) : calibrateSkill(avgAccuracy);

        OpeningStat weakOpening = weakestOpening(username);
        PhaseStat weakPhase = weakestPhase(username);
        MotifStat topMotif = topMotif(username);

        List<String> why = new java.util.ArrayList<>();

        // Each line is "Label: facts" (the page shows the label as a label): the
        // numbers stay, the sentence around them goes.
        if (weakOpening != null) {
            why.add(String.format(
                    "Steers toward: %s · %.0f%% accuracy in %d games, your weakest opening with enough games to judge",
                    displayName(weakOpening),
                    weakOpening.avgAccuracy() == null ? 0.0 : weakOpening.avgAccuracy(), weakOpening.games()));
        } else {
            why.add("Steers toward: no opening yet, none has enough games to judge");
        }

        if (weakPhase != null) {
            why.add(String.format("Most blunders: %s · %d of them",
                    weakPhase.phase().toLowerCase(), weakPhase.blunders()));
        }
        if (topMotif != null) {
            why.add(String.format("Most frequent tactic: %s · %d times",
                    humanMotif(topMotif.motif()).toLowerCase(), topMotif.count()));
        }

        why.add(avgAccuracy == null
                ? String.format("Strength: %d of 20", skill)
                : String.format("Strength: %d of 20 · calibrated to your %.0f%% average accuracy", skill, avgAccuracy));

        String weakness = weakOpening != null ? "opening:" + weakOpening.eco()
                : weakPhase != null ? "phase:" + weakPhase.phase()
                : topMotif != null ? "motif:" + topMotif.motif()
                : null;

        return new OpponentProfile(
                skill,
                weakOpening == null ? null : weakOpening.eco(),
                weakOpening == null ? null : displayName(weakOpening),
                weakPhase == null ? null : weakPhase.phase(),
                topMotif == null ? null : topMotif.motif(),
                weakness,
                List.copyOf(why),
                true);
    }

    /**
     * Accuracy in, Stockfish skill out.
     *
     * Being beaten 0-20 measures nothing, and neither does winning every game.
     * The mapping is deliberately gentle and clamped well below full strength:
     * the opponent exists to apply pressure, not to demonstrate that Stockfish
     * is better at chess.
     */
    static int calibrateSkill(Double avgAccuracy) {
        if (avgAccuracy == null) return 5;
        int skill = (int) Math.round((avgAccuracy - 45.0) / 2.5);
        return clampSkill(skill);
    }

    static int clampSkill(int skill) {
        return Math.max(MIN_SKILL, Math.min(MAX_SKILL, skill));
    }

    /** Lowest accuracy among openings with enough games to mean anything. */
    private OpeningStat weakestOpening(String username) {
        List<OpeningStat> stats = intel.openingPerformance(username, null, null, MIN_GAMES_FOR_TARGET);
        return stats.stream()
                .filter(o -> o.games() >= MIN_GAMES_FOR_TARGET)
                .filter(o -> o.avgAccuracy() != null)
                .min(Comparator.comparingDouble(OpeningStat::avgAccuracy))
                .orElse(null);
    }

    private PhaseStat weakestPhase(String username) {
        return intel.phasePerformance(username, null, null).stream()
                .filter(p -> p.errors() > 0)
                .max(Comparator.comparingInt(PhaseStat::blunders))
                .orElse(null);
    }

    /** "No tactic found" categories: counted elsewhere, never named as a pattern (as in the Pattern Report). */
    static final java.util.Set<String> NOT_A_TACTIC = java.util.Set.of("POSITIONAL", "OTHER");

    /** The most frequent tactic the board proved; positional and other mean none was. */
    private MotifStat topMotif(String username) {
        return intel.mistakePatterns(username, null, null, null, 10).stream()
                .filter(m -> m.motif() != null && !NOT_A_TACTIC.contains(m.motif()))
                .findFirst().orElse(null);
    }

    private static String displayName(OpeningStat o) {
        return o.name() != null && !o.name().isBlank() ? o.name() : o.eco();
    }

    static String humanMotif(String motif) {
        if (motif == null) return "an unclassified pattern";
        String[] parts = motif.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(p.charAt(0)).append(p.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /**
     * The colour the player performs worse in, or "white" when it cannot be told.
     *
     * Accuracy first, win rate as the tie-break, and only when both colours have
     * enough games — otherwise this would hand someone their "weak" colour on the
     * strength of two games.
     */
    public String weakerColour(String username) {
        Map<String, Object> profile = intel.playerProfile(username);
        ChessIntelligence.ColorStats w = colour(profile, "white");
        ChessIntelligence.ColorStats b = colour(profile, "black");
        if (w == null || b == null) return "white";
        if (w.games() < MIN_GAMES_FOR_TARGET || b.games() < MIN_GAMES_FOR_TARGET) return "white";

        if (w.avgAccuracy() != null && b.avgAccuracy() != null
                && Math.abs(w.avgAccuracy() - b.avgAccuracy()) >= 1.0) {
            return w.avgAccuracy() < b.avgAccuracy() ? "white" : "black";
        }
        return w.winPct() <= b.winPct() ? "white" : "black";
    }

    private static ChessIntelligence.ColorStats colour(Map<String, Object> profile, String key) {
        return profile.get(key) instanceof ChessIntelligence.ColorStats cs ? cs : null;
    }

    /** White and black accuracy, weighted by how many games were played as each. */
    private static Double weightedAccuracy(Map<String, Object> profile) {
        double sum = 0, n = 0;
        for (String colour : new String[]{"white", "black"}) {
            if (profile.get(colour) instanceof ChessIntelligence.ColorStats cs
                    && cs.avgAccuracy() != null && cs.games() > 0) {
                sum += cs.avgAccuracy() * cs.games();
                n += cs.games();
            }
        }
        return n == 0 ? null : sum / n;
    }
}
