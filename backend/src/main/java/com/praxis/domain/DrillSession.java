package com.praxis.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A time-budgeted drill session.
 *
 * Sessions are persisted so they can be resumed when interrupted. The session
 * engine generates a card queue (due reviews + new cards + stretch) and stores
 * it here; the client fetches cards one at a time, records attempts, and the
 * session is marked COMPLETED when all cards are reviewed or the budget expires.
 *
 * Note: named DrillSession (not Session) to avoid clashing with java.io.Serializable
 * and the JPA provider's own session terminology.
 */
@Entity
@Table(name = "drill_sessions", indexes = {
        @Index(name = "idx_sessions_username", columnList = "username"),
        @Index(name = "idx_sessions_started",  columnList = "started_at")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DrillSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String username;

    /** Ordered JSON array of card UUIDs for this session. */
    @Column(name = "card_queue", columnDefinition = "TEXT")
    private String cardQueue;

    /** Estimated duration in minutes. */
    @Column(name = "budget_minutes")
    @Builder.Default
    private int budgetMinutes = 20;

    /** Number of cards completed so far. */
    @Column(name = "cards_completed")
    @Builder.Default
    private int cardsCompleted = 0;

    /** Total cards in this session. */
    @Column(name = "cards_total")
    @Builder.Default
    private int cardsTotal = 0;

    @Column(name = "completed")
    @Builder.Default
    private boolean completed = false;

    @Column(name = "started_at")
    @CreationTimestamp
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
