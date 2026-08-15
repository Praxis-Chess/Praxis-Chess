package com.praxis.domain;

import com.praxis.domain.enums.CardStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A spaced-repetition flash card derived from a MoveError.
 *
 * One card per unique FEN position (deduplication key). When multiple MoveErrors
 * share the same FEN the card is created from the worst blunder; the others become
 * sibling references via the sourceError relationship.
 *
 * FSRS state: stability, difficulty, retrievability are stored after every review.
 * Anti-memorisation rules (7-day minimum first review for own-game cards, lapse cap
 * at 5 → SUSPENDED, graduation requiring 3 consecutive GOOD at ≥21-day interval)
 * are enforced by SessionEngineService / FsrsScheduler.
 */
@Entity
@Table(name = "cards", indexes = {
        @Index(name = "idx_cards_username", columnList = "username"),
        @Index(name = "idx_cards_status",   columnList = "status"),
        @Index(name = "idx_cards_due_date", columnList = "due_date"),
        @Index(name = "idx_cards_fen",      columnList = "fen_position", unique = false)
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String username;

    // --- Source ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_error_id", nullable = false)
    private MoveError sourceError;

    /** FEN before the mistake — deduplication key. */
    @Column(name = "fen_position", nullable = false, columnDefinition = "TEXT")
    private String fenPosition;

    // --- FSRS scheduling state ---

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private CardStatus status = CardStatus.NEW;

    /** FSRS stability (expected days until 90% retention). */
    @Column
    private Double stability;

    /** FSRS difficulty (0–10). */
    @Column
    private Double difficulty;

    /** Current interval in days (0 = due today or overdue). */
    @Column(name = "interval_days")
    @Builder.Default
    private int intervalDays = 0;

    /** Number of times the card lapsed (failed after graduating). */
    @Column(name = "lapse_count")
    @Builder.Default
    private int lapseCount = 0;

    /** Consecutive successful recalls at or above the graduation interval. */
    @Column(name = "consecutive_good")
    @Builder.Default
    private int consecutiveGood = 0;

    /** Total number of review attempts. */
    @Column(name = "review_count")
    @Builder.Default
    private int reviewCount = 0;

    /** Date when this card is next due for review. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Date of the last review (null = never reviewed). */
    @Column(name = "last_reviewed_at")
    private OffsetDateTime lastReviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
