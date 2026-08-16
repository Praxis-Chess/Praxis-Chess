package com.praxis.domain;

import com.praxis.domain.enums.AnalysisStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "games", indexes = {
    @Index(name = "idx_games_username", columnList = "username"),
    @Index(name = "idx_games_analysis_status", columnList = "analysis_status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chess_com_id", unique = true, nullable = false, length = 64)
    private String chessComId;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "played_at")
    private OffsetDateTime playedAt;

    @Column(name = "time_control", length = 32)
    private String timeControl;

    @Column(name = "time_class", length = 16)
    private String timeClass;

    @Column(name = "player_color", nullable = false, length = 8)
    private String playerColor;

    @Column(nullable = false, length = 8)
    private String result;

    @Column(name = "opening_eco", length = 8)
    private String openingEco;

    @Column(name = "opening_name", length = 128)
    private String openingName;

    @Column(name = "raw_pgn", nullable = false, columnDefinition = "TEXT")
    private String rawPgn;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", length = 16)
    @Builder.Default
    private AnalysisStatus analysisStatus = AnalysisStatus.PENDING;

    @Column(name = "analyzed_at")
    private OffsetDateTime analyzedAt;

    @Column(name = "white_rating")
    private Integer whiteRating;

    @Column(name = "black_rating")
    private Integer blackRating;

    @Column(name = "accuracy")
    private Double accuracy;

    /**
     * Is this accuracy a real measurement?
     *
     * A stored 0.0 is never one. computeAccuracy() used to return 0.0 when it
     * could not measure a game, which made a failure indistinguishable from a
     * catastrophically bad game — and 61 of 100 games ended up holding it,
     * dragging every average to nonsense (a 15.4% "average accuracy") and making
     * the trend chart look like improvement as the zeros aged out of the moving
     * window. A genuine chess accuracy of exactly 0.0 does not occur.
     */
    public boolean hasAccuracy() {
        return accuracy != null && accuracy > 0.0;
    }

    // Highest evaluation (pawns) the player reached in the game, from their perspective.
    // Powers winning-position conversion analytics. Null until analyzed.
    @Column(name = "max_advantage")
    private Double maxAdvantage;

    // Average seconds the player spent per move (derived from PGN clock annotations).
    // Powers time-management analytics. Null when the game has no clock data.
    @Column(name = "avg_move_seconds")
    private Double avgMoveSeconds;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
