package com.praxis.service;

import com.praxis.config.PraxisClock;
import com.praxis.domain.Card;
import com.praxis.domain.MoveError;
import com.praxis.domain.enums.CardStatus;
import com.praxis.domain.enums.Severity;
import com.praxis.repository.CardRepository;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.repository.TrainingPlanRepository;
import com.praxis.service.drills.DeckRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Generates the review deck from analysed mistakes, by {@link DeckRule}:
 * blunders and mistakes from Chess.com games (never inaccuracies, never practice
 * games), up to about {@link DeckRule#TARGET_ACTIVE_CARDS} active cards. When
 * there are more candidates than room: blunders first, then the Training Plan's
 * focus positions, then the most recent game. A new card can be drilled the day
 * it is created (D8); the session engine introduces at most
 * {@link DeckRule#NEW_PER_DAY} a day.
 *
 * Idempotent: one card per game and ply, and one per position. Existing cards
 * are never deleted. Called after each analysis run, at startup (the backfill),
 * and when a session starts.
 */
@Service
public class CardGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(CardGeneratorService.class);

    private final MoveErrorRepository moveErrorRepository;
    private final CardRepository cardRepository;
    private final TrainingPlanRepository planRepository;
    private final ObjectMapper objectMapper;
    private final PraxisClock clock;

    public CardGeneratorService(MoveErrorRepository moveErrorRepository,
                                CardRepository cardRepository,
                                TrainingPlanRepository planRepository,
                                ObjectMapper objectMapper,
                                PraxisClock clock) {
        this.moveErrorRepository = moveErrorRepository;
        this.cardRepository = cardRepository;
        this.planRepository = planRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public int generateForUser(String username) {
        if (username == null || username.isBlank()) return 0;
        List<MoveError> candidates = new ArrayList<>(moveErrorRepository.findDrillCandidatesByUsername(username).stream()
                .filter(e -> e.getSeverity() == Severity.BLUNDER || e.getSeverity() == Severity.MISTAKE)
                .toList());
        if (candidates.isEmpty()) {
            log.debug("No drill candidates for {}", username);
            return 0;
        }

        long existingCount = cardRepository.countActiveByUsername(username);
        int budget = (int) Math.max(0, DeckRule.TARGET_ACTIVE_CARDS - existingCount);
        if (budget == 0) {
            log.debug("Card deck at target capacity ({}) for {}", DeckRule.TARGET_ACTIVE_CARDS, username);
            return 0;
        }

        // D3b: blunders before mistakes, then the plan's focus positions, then the most recent game.
        Set<String> focus = planFocus(username);
        candidates.sort(Comparator
                .comparingInt((MoveError e) -> e.getSeverity() == Severity.BLUNDER ? 0 : 1)
                .thenComparingInt(e -> focus.contains(key(e)) ? 0 : 1)
                .thenComparing(e -> e.getGame().getPlayedAt(), Comparator.nullsLast(Comparator.reverseOrder())));

        int created = 0;
        LocalDate firstDue = clock.today();   // D8: no wait; NEW_PER_DAY paces introductions

        for (MoveError error : candidates) {
            if (created >= budget) break;
            String fen = error.getFenPosition();
            if (fen == null || fen.isBlank()) continue;
            if (cardRepository.countForGameAndPly(username, error.getGame().getId(), error.getMoveNumber()) > 0) continue;
            if (cardRepository.findByUsernameAndFenPosition(username, fen).isPresent()) continue;

            Card card = Card.builder()
                    .username(username)
                    .sourceError(error)
                    .fenPosition(fen)
                    .status(CardStatus.NEW)
                    .dueDate(firstDue)
                    .build();
            cardRepository.save(card);
            created++;
        }

        log.info("Generated {} new cards for {} (budget was {})", created, username, budget);
        return created;
    }

    private static String key(MoveError e) {
        return e.getGame().getId() + ":" + e.getMoveNumber();
    }

    /**
     * The positions the latest Training Plan drills, as "gameId:ply". Read from
     * the stored plan's JSON, wherever it lists a position (snake_case, as the
     * API writes it); an unreadable or missing plan means no focus.
     */
    private Set<String> planFocus(String username) {
        Set<String> out = new HashSet<>();
        planRepository.findTopByUsernameOrderByGeneratedAtDesc(username).ifPresent(plan -> {
            try {
                collect(objectMapper.readTree(plan.getPlanJson()), out);
            } catch (Exception e) {
                log.debug("Training plan not readable for focus: {}", e.getMessage());
            }
        });
        return out;
    }

    private static void collect(JsonNode node, Set<String> out) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode game = node.has("game_id") ? node.get("game_id") : node.get("gameId");
            JsonNode ply = node.get("ply");
            if (game != null && ply != null && ply.canConvertToInt()) out.add(game.asText() + ":" + ply.asInt());
            node.elements().forEachRemaining(n -> collect(n, out));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(n -> collect(n, out));
        }
    }
}
