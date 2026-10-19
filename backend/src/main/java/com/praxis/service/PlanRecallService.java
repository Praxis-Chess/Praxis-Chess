package com.praxis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.domain.Attempt;
import com.praxis.domain.Card;
import com.praxis.domain.TrainingPlan;
import com.praxis.report.PlanFacts;
import com.praxis.report.ReportFacts;
import com.praxis.repository.AttemptRepository;
import com.praxis.repository.CardRepository;
import com.praxis.repository.TrainingPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Recall on these positions" (F11): for a plan's positions that are also cards
 * in the review deck, how the reviews since the plan went.
 *
 * Only positions with a card can be measured this way; the result says how
 * many of a session's positions that is, so a session with none tracked reads
 * as "not tracked", never as "nothing recalled".
 */
@Service
public class PlanRecallService {

    private static final Logger log = LoggerFactory.getLogger(PlanRecallService.class);

    private final TrainingPlanRepository plans;
    private final CardRepository cards;
    private final AttemptRepository attempts;
    private final ObjectMapper objectMapper;

    public PlanRecallService(TrainingPlanRepository plans, CardRepository cards, AttemptRepository attempts,
                             ObjectMapper objectMapper) {
        this.plans = plans;
        this.cards = cards;
        this.attempts = attempts;
        this.objectMapper = objectMapper;
    }

    /** One position: its reviews since the plan, when it has a card. */
    public record Position(UUID gameId, int ply, boolean tracked, int reviews, int correct) {}

    /** One session: how many of its positions have a card, and their reviews since the plan. */
    public record Session(int number, int positions, int tracked, int reviews, int correct, List<Position> detail) {}

    public record Result(OffsetDateTime since, List<Session> sessions) {}

    @Transactional(readOnly = true)
    public Optional<Result> recall(UUID planId) {
        return plans.findById(planId).flatMap(p -> {
            PlanFacts plan = read(p);
            if (plan == null) return Optional.empty();
            Set<UUID> gameIds = new HashSet<>();
            plan.sessions().forEach(s -> s.positions().forEach(x -> gameIds.add(x.gameId())));
            Map<String, Card> byPosition = new HashMap<>();
            if (!gameIds.isEmpty()) {
                for (Card c : cards.findByUsernameAndGameIds(p.getUsername(), gameIds)) {
                    var e = c.getSourceError();
                    byPosition.putIfAbsent(e.getGame().getId() + ":" + e.getMoveNumber(), c);
                }
            }
            OffsetDateTime since = p.getGeneratedAt();
            List<Session> sessions = new ArrayList<>();
            for (PlanFacts.Session s : plan.sessions()) {
                List<Position> detail = new ArrayList<>();
                for (ReportFacts.Example x : s.positions()) {
                    Card c = byPosition.get(x.gameId() + ":" + x.ply());
                    if (c == null) { detail.add(new Position(x.gameId(), x.ply(), false, 0, 0)); continue; }
                    List<Attempt> after = attempts.findByCardIdOrderByCreatedAtDesc(c.getId()).stream()
                            .filter(a -> a.getCreatedAt() != null && !a.getCreatedAt().isBefore(since)).toList();
                    detail.add(new Position(x.gameId(), x.ply(), true, after.size(),
                            (int) after.stream().filter(Attempt::isCorrect).count()));
                }
                sessions.add(new Session(s.number(), detail.size(),
                        (int) detail.stream().filter(Position::tracked).count(),
                        detail.stream().mapToInt(Position::reviews).sum(),
                        detail.stream().mapToInt(Position::correct).sum(), detail));
            }
            return Optional.of(new Result(since, sessions));
        });
    }

    private PlanFacts read(TrainingPlan p) {
        if (p.getFactsJson() == null || p.getPlanJson() == null) return null;   // made before Phase 12
        try {
            return objectMapper.readValue(p.getPlanJson(), PlanFacts.class);
        } catch (Exception unreadable) {
            log.warn("Plan {} could not be read for recall: {}", p.getId(), unreadable.getMessage());
            return null;
        }
    }
}
