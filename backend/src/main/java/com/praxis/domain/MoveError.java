package com.praxis.domain;

import com.praxis.domain.enums.AnalysisState;
import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import com.praxis.domain.enums.TacticalMotif;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
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
}
