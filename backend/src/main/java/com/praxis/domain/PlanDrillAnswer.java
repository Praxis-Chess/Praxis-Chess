package com.praxis.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One answer to one position in a Training Plan session (F12).
 *
 * A session used to keep only its total ("2 of 3 solved"). Kept per position,
 * the answers can later say whether a position that was missed is now solved,
 * and how a cause fares after it was trained. That history only exists from the
 * day this is recorded, so it is recorded from the start.
 *
 * The position is named by (game, ply), not by the mistake's id: re-analysis
 * supersedes mistake rows, but the game and the ply stay the same.
 */
@Entity
@Table(name = "plan_drill_answers", indexes = {
        @Index(name = "idx_plan_drill_answers_plan", columnList = "plan_id"),
        @Index(name = "idx_plan_drill_answers_position", columnList = "game_id, ply")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PlanDrillAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "session_number", nullable = false)
    private int sessionNumber;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "ply", nullable = false)
    private int ply;

    /** The mistake row answered, for reference; it may later be superseded. */
    @Column(name = "move_error_id")
    private UUID moveErrorId;

    /** Found the engine's move without asking for the answer. */
    @Column(name = "correct", nullable = false)
    private boolean correct;

    /** Wrong moves tried first. */
    @Column(name = "wrong_tries", nullable = false)
    private int wrongTries;

    /** Asked for the answer instead of finding it. */
    @Column(name = "revealed", nullable = false)
    private boolean revealed;

    /** Milliseconds from the position appearing to the answer. */
    @Column(name = "response_ms")
    private Long responseMs;

    @CreationTimestamp
    @Column(name = "answered_at", nullable = false, updatable = false)
    private OffsetDateTime answeredAt;
}
