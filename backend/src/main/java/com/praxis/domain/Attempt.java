package com.praxis.domain;

import com.praxis.domain.enums.AttemptRating;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One review attempt of a Card during a DrillSession.
 *
 * Records the move the user played (UCI), whether it matched the engine's best move,
 * and the FSRS rating they self-assigned after seeing the answer.
 */
@Entity
@Table(name = "attempts", indexes = {
        @Index(name = "idx_attempts_card",    columnList = "card_id"),
        @Index(name = "idx_attempts_session", columnList = "session_id")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Attempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private DrillSession session;

    /** UCI move the user played (e.g. "e2e4"), or null if they revealed the answer. */
    @Column(name = "move_played", length = 8)
    private String movePlayed;

    /** True when movePlayed matches the engine's best move for the card's FEN. */
    @Column(name = "correct")
    private boolean correct;

    /** Number of attempts before this one in the same card exposure. */
    @Column(name = "attempt_number")
    @Builder.Default
    private int attemptNumber = 1;

    /** FSRS rating the user selected after seeing the answer. Null until the card is rated. */
    @Enumerated(EnumType.STRING)
    @Column(name = "rating", length = 16)
    private AttemptRating rating;

    /** Milliseconds from card display to first move played. */
    @Column(name = "response_ms")
    private Long responseMs;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
