package com.praxis.service.ai;

import com.praxis.domain.MoveError;
import com.praxis.domain.enums.AnalysisStatus;
import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.dto.TodayInsightDto;
import com.praxis.dto.TodayInsightDto.Evidence;
import com.praxis.repository.GameRepository;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.service.settings.PlayerIdentity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "Today's focus" card: the game phase where the player's worst mistakes
 * happen, stated with the real counts.
 *
 * Deterministic on purpose. A model used to write this card from the same
 * counts plus the most frequent tactical motif. The motif came from unchecked
 * per-move labels (most blunders had none, the rest often tied), and nothing
 * checked the numbers the model wrote into its evidence line. Every word here
 * comes from engine-measured severity and the move's game phase.
 */
@Service
public class TodayInsightService {

    private final MoveErrorRepository moveErrorRepository;
    private final GameRepository gameRepository;
    private final PlayerIdentity player;

    public TodayInsightService(MoveErrorRepository moveErrorRepository,
                               GameRepository gameRepository,
                               PlayerIdentity player) {
        this.moveErrorRepository = moveErrorRepository;
        this.gameRepository = gameRepository;
        this.player = player;
    }

    public TodayInsightDto generate() {
        String username = player.username();
        int analysed = (int) gameRepository.countByUsernameAndAnalysisStatus(username, AnalysisStatus.ANALYZED);
        return build(moveErrorRepository.findAllByUsername(username), analysed);
    }

    /** The card for these mistakes, from {@code analysed} analysed games. */
    static TodayInsightDto build(List<MoveError> errors, int analysed) {
        if (analysed == 0) {
            return new TodayInsightDto("Analyse your games first",
                    new Evidence("Games analysed", "No analysed games yet", 0),
                    "Sync your Chess.com games and run the analysis. Today's focus comes from your own mistakes.", 5);
        }

        // Blunders when there are any; otherwise the next tier down.
        for (Severity tier : List.of(Severity.BLUNDER, Severity.MISTAKE)) {
            Map<GamePhase, Integer> byPhase = new EnumMap<>(GamePhase.class);
            for (GamePhase p : GamePhase.values()) byPhase.put(p, 0);
            int total = 0;
            for (MoveError e : errors) {
                if (e.getSeverity() != tier || e.getGamePhase() == null) continue;
                byPhase.merge(e.getGamePhase(), 1, Integer::sum);
                total++;
            }
            if (total == 0) continue;
            return phaseCard(tier, byPhase, total, analysed);
        }

        return new TodayInsightDto("No blunders or mistakes found",
                new Evidence("Blunders and mistakes", "None in " + analysed + " analysed games", analysed),
                "Play and analyse a few more games. A focus appears once there are mistakes to learn from.", 5);
    }

    private static TodayInsightDto phaseCard(Severity tier, Map<GamePhase, Integer> byPhase, int total, int analysed) {
        int top = byPhase.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        // Ties name every tied phase rather than picking one silently.
        List<String> worst = new ArrayList<>();
        int inWorst = 0;
        for (GamePhase p : GamePhase.values()) {
            if (byPhase.get(p) == top) {
                worst.add(name(p));
                inWorst += top;
            }
        }
        String phases = String.join(" and ", worst);
        String noun = tier == Severity.BLUNDER ? "blunders" : "mistakes";
        int pct = Math.round(100f * inWorst / total);

        String value = inWorst + " of " + total + " " + noun + " in the " + phases + " (" + pct + "%)";
        List<String> others = new ArrayList<>();
        for (GamePhase p : GamePhase.values()) {
            if (byPhase.get(p) != top) others.add(name(p) + " " + byPhase.get(p));
        }
        if (!others.isEmpty()) value += " · " + String.join(" · ", others);

        String title = "Cut your " + phases + " " + noun;
        String action = "In today's session, before each " + phases + " move, ask what your opponent's best reply is.";
        return new TodayInsightDto(title,
                new Evidence(capitalise(noun) + " by game phase", value, analysed), action, 15);
    }

    private static String name(GamePhase p) {
        return p.name().toLowerCase(Locale.ROOT);
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
