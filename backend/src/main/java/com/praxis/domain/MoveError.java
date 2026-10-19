package com.praxis.domain;

import com.praxis.domain.enums.AnalysisState;
import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.domain.enums.TacticalMotif;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One flagged move of one analysis run.
 *
 * <p><b>History is kept (Phase 12).</b> Re-analysing a game no longer deletes its
 * rows: they get {@code superseded_at} and the new run writes fresh ones. The
 * restriction below hides superseded rows from every query, so every screen sees
 * only the current analysis; the old rows stay in the table for recovery and later
 * analysis (read them with native SQL). Drill cards are re-linked to the new row
 * at the same ply, never deleted (GameAnalysisTransactionService).
 */
@Entity
@SQLRestriction("superseded_at IS NULL")
@Table(name = "move_errors", indexes = {
    @Index(name = "idx_move_errors_game_id", columnList = "game_id"),
    @Index(name = "idx_move_errors_severity", columnList = "severity"),
    @Index(name = "idx_move_errors_motif", columnList = "tactical_motif")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoveError {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(name = "move_number", nullable = false)
    private int moveNumber;

    @Column(name = "player_color", nullable = false, length = 8)
    private String playerColor;

    @Column(name = "move_played", nullable = false, length = 16)
    private String movePlayed;

    @Column(name = "better_move", length = 16)
    private String betterMove;

    @Column(name = "fen_position", nullable = false, columnDefinition = "TEXT")
    private String fenPosition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "tactical_motif", length = 32)
    private TacticalMotif tacticalMotif;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_phase", length = 16)
    private GamePhase gamePhase;

    @Column(name = "clock_remaining")
    private Integer clockRemaining;

    /** Position evaluation (White's POV, pawns) immediately before the player's move. */
    @Column(name = "eval_before")
    private Double evalBefore;

    /** Position evaluation (White's POV, pawns) immediately after the player's move. */
    @Column(name = "eval_after")
    private Double evalAfter;

    /** Chess.com win-percentage drop caused by this move (player's POV, 0–100). */
    @Column(name = "win_pct_drop")
    private Double winPctDrop;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_state", length = 16)
    private AnalysisState analysisState;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    /** When a re-analysis replaced this row; null while it is the current one. */
    @Column(name = "superseded_at")
    private OffsetDateTime supersededAt;

    /** The analysis run that wrote this row: {@code analysis_timing.run_id}. Null for rows from before Phase 12. */
    @Column(name = "analysis_run_id")
    private UUID analysisRunId;
}
