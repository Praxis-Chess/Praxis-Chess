package com.praxis.service.drills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.config.AppProperties;
import com.praxis.domain.Attempt;
import com.praxis.domain.Card;
import com.praxis.domain.DrillSession;
import com.praxis.domain.MetricSnapshot;
import com.praxis.domain.enums.AttemptRating;
import com.praxis.domain.enums.CardStatus;
import com.praxis.domain.enums.GamePhase;
import com.praxis.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Session engine: composes, runs, resumes, and completes drill sessions.
 *
 * Session composition (per session, ~20 min / ~55 s per card ≈ 22 cards):
 *   60% due reviews  — overdue cards, oldest first
 *   30% new cards    — newest blunders first (from CardGeneratorService order)
 *   10% stretch      — cards due in next 3 days (early retrieval practice)
 *
 * Anti-memorisation (sibling rotation):
 *   Cards sharing the same (motif + phase) as the immediately previous card are
 *   deferred to the end of the queue so the user can't pattern-match across siblings.
 *
 * Resumption:
 *   An incomplete session is returned as-is from findOrCreate so the user picks up
 *   where they left off.
 */
@Service
public class SessionEngineService {

    private static final Logger log = LoggerFactory.getLogger(SessionEngineService.class);

    // Session budget
    private static final int BUDGET_MINUTES    = 20;
    private static final int SECONDS_PER_CARD  = 55;
    private static final int MAX_SESSION_CARDS = BUDGET_MINUTES * 60 / SECONDS_PER_CARD; // ≈ 21

    // Composition ratios
    private static final double RATIO_DUE     = 0.60;
    private static final double RATIO_NEW     = 0.30;
    private static final double RATIO_STRETCH = 0.10;

    private final CardRepository cardRepository;
    private final DrillSessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final MetricSnapshotRepository snapshotRepository;
    private final SchedulerStrategy scheduler;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public SessionEngineService(CardRepository cardRepository,
                                DrillSessionRepository sessionRepository,
                                AttemptRepository attemptRepository,
                                MetricSnapshotRepository snapshotRepository,
                                SchedulerStrategy scheduler,
                                AppProperties appProperties,
                                ObjectMapper objectMapper) {
        this.cardRepository = cardRepository;
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.snapshotRepository = snapshotRepository;
        this.scheduler = scheduler;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    // --- Session lifecycle ---

    @Transactional
    public DrillSession findOrCreateSession(String username) {
        // Resume an existing incomplete session
        return sessionRepository.findLatestIncomplete(username)
                .orElseGet(() -> createSession(username));
    }

    @Transactional
    public DrillSession createSession(String username) {
        LocalDate today = LocalDate.now();
        List<Card> due     = cardRepository.findDueCards(username, today);
        List<Card> newCards = cardRepository.findByUsernameAndStatusOrderByCreatedAtAsc(username, CardStatus.NEW);
        // Stretch: cards due in next 3 days
        LocalDate stretchHorizon = today.plusDays(3);
        List<Card> stretch = cardRepository.findDueCards(username, stretchHorizon).stream()
                .filter(c -> c.getDueDate() != null && c.getDueDate().isAfter(today))
                .toList();

        int nDue     = (int) Math.round(MAX_SESSION_CARDS * RATIO_DUE);
        int nNew     = (int) Math.round(MAX_SESSION_CARDS * RATIO_NEW);
        int nStretch = MAX_SESSION_CARDS - nDue - nNew;

        List<Card> queue = new ArrayList<>();
        queue.addAll(take(due,      nDue));
        queue.addAll(take(newCards, nNew));
        queue.addAll(take(stretch,  nStretch));

        // Sibling rotation: shuffle so same (motif+phase) cards are not adjacent
        queue = siblingRotate(queue);

        List<UUID> ids = queue.stream().map(Card::getId).toList();
        String queueJson = toJson(ids);

        DrillSession session = DrillSession.builder()
                .username(username)
                .cardQueue(queueJson)
                .budgetMinutes(BUDGET_MINUTES)
                .cardsTotal(ids.size())
                .build();
        sessionRepository.save(session);
        log.info("Created session for {}: {} cards ({} due, {} new, {} stretch)",
                username, ids.size(),
                Math.min(due.size(), nDue),
                Math.min(newCards.size(), nNew),
                Math.min(stretch.size(), nStretch));
        return session;
    }

    /**
     * Record an attempt and advance the card's FSRS state.
     *
     * @param sessionId  the active session
     * @param cardId     the card being rated
     * @param movePlayed UCI move played by the user (null if answer was revealed)
     * @param correct    whether the move matched the engine's best move
     * @param rating     FSRS self-rating after seeing the answer
     * @param responseMs time from card display to first move, in milliseconds
     */
    @Transactional
    public Attempt recordAttempt(UUID sessionId, UUID cardId,
                                 String movePlayed, boolean correct,
                                 AttemptRating rating, Long responseMs) {
        DrillSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));

        // Persist attempt
        Attempt attempt = Attempt.builder()
                .card(card)
                .session(session)
                .movePlayed(movePlayed)
                .correct(correct)
                .rating(rating)
                .responseMs(responseMs)
                .build();
        attemptRepository.save(attempt);

        // Advance FSRS
        Card updated = scheduler.schedule(card, rating);
        cardRepository.save(updated);

        // Advance session counter
        session.setCardsCompleted(session.getCardsCompleted() + 1);
        if (session.getCardsCompleted() >= session.getCardsTotal()) {
            completeSession(session);
        }
        sessionRepository.save(session);

        return attempt;
    }

    @Transactional
    public void completeSession(DrillSession session) {
        session.setCompleted(true);
        session.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        updateDailySnapshot(session);
    }

    // --- Snapshot ---

    private void updateDailySnapshot(DrillSession session) {
        LocalDate today = LocalDate.now();
        List<Attempt> attempts = attemptRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        if (attempts.isEmpty()) return;

        MetricSnapshot snap = snapshotRepository
                .findByUsernameAndSnapshotDate(session.getUsername(), today)
                .orElse(MetricSnapshot.builder()
                        .username(session.getUsername())
                        .snapshotDate(today)
                        .build());

        int reviewed = attempts.size();
        int correct  = (int) attempts.stream().filter(Attempt::isCorrect).count();
        int again    = (int) attempts.stream().filter(a -> a.getRating() == AttemptRating.AGAIN).count();

        snap.setCardsReviewed(snap.getCardsReviewed() + reviewed);
        snap.setCardsCorrect(snap.getCardsCorrect()  + correct);
        snap.setCardsAgain(snap.getCardsAgain()       + again);

        // Per-phase breakdown
        for (Attempt a : attempts) {
            GamePhase phase = a.getCard().getSourceError().getGamePhase();
            if (phase == null) continue;
            switch (phase) {
                case OPENING    -> { snap.setOpeningTotal(snap.getOpeningTotal() + 1);
                                    if (a.isCorrect()) snap.setOpeningCorrect(snap.getOpeningCorrect() + 1); }
                case MIDDLEGAME -> { snap.setMiddlegameTotal(snap.getMiddlegameTotal() + 1);
                                    if (a.isCorrect()) snap.setMiddlegameCorrect(snap.getMiddlegameCorrect() + 1); }
                case ENDGAME    -> { snap.setEndgameTotal(snap.getEndgameTotal() + 1);
                                    if (a.isCorrect()) snap.setEndgameCorrect(snap.getEndgameCorrect() + 1); }
            }
        }

        // Average interval of reviewed cards
        double avgInterval = attempts.stream()
                .mapToInt(a -> a.getCard().getIntervalDays())
                .average().orElse(0);
        snap.setAvgIntervalDays(Math.round(avgInterval * 10.0) / 10.0);
        snap.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        snapshotRepository.save(snap);
    }

    // --- Helpers ---

    private List<Card> take(List<Card> list, int n) {
        return list.stream().limit(n).collect(Collectors.toList());
    }

    /**
     * Sibling rotation: reorder so that cards with identical (motif + phase) are not adjacent.
     * Uses a round-robin interleave across motif groups.
     */
    private List<Card> siblingRotate(List<Card> cards) {
        Map<String, List<Card>> groups = new LinkedHashMap<>();
        for (Card c : cards) {
            String motif = c.getSourceError() != null && c.getSourceError().getTacticalMotif() != null
                    ? c.getSourceError().getTacticalMotif().name() : "NONE";
            String phase = c.getSourceError() != null && c.getSourceError().getGamePhase() != null
                    ? c.getSourceError().getGamePhase().name() : "NONE";
            groups.computeIfAbsent(motif + "_" + phase, k -> new ArrayList<>()).add(c);
        }
        List<List<Card>> lists = new ArrayList<>(groups.values());
        List<Card> result = new ArrayList<>(cards.size());
        boolean added = true;
        while (added) {
            added = false;
            for (List<Card> g : lists) {
                if (!g.isEmpty()) {
                    result.add(g.remove(0));
                    added = true;
                }
            }
        }
        return result;
    }

    private String toJson(List<UUID> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize card queue", e);
        }
    }

    public List<UUID> parseQueue(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse card queue", e);
        }
    }
}
