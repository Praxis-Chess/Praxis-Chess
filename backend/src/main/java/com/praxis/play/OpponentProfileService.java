package com.praxis.play;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.prax.intelligence.ChessIntelligence;
import com.praxis.report.ReportFacts;
import com.praxis.report.Thresholds;
import com.praxis.repository.PlayerPatternRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the opponent out of the player's own measured history.
 *
 * This is the difference between this feature and a chess bot. Every field
 * below comes from a count in the database, and nothing is invented: when there
 * is not enough data to justify a choice, the profile says so instead of
 * guessing.
 *
 * Two kinds of fact, kept apart because only one of them changes how the
 * opponent plays (PLAY_PLAN.md, finding 15):
 *
 * - what the opponent DOES: the opening it steers toward (its first moves, from
 *   a book) and its strength;
 * - what to WATCH FOR: the phase and cause the Pattern Report found. The
 *   opponent does not aim at these; they are shown so the player knows them.
 *
 * Both read the stored Pattern Report, so Play, the report and the Training
 * Plan name the same opening from the same numbers (decision P2). "Weakest
 * opening" is the report's: the one most above your own average of serious
 * mistakes per game, with at least {@link Thresholds#MIN_GAMES} games and
 * {@link Thresholds#MIN_MISTAKES} serious mistakes behind it.
 */
@Service
public class OpponentProfileService {

    private static final Logger log = LoggerFactory.getLogger(OpponentProfileService.class);

    /** Below this an opening or a colour cannot justify anything (the games threshold). */
    static final int MIN_GAMES_FOR_TARGET = Thresholds.MIN_GAMES;

    /** Never fully random, never full strength: both make the game pointless. */
    static final int MIN_SKILL = 1;
    static final int MAX_SKILL = 14;

    /** Stockfish's UCI "Skill Level" scale. */
    static final int SCALE = 20;

    private final ChessIntelligence intel;
    private final PlayerPatternRepository reports;
    private final ObjectMapper objectMapper;

    public OpponentProfileService(ChessIntelligence intel, PlayerPatternRepository reports, ObjectMapper objectMapper) {
        this.intel = intel;
        this.reports = reports;
        this.objectMapper = objectMapper;
    }

    /**
     * @param skillLevel        Stockfish skill, calibrated to the player
     * @param targetEco         opening to steer toward, or null when none qualifies
     * @param targetOpening     its human name
     * @param targetPhase       the report's worst phase, or null (watch for; not targeted)
     * @param targetMotif       the report's top proven tactic, or null (watch for; not targeted)
     * @param targetedWeakness  short label persisted with the game
     * @param rationale         short "Label: facts" lines, for any reader of this record
     * @param personalised      false when there is not enough history to shape anything
     * @param opening           the opening and the report's figures for it; null when none qualifies
     * @param skill             the strength, its scale, and how it was set
     * @param colour            the weaker colour and the figures that decided it
     * @param watchFor          the report's worst phase and top cause and tactic; null without a report
     */
    public record OpponentProfile(
            int skillLevel,
            String targetEco,
            String targetOpening,
            String targetPhase,
            String targetMotif,
            String targetedWeakness,
            List<String> rationale,
            boolean personalised,
            Opening opening,
            Skill skill,
            Colour colour,
            WatchFor watchFor
    ) {}

    /** The report's figures for the opening it ranks first: serious mistakes per game, against your average. */
    public record Opening(String eco, String name, int games, int serious, double seriousPerGame,
                          double average, double vsAverage) {}

    /**
     * @param calibrated the level your accuracy maps to before the 1–14 clamp; null without accuracy
     * @param capped     the clamp changed it: the level shown is a limit, not the calibration
     * @param requested  the player chose this level
     */
    public record Skill(int level, int scale, int min, int max, Integer calibrated, boolean capped,
                        boolean requested, Double accuracy) {}

    /** @param winPct 0–100 */
    public record ColourStats(int games, Double accuracy, double winPct) {}

    /**
     * @param basis how the weaker colour was decided: "accuracy", "win rate", or
     *              "too few games" (then White, and nothing is claimed)
     */
    public record Colour(String weaker, String basis, int minGames, ColourStats white, ColourStats black) {}

    public record Phase(String key, String label, int count, int moves, double perHundred, double overall) {}

    public record Cause(String key, String title, int count, int base, int pct) {}

    /** What the Pattern Report found; the opponent does not aim at it. */
    public record WatchFor(Phase phase, Cause cause, Cause tactic) {}

    /**
     * Accuracy in, Stockfish skill out.
     *
     * Being beaten 0-20 measures nothing, and neither does winning every game.
     * The mapping is deliberately gentle and clamped well below full strength:
     * the opponent exists to apply pressure, not to demonstrate that Stockfish
     * is better at chess.
     */
    static int calibrateSkill(Double avgAccuracy) {
        Integer raw = rawSkill(avgAccuracy);
        return raw == null ? 5 : clampSkill(raw);
    }

    /** The level an accuracy maps to, before the clamp. */
    static Integer rawSkill(Double avgAccuracy) {
        return avgAccuracy == null ? null : (int) Math.round((avgAccuracy - 45.0) / 2.5);
    }

    static int clampSkill(int skill) {
        return Math.max(MIN_SKILL, Math.min(MAX_SKILL, skill));
    }

    public OpponentProfile build(String username, Integer requestedSkill) {
        Map<String, Object> profile = intel.playerProfile(username);
        int analysed = asInt(profile.get("analyzedGames"));
        Colour colour = colour(profile);

        if (analysed == 0) {
            int level = requestedSkill != null ? clampSkill(requestedSkill) : 5;
            return new OpponentProfile(level, null, null, null, null, null,
                    List.of("No analysed games yet: a default opponent, engine skill " + level + " of " + SCALE),
                    false, null,
                    new Skill(level, SCALE, MIN_SKILL, MAX_SKILL, null, false, requestedSkill != null, null),
                    colour, null);
        }

        // No top-level accuracy exists: playerProfile reports it per colour, so
        // combine the two weighted by games actually played as each.
        Double avgAccuracy = weightedAccuracy(profile);
        Integer raw = rawSkill(avgAccuracy);
        int level = requestedSkill != null ? clampSkill(requestedSkill) : calibrateSkill(avgAccuracy);
        Skill skill = new Skill(level, SCALE, MIN_SKILL, MAX_SKILL, raw,
                requestedSkill == null && raw != null && raw != level, requestedSkill != null, avgAccuracy);

        ReportFacts facts = latestReport(username);
        Opening opening = opening(facts);
        WatchFor watch = watchFor(facts);

        List<String> why = new ArrayList<>();
        why.add(opening != null
                ? String.format("Steers toward: %s · %.1f serious mistakes per game vs your %.1f · %d games",
                        opening.name(), opening.seriousPerGame(), opening.average(), opening.games())
                : "Steers toward: no opening, none is far enough above your average");
        why.add(skill.capped()
                ? String.format("Strength: engine skill %d of %d, the %s; your %.0f%% average maps to %d",
                        level, SCALE, raw > level ? "cap" : "lowest it goes", avgAccuracy, raw)
                : skill.requested()
                        ? String.format("Strength: engine skill %d of %d, your choice", level, SCALE)
                        : String.format("Strength: engine skill %d of %d, set from your %.0f%% average accuracy",
                                level, SCALE, avgAccuracy == null ? 0.0 : avgAccuracy));
        if (watch != null && watch.phase() != null) {
            why.add(String.format("Watch for: %s · %.1f vs %.1f per 100 moves",
                    watch.phase().label(), watch.phase().perHundred(), watch.phase().overall()));
        }

        String weakness = opening != null ? "opening:" + opening.eco()
                : watch != null && watch.phase() != null ? "phase:" + watch.phase().key()
                : null;

        return new OpponentProfile(
                level,
                opening == null ? null : opening.eco(),
                opening == null ? null : opening.name(),
                watch == null || watch.phase() == null ? null : watch.phase().key(),
                watch == null || watch.tactic() == null ? null : watch.tactic().key(),
                weakness,
                List.copyOf(why),
                true,
                opening, skill, colour, watch);
    }

    /** The stored Pattern Report's facts, or null before the first report (or one in an older shape). */
    private ReportFacts latestReport(String username) {
        return reports.findTopByUsernameOrderByComputedAtDesc(username)
                .map(p -> {
                    if (p.getFactsJson() == null) return null;
                    try {
                        return objectMapper.readValue(p.getFactsJson(), ReportFacts.class);
                    } catch (Exception unreadable) {
                        log.warn("[play] stored report for {} could not be read: {}", username, unreadable.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    /** The report's first opening above your average; its rule already holds both thresholds. */
    static Opening opening(ReportFacts f) {
        if (f == null || f.openings() == null || f.openings().isEmpty()) return null;
        ReportFacts.OpeningRow o = f.openings().get(0);
        if (o.eco() == null || o.eco().isBlank()) return null;   // nothing to steer toward
        return new Opening(o.eco(), o.name(), o.games(), o.serious(), o.seriousPerGame(), f.perGame(), o.vsAverage());
    }

    /** The report's worst phase (only when enough is behind it), and its top cause and tactic. */
    static WatchFor watchFor(ReportFacts f) {
        if (f == null) return null;
        Phase phase = null;
        if (f.worstPhase() != null && f.byPhase() != null && f.playerMoves() > 0) {
            double overall = Math.round(1000.0 * f.serious() / f.playerMoves()) / 10.0;
            phase = f.byPhase().stream()
                    .filter(r -> f.worstPhase().equals(r.key()) && r.enough())
                    .findFirst()
                    .map(r -> new Phase(r.key(), r.label(), r.count(), r.moves(), r.perHundred(), overall))
                    .orElse(null);
        }
        Cause cause = f.topCauses() == null || f.topCauses().isEmpty() ? null : cause(f.topCauses().get(0));
        Cause tactic = f.topTactics() == null || f.topTactics().isEmpty() ? null : cause(f.topTactics().get(0));
        if (phase == null && cause == null && tactic == null) return null;
        return new WatchFor(phase, cause, tactic);
    }

    private static Cause cause(ReportFacts.Weakness w) {
        return new Cause(w.key(), w.title(), w.count(), w.base(), w.pct());
    }

    /**
     * The colour the player performs worse in, and why.
     *
     * Accuracy first, win rate as the tie-break, and only when both colours have
     * enough games: otherwise this would hand someone their "weak" colour on the
     * strength of two games, so it says "too few games" and plays White.
     */
    Colour colour(Map<String, Object> profile) {
        ChessIntelligence.ColorStats w = colourStats(profile, "white");
        ChessIntelligence.ColorStats b = colourStats(profile, "black");
        ColourStats white = stats(w), black = stats(b);
        if (w == null || b == null || w.games() < MIN_GAMES_FOR_TARGET || b.games() < MIN_GAMES_FOR_TARGET) {
            return new Colour("white", "too few games", MIN_GAMES_FOR_TARGET, white, black);
        }
        if (w.avgAccuracy() != null && b.avgAccuracy() != null
                && Math.abs(w.avgAccuracy() - b.avgAccuracy()) >= 1.0) {
            return new Colour(w.avgAccuracy() < b.avgAccuracy() ? "white" : "black", "accuracy",
                    MIN_GAMES_FOR_TARGET, white, black);
        }
        return new Colour(w.winPct() <= b.winPct() ? "white" : "black", "win rate", MIN_GAMES_FOR_TARGET, white, black);
    }

    /** The colour the player performs worse in, or "white" when it cannot be told. */
    public String weakerColour(String username) {
        return colour(intel.playerProfile(username)).weaker();
    }

    private static ColourStats stats(ChessIntelligence.ColorStats c) {
        return c == null ? new ColourStats(0, null, 0) : new ColourStats(c.games(), c.avgAccuracy(),
                Math.round(c.winPct() * 1000) / 10.0);
    }

    private static ChessIntelligence.ColorStats colourStats(Map<String, Object> profile, String key) {
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

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
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
}
