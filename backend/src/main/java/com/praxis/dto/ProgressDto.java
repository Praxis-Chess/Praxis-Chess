package com.praxis.dto;

import com.praxis.domain.MetricSnapshot;
import com.praxis.domain.enums.CardStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * Progress page DTO — aggregates card-deck health and daily metric history.
 */
public record ProgressDto(
        DeckSummary deckSummary,
        List<DailyStat> history  // last 30 days, ascending
) {
    public record DeckSummary(
            long totalCards,
            long newCards,
            long learningCards,
            long reviewCards,
            long suspendedCards
    ) {}

    public record DailyStat(
            LocalDate date,
            int reviewed,
            int correct,
            int again,
            double accuracy,
            Double avgIntervalDays,
            PhaseBreakdown phases
    ) {
        public static DailyStat from(MetricSnapshot s) {
            double acc = s.getCardsReviewed() == 0 ? 0
                    : Math.round(s.getCardsCorrect() * 1000.0 / s.getCardsReviewed()) / 10.0;
            return new DailyStat(s.getSnapshotDate(), s.getCardsReviewed(),
                    s.getCardsCorrect(), s.getCardsAgain(), acc, s.getAvgIntervalDays(),
                    new PhaseBreakdown(
                            s.getOpeningCorrect(), s.getOpeningTotal(),
                            s.getMiddlegameCorrect(), s.getMiddlegameTotal(),
                            s.getEndgameCorrect(), s.getEndgameTotal()));
        }
    }

    public record PhaseBreakdown(
            int openingCorrect,  int openingTotal,
            int middlegameCorrect, int middlegameTotal,
            int endgameCorrect,  int endgameTotal
    ) {}
}
