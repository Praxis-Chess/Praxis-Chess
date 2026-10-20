package com.praxis.api;

import com.praxis.config.PraxisClock;
import com.praxis.domain.enums.CardStatus;
import com.praxis.dto.ProgressDto;
import com.praxis.dto.ProgressDto.*;
import com.praxis.repository.CardRepository;
import com.praxis.repository.MetricSnapshotRepository;
import com.praxis.service.drills.DeckRule;
import com.praxis.service.settings.PlayerIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Progress-page data: deck health + per-day metric history.
 */
@RestController
@RequestMapping("/api/progress")
public class ProgressController {

    private final CardRepository cardRepository;
    private final MetricSnapshotRepository snapshotRepository;
    private final PlayerIdentity player;
    private final PraxisClock clock;

    public ProgressController(CardRepository cardRepository,
                              MetricSnapshotRepository snapshotRepository,
                              PlayerIdentity player,
                              PraxisClock clock) {
        this.cardRepository = cardRepository;
        this.snapshotRepository = snapshotRepository;
        this.player = player;
        this.clock = clock;
    }

    @GetMapping
    public ResponseEntity<ProgressDto> getProgress() {
        String username = player.username();

        long newCards = cardRepository.countByUsernameAndStatus(username, CardStatus.NEW);
        LocalDate today = clock.today();
        long introduced = cardRepository.countIntroducedSince(username,
                today.atStartOfDay(clock.zone()).toOffsetDateTime());
        long newToday = Math.min(newCards, Math.max(0, DeckRule.NEW_PER_DAY - introduced));
        DeckSummary deck = new DeckSummary(
                cardRepository.countActiveByUsername(username),
                newCards,
                cardRepository.countByUsernameAndStatus(username, CardStatus.LEARNING) +
                cardRepository.countByUsernameAndStatus(username, CardStatus.RELEARNING),
                cardRepository.countByUsernameAndStatus(username, CardStatus.REVIEW),
                cardRepository.countByUsernameAndStatus(username, CardStatus.SUSPENDED),
                cardRepository.countDueReviews(username, today) + newToday
        );

        LocalDate cutoff = today.minusDays(30);
        List<DailyStat> history = snapshotRepository
                .findByUsernameOrderBySnapshotDateDesc(username)
                .stream()
                .filter(s -> !s.getSnapshotDate().isBefore(cutoff))
                .sorted((a, b) -> a.getSnapshotDate().compareTo(b.getSnapshotDate()))
                .map(DailyStat::from)
                .toList();

        return ResponseEntity.ok(new ProgressDto(deck, history));
    }
}
