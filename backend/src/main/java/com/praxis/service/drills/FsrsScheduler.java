package com.praxis.service.drills;

import com.praxis.domain.Card;
import com.praxis.domain.enums.AttemptRating;
import com.praxis.domain.enums.CardStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Free Spaced Repetition Scheduler (FSRS v4) implementation.
 *
 * Reference: Jarrett Ye, "FSRS v4: A Modern Spaced Repetition Algorithm" (2023).
 * Parameters are the published defaults; they can be personalised later.
 *
 * Anti-memorisation rules enforced here:
 *   - Minimum first interval: enforced by CardGeneratorService (dueDate = today + 7d)
 *   - Graduation: 3 consecutive GOOD/EASY reviews at interval ≥ 21 days → REVIEW
 *   - Lapse cap: lapseCount ≥ 5 → SUSPENDED
 *   - Sibling rotation: enforced by SessionEngineService (not here)
 */
@Component
public class FsrsScheduler implements SchedulerStrategy {

    // FSRS v4 default parameters
    private static final double W0  = 0.4;    // initial stability for AGAIN
    private static final double W1  = 0.6;    // initial stability for HARD
    private static final double W2  = 2.4;    // initial stability for GOOD
    private static final double W3  = 5.8;    // initial stability for EASY
    private static final double W4  = 4.93;   // initial difficulty
    private static final double W5  = 0.94;   // difficulty decay
    private static final double W6  = 0.86;   // difficulty penalty (HARD)
    private static final double W7  = 0.01;   // difficulty penalty (EASY)
    private static final double W8  = 1.49;   // stability recall
    private static final double W9  = 0.14;   // stability forgetting
    private static final double W10 = 0.94;   // stability bonus (HARD)
    private static final double W11 = 2.18;   // stability bonus (EASY)
    private static final double W12 = 0.05;   // difficulty weight in recall
    private static final double W13 = 0.34;   // stability weight in recall
    private static final double W14 = 1.26;   // stability power in recall
    private static final double W15 = 0.29;   // lapse stability factor
    private static final double W16 = 2.61;   // lapse stability bonus

    private static final double DECAY             = -0.5;
    private static final double FACTOR            = Math.pow(0.9, 1.0 / DECAY) - 1; // ≈ 19/81
    private static final double TARGET_RETENTION  = 0.9;

    private static final int GRADUATION_INTERVAL    = 21;  // days; consecutive good ≥ this → graduate
    private static final int GRADUATION_STREAK      = 3;   // consecutive good needed
    private static final int MAX_LAPSES_BEFORE_SUSPEND = 5;

    @Override
    public Card schedule(Card card, AttemptRating rating) {
        Card c = copyCard(card);
        c.setLastReviewedAt(OffsetDateTime.now(ZoneOffset.UTC));
        c.setReviewCount(c.getReviewCount() + 1);

        if (c.getStatus() == CardStatus.NEW || c.getStatus() == CardStatus.LEARNING) {
            scheduleNew(c, rating);
        } else if (c.getStatus() == CardStatus.REVIEW) {
            scheduleReview(c, rating);
        } else if (c.getStatus() == CardStatus.RELEARNING) {
            scheduleRelearning(c, rating);
        }

        return c;
    }

    // --- New / Learning ---

    private void scheduleNew(Card c, AttemptRating rating) {
        double initStab = switch (rating) {
            case AGAIN -> W0;
            case HARD  -> W1;
            case GOOD  -> W2;
            case EASY  -> W3;
        };
        double initDiff = W4 - Math.exp(W5 * (gradeOf(rating) - 1)) + 1;
        initDiff = clamp(initDiff, 1.0, 10.0);

        c.setStability(initStab);
        c.setDifficulty(initDiff);

        if (rating == AttemptRating.AGAIN) {
            c.setStatus(CardStatus.LEARNING);
            c.setIntervalDays(1);
            c.setConsecutiveGood(0);
        } else if (rating == AttemptRating.HARD) {
            c.setStatus(CardStatus.LEARNING);
            c.setIntervalDays(Math.max(1, (int) Math.ceil(initStab)));
        } else {
            // GOOD or EASY — check graduation
            advanceGood(c, rating);
        }
        c.setDueDate(LocalDate.now().plusDays(c.getIntervalDays()));
    }

    // --- Review ---

    private void scheduleReview(Card c, AttemptRating rating) {
        double d = c.getDifficulty() != null ? c.getDifficulty() : W4;
        double s = c.getStability() != null ? c.getStability() : W2;

        if (rating == AttemptRating.AGAIN) {
            // Lapse
            int lapses = c.getLapseCount() + 1;
            c.setLapseCount(lapses);
            if (lapses >= MAX_LAPSES_BEFORE_SUSPEND) {
                c.setStatus(CardStatus.SUSPENDED);
                c.setIntervalDays(0);
                c.setDueDate(null);
                return;
            }
            double newStab = W15 * Math.pow(s, -W9) * (Math.exp(W16 * (1 - retrievability(s))) - 1);
            c.setStability(Math.max(0.1, newStab));
            c.setDifficulty(updateDifficulty(d, rating));
            c.setStatus(CardStatus.RELEARNING);
            c.setIntervalDays(1);
            c.setConsecutiveGood(0);
        } else {
            double newStab = recallStability(d, s, rating);
            c.setStability(newStab);
            c.setDifficulty(updateDifficulty(d, rating));
            advanceGood(c, rating);
        }
        c.setDueDate(LocalDate.now().plusDays(c.getIntervalDays()));
    }

    // --- Relearning ---

    private void scheduleRelearning(Card c, AttemptRating rating) {
        if (rating == AttemptRating.AGAIN) {
            c.setIntervalDays(1);
            c.setConsecutiveGood(0);
        } else {
            advanceGood(c, rating);
            if (c.getStatus() == CardStatus.REVIEW) {
                // just graduated back out of relearning — keep going
            }
        }
        c.setDueDate(LocalDate.now().plusDays(c.getIntervalDays()));
    }

    // --- Helpers ---

    private void advanceGood(Card c, AttemptRating rating) {
        double s = c.getStability() != null ? c.getStability() : W2;
        int interval = optimalInterval(s);

        if (interval >= GRADUATION_INTERVAL) {
            c.setConsecutiveGood(c.getConsecutiveGood() + 1);
        } else {
            c.setConsecutiveGood(0);
        }

        if (c.getConsecutiveGood() >= GRADUATION_STREAK) {
            c.setStatus(CardStatus.REVIEW);
        } else {
            c.setStatus(CardStatus.LEARNING);
        }

        c.setIntervalDays(Math.max(interval, 1));
    }

    private double recallStability(double d, double s, AttemptRating rating) {
        double r = retrievability(s);
        double base = s * W8 * (Math.pow(d, -W12) * Math.pow(s + 1, W13) * Math.pow(r, W14) - 1 + 1);
        return base * switch (rating) {
            case HARD -> W10;
            case EASY -> W11;
            default   -> 1.0;
        };
    }

    private double updateDifficulty(double d, AttemptRating rating) {
        double delta = W6 * (gradeOf(rating) - 3);
        double updated = d - delta + W7 * (10 - d) * Math.pow(d - 1, 2) / 81.0;
        return clamp(updated, 1.0, 10.0);
    }

    /** Retrievability at current stability (fraction of users who recall at this interval). */
    private double retrievability(double stability) {
        // R(t, S) = (1 + t / (FACTOR * S)) ^ DECAY  where t = stabilityDays (approximate)
        return Math.pow(1 + stability / (FACTOR * stability), DECAY);
    }

    /** Next interval in days targeting TARGET_RETENTION retention. */
    private int optimalInterval(double stability) {
        // I = S/FACTOR * (R^(1/DECAY) - 1)
        double days = stability / FACTOR * (Math.pow(TARGET_RETENTION, 1.0 / DECAY) - 1);
        return Math.max(1, (int) Math.round(days));
    }

    private int gradeOf(AttemptRating rating) {
        return switch (rating) { case AGAIN -> 1; case HARD -> 2; case GOOD -> 3; case EASY -> 4; };
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Shallow copy — only fields we read/write. */
    private Card copyCard(Card src) {
        Card c = new Card();
        c.setId(src.getId());
        c.setUsername(src.getUsername());
        c.setSourceError(src.getSourceError());
        c.setFenPosition(src.getFenPosition());
        c.setStatus(src.getStatus());
        c.setStability(src.getStability());
        c.setDifficulty(src.getDifficulty());
        c.setIntervalDays(src.getIntervalDays());
        c.setLapseCount(src.getLapseCount());
        c.setConsecutiveGood(src.getConsecutiveGood());
        c.setReviewCount(src.getReviewCount());
        c.setDueDate(src.getDueDate());
        c.setLastReviewedAt(src.getLastReviewedAt());
        c.setCreatedAt(src.getCreatedAt());
        return c;
    }
}
