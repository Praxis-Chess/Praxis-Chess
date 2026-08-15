package com.praxis.api;

import com.praxis.config.AppProperties;
import com.praxis.domain.enums.CardStatus;
import com.praxis.dto.ProgressDto;
import com.praxis.dto.ProgressDto.*;
import com.praxis.repository.CardRepository;
import com.praxis.repository.MetricSnapshotRepository;
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
    private final AppProperties appProperties;

    public ProgressController(CardRepository cardRepository,
                              MetricSnapshotRepository snapshotRepository,
                              AppProperties appProperties) {
        this.cardRepository = cardRepository;
        this.snapshotRepository = snapshotRepository;
        this.appProperties = appProperties;
    }

    @GetMapping
    public ResponseEntity<ProgressDto> getProgress() {
        String username = appProperties.chessCom().username();

        DeckSummary deck = new DeckSummary(
                cardRepository.countActiveByUsername(username),
                cardRepository.countByUsernameAndStatus(username, CardStatus.NEW),
                cardRepository.countByUsernameAndStatus(username, CardStatus.LEARNING) +
                cardRepository.countByUsernameAndStatus(username, CardStatus.RELEARNING),
                cardRepository.countByUsernameAndStatus(username, CardStatus.REVIEW),
                cardRepository.countByUsernameAndStatus(username, CardStatus.SUSPENDED)
        );

        LocalDate cutoff = LocalDate.now().minusDays(30);
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
