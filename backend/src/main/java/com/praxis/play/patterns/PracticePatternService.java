package com.praxis.play.patterns;

import com.praxis.config.AppProperties;
import com.praxis.domain.Game;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.play.domain.PracticeGame;
import com.praxis.play.repository.PracticeGameRepository;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.service.analysis.ParsedGame;
import com.praxis.service.analysis.PgnParserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Recurring tendencies across the player's recent practice games.
 *
 * Computed on read, which is only defensible because the window is capped
 * (§5.1 of PRACTICE_PATTERNS_PLAN.md). An open-ended population would make this
 * linear in total history; twenty games is constant work forever, and is also
 * the better claim — a habit fixed two hundred games ago is not a current one.
 */
@Service
public class PracticePatternService {

    private static final Logger log = LoggerFactory.getLogger(PracticePatternService.class);

    /**
     * How far back "recently" reaches. Distinct from MIN_SAMPLE, which decides
     * whether there is enough to speak at all — this decides what counts as now.
     * Deliberately not ImprovementService.WINDOW (5), which answers a third
     * question: what to compare a single new game against.
     */
    public static final int WINDOW = 20;

    private static final String UNTIMED_NOTE =
            "Time and decision-making — practice games are untimed, so no clock is "
          + "recorded and none can be reconstructed.";

    private final PracticeGameRepository practiceGames;
    private final GameRepository games;
    private final MoveErrorRepository moveErrors;
    private final PgnParserService pgnParser;
    private final AppProperties props;
    private final List<PatternDetector> detectors;

    public PracticePatternService(PracticeGameRepository practiceGames, GameRepository games,
                                  MoveErrorRepository moveErrors, PgnParserService pgnParser,
                                  AppProperties props, List<PatternDetector> detectors) {
        this.practiceGames = practiceGames;
        this.games = games;
        this.moveErrors = moveErrors;
        this.pgnParser = pgnParser;
        this.props = props;
        this.detectors = detectors;
    }

    @Transactional(readOnly = true)
    public PracticePatternReport report() {
        String username = props.chessCom().username();
        List<AnalysedGame> window = window(username);

        List<String> notMeasured = List.of(UNTIMED_NOTE);

        if (window.size() < PerGameDetector.MIN_SAMPLE) {
            return new PracticePatternReport(
                    window.size(), false,
                    caveatForSmallSample(window.size()),
                    openingCaveat(window),
                    List.of(), notMeasured);
        }

        List<PracticePattern> found = new ArrayList<>();
        for (PatternDetector detector : detectors) {
            try {
                detector.detect(window).ifPresent(found::add);
            } catch (Exception e) {
                // One bad detector must not take the report with it. A missing
                // card is a smaller lie than a 500 on a page of true ones.
                log.warn("[patterns] detector {} failed: {}", detector.id(), e.getMessage(), e);
            }
        }

        return new PracticePatternReport(
                window.size(), true, null,
                openingCaveat(window),
                List.copyOf(found), notMeasured);
    }

    /**
     * The games patterns are computed over: rated, finished, archived AND
     * measured, newest first, capped at WINDOW.
     *
     * A game archived but not analysed has a PGN but no mistakes, so PGN
     * detectors would judge it while mistake detectors could not — two
     * denominators on one screen that do not add up. Excluding it is simpler
     * and honest.
     */
    private List<AnalysedGame> window(String username) {
        List<PracticeGame> candidates = practiceGames.findRatedFinished(username);

        List<AnalysedGame> out = new ArrayList<>();
        for (PracticeGame pg : candidates) {
            if (out.size() >= WINDOW) break;
            assemble(pg).ifPresent(out::add);
        }
        return out;
    }

    private Optional<AnalysedGame> assemble(PracticeGame pg) {
        if (pg.getGameId() == null) return Optional.empty();

        Game game = games.findById(pg.getGameId()).orElse(null);
        if (game == null) return Optional.empty();
        if (game.getAnalysisStatus() != AnalysisStatus.ANALYZED) return Optional.empty();
        if (game.getRawPgn() == null || game.getRawPgn().isBlank()) return Optional.empty();

        // Same arguments the analysis pipeline used. configuredUsername decides
        // which side is the player, so parsing with anything else would label
        // moves for one colour while the stored MoveError rows describe the other.
        ParsedGame parsed = pgnParser.parse(
                game.getId().toString(), game.getRawPgn(), props.chessCom().username());
        if (parsed.moves().isEmpty()) return Optional.empty();

        return Optional.of(new AnalysedGame(
                pg, game, parsed, moveErrors.findByGameId(game.getId())));
    }

    private static String caveatForSmallSample(int n) {
        if (n == 0) return "No analysed practice games yet — play one and this fills in.";
        return "Only " + n + " analysed practice game" + (n == 1 ? "" : "s")
             + " so far. Here is what they show, but "
             + PerGameDetector.MIN_SAMPLE + " are needed before calling anything a tendency.";
    }

    /**
     * The opponent steers to the player's weakest opening, so a window is
     * usually all one ECO — and then a habit and an unfamiliar opening look
     * identical from here. Said out loud rather than left for the reader to
     * notice.
     */
    private static String openingCaveat(List<AnalysedGame> window) {
        if (window.isEmpty()) return null;

        Set<String> openings = new LinkedHashSet<>();
        for (AnalysedGame g : window) {
            String name = g.game().getOpeningName() != null
                    ? g.game().getOpeningName() : g.game().getOpeningEco();
            if (name != null && !name.isBlank()) openings.add(name);
        }
        if (openings.size() != 1) return null;

        return "Every one of these games is the " + openings.iterator().next()
             + ", because your opponent is built to play your weakest opening. "
             + "Some of what follows may be specific to that opening rather than "
             + "a habit you carry everywhere.";
    }
}
