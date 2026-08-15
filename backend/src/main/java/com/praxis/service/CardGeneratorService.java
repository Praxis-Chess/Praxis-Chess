package com.praxis.service;

import com.praxis.config.AppProperties;
import com.praxis.domain.Card;
import com.praxis.domain.MoveError;
import com.praxis.domain.enums.CardStatus;
import com.praxis.domain.enums.Severity;
import com.praxis.repository.CardRepository;
import com.praxis.repository.MoveErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Generates and refreshes the Card deck from MoveError rows.
 *
 * Strategy:
 *   - Target ~120 active cards; cap by severity + recency
 *   - Deduplicate by FEN: only one card per unique position
 *   - New cards start with dueDate = today + MIN_FIRST_INTERVAL_DAYS (anti-memorisation:
 *     you can't drill a mistake from your game played today on the same day)
 *   - Blunders contribute more candidates than mistakes/inaccuracies (see SEVERITY_WEIGHT)
 *   - Calling generateForUser is idempotent — existing cards are never deleted,
 *     only new positions are added
 */
@Service
public class CardGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(CardGeneratorService.class);

    private static final int MIN_FIRST_INTERVAL_DAYS = 7;   // anti-memorisation
    private static final int TARGET_ACTIVE_CARDS      = 120;

    private final MoveErrorRepository moveErrorRepository;
    private final CardRepository cardRepository;
    private final AppProperties appProperties;

    public CardGeneratorService(MoveErrorRepository moveErrorRepository,
                                CardRepository cardRepository,
                                AppProperties appProperties) {
        this.moveErrorRepository = moveErrorRepository;
        this.cardRepository = cardRepository;
        this.appProperties = appProperties;
    }

    @Transactional
    public int generateForUser(String username) {
        List<MoveError> candidates = moveErrorRepository.findDrillCandidatesByUsername(username);
        if (candidates.isEmpty()) {
            log.debug("No drill candidates for {}", username);
            return 0;
        }

        long existingCount = cardRepository.countActiveByUsername(username);
        int budget = (int) Math.max(0, TARGET_ACTIVE_CARDS - existingCount);
        if (budget == 0) {
            log.debug("Card deck at target capacity ({}) for {}", TARGET_ACTIVE_CARDS, username);
            return 0;
        }

        // Sort: blunders first, then mistakes, then inaccuracies; within tier by win% drop desc
        candidates.sort(Comparator
                .comparingInt((MoveError e) -> severityWeight(e.getSeverity())).reversed()
                .thenComparingDouble((MoveError e) -> e.getWinPctDrop() != null ? e.getWinPctDrop() : 0.0).reversed());

        int created = 0;
        LocalDate firstDue = LocalDate.now().plusDays(MIN_FIRST_INTERVAL_DAYS);

        for (MoveError error : candidates) {
            if (created >= budget) break;
            String fen = error.getFenPosition();
            if (fen == null || fen.isBlank()) continue;

            // Skip if a card already exists for this position
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

    private int severityWeight(Severity s) {
        if (s == null) return 0;
        return switch (s) {
            case BLUNDER    -> 3;
            case MISTAKE    -> 2;
            case INACCURACY -> 1;
        };
    }
}
