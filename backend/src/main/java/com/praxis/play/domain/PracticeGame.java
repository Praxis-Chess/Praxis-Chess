package com.praxis.play.domain;

import com.praxis.play.domain.enums.PracticeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One game played inside Praxis against the engine.
 *
 * The opponent's configuration is persisted alongside the moves because it is
 * part of the measurement, not decoration: comparing a game at skill 3 with one
 * at skill 12 without recording the difference is how a progress feature starts
 * lying, and it cannot be repaired afterwards.
 */
@Entity
@Table(name = "practice_game", indexes = {
        @Index(name = "idx_practice_game_user", columnList = "username, started_at")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PracticeGame {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String username;

    /** The archived Game row. Null until the game finishes and is analysed. */
    @Column(name = "game_id")
    private UUID gameId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private PracticeStatus status = PracticeStatus.IN_PROGRESS;

    @Column(name = "player_color", nullable = false, length = 8)
    private String playerColor;

    // --- opponent configuration: part of the measurement ---

    @Column(name = "skill_level", nullable = false)
    @Builder.Default
    private int skillLevel = 5;

    /** ECO the opponent steered toward, chosen from the player's weakest openings. */
    @Column(name = "target_eco", length = 8)
    private String targetEco;

    @Column(name = "target_opening", length = 128)
    private String targetOpening;

    /** Human-readable statement of what this game was pressing. */
    @Column(name = "targeted_weakness", length = 128)
    private String targetedWeakness;

    // --- integrity ---

    /**
     * False once the player takes a move back. An undone game still gets
     * analysed and shown, but is excluded from improvement tracking — counting
     * it silently would corrupt every later comparison.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean rated = true;

    @Column(name = "undo_count", nullable = false)
    @Builder.Default
    private int undoCount = 0;

    // --- state ---

    @Column(name = "current_fen", nullable = false, columnDefinition = "TEXT")
    private String currentFen;

    /** Space-separated SAN, the source of the archived PGN. */
    @Column(name = "san_moves", columnDefinition = "TEXT")
    @Builder.Default
    private String sanMoves = "";

    @Column(length = 8)
    private String result;

    /** How the game ended, for the report: CHECKMATE, RESIGNATION, STALEMATE… */
    @Column(name = "end_reason", length = 24)
    private String endReason;

    @CreationTimestamp
    @Column(name = "started_at", updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
}
