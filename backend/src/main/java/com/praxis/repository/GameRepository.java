package com.praxis.repository;

import com.praxis.domain.Game;
import com.praxis.domain.enums.AnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads are CHESS_COM-only by default.
 *
 * Every aggregate in the app — Insights, Dashboard, PatternAggregator,
 * ChessIntelligence, Today — calls these methods and sums whatever comes back.
 * Practice games against the engine are real rows in this table, so an unfiltered
 * read would silently fold Stockfish games into the player's rating trend, win
 * rate and opening statistics.
 *
 * The default is therefore the SAFE answer, and including practice games takes an
 * explicit `...IncludingPractice` call. A caller who forgets under-counts, which
 * is visible; the other way round fails silently.
 *
 * NULL counts as CHESS_COM. ddl-auto adds a column to existing rows as NULL and
 * never backfills it — @Builder.Default only sets the field for entities built
 * in Java. Without the NULL branch, adding this filter made every aggregate in
 * the app see one game instead of a hundred. The backfill below repairs the
 * data; this clause makes the query correct even before it runs, and for any
 * row inserted by something that bypasses the builder.
 */
public interface GameRepository extends JpaRepository<Game, UUID> {

    boolean existsByChessComId(String chessComId);

    @Query("SELECT g FROM Game g WHERE g.username = :username AND (g.source IS NULL OR g.source = com.praxis.domain.enums.GameSource.CHESS_COM) ORDER BY g.playedAt DESC")
    List<Game> findByUsernameOrderByPlayedAtDesc(@Param("username") String username);

    @Query("SELECT g FROM Game g WHERE g.username = :username AND g.analysisStatus = :status AND (g.source IS NULL OR g.source = com.praxis.domain.enums.GameSource.CHESS_COM)")
    List<Game> findByUsernameAndAnalysisStatus(@Param("username") String username, @Param("status") AnalysisStatus status);

    @Query("SELECT COUNT(g) FROM Game g WHERE g.username = :username AND g.analysisStatus = :status AND (g.source IS NULL OR g.source = com.praxis.domain.enums.GameSource.CHESS_COM)")
    long countByUsernameAndAnalysisStatus(@Param("username") String username, @Param("status") AnalysisStatus status);

    @Query("SELECT g FROM Game g WHERE g.username = :username AND (g.source IS NULL OR g.source = com.praxis.domain.enums.GameSource.CHESS_COM) ORDER BY g.playedAt DESC")
    List<Game> findRecentByUsername(@Param("username") String username);

    // --- Practice games: explicit opt-in ---

    @Query("SELECT g FROM Game g WHERE g.username = :username AND g.source = com.praxis.domain.enums.GameSource.PRACTICE ORDER BY g.playedAt DESC")
    List<Game> findPracticeGamesByUsername(@Param("username") String username);

    /** Every game regardless of origin. Only for operations that act on rows, not statistics. */
    @Query("SELECT g FROM Game g WHERE g.username = :username ORDER BY g.playedAt DESC")
    List<Game> findAllSourcesByUsername(@Param("username") String username);

    long countBySourceIsNull();

    /**
     * One-time repair for rows that predate the `source` column. Every such row
     * came from Chess.com, because practice games did not exist before it.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Game g SET g.source = com.praxis.domain.enums.GameSource.CHESS_COM WHERE g.source IS NULL")
    int backfillNullSource();

    Optional<Game> findByChessComId(String chessComId);

    /** Returns all Chess.com UUIDs stored for a user — used to diff against a live Chess.com month fetch. */
    @Query("SELECT g.chessComId FROM Game g WHERE g.username = :username")
    List<String> findAllChessComIdsByUsername(@Param("username") String username);

    // Resets games stuck in ANALYZING state (e.g. after a server crash) back to PENDING
    @Modifying
    @Transactional
    @Query("UPDATE Game g SET g.analysisStatus = :to WHERE g.analysisStatus = :from")
    int resetAnalysisStatus(@Param("from") AnalysisStatus from, @Param("to") AnalysisStatus to);
}
