package com.praxis.repository;

import com.praxis.domain.MoveError;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Username-scoped reads are CHESS_COM-only by default — the same rule, and for
 * the same reason, as {@link GameRepository}.
 *
 * A practice game is archived as a real Game row with real MoveError rows, so an
 * unfiltered read here folds Stockfish-game mistakes into the Pattern Report, the
 * drill deck, Insights, Today and every mistake figure Prax quotes. GameRepository
 * already guards the game-level aggregates; these are the move-level ones, and
 * they need the same guard or the filter is only half applied.
 *
 * NULL counts as CHESS_COM, exactly as in GameRepository: ddl-auto adds a column
 * to existing rows as NULL and never backfills it, so the clause has to be correct
 * before {@code backfillNullSource()} runs and for any row inserted by something
 * that bypasses the builder.
 *
 * Practice data takes an explicit {@code ...Practice...} call. A caller who forgets
 * under-counts, which is visible; the other way round fails silently.
 *
 * Lookups by game id are deliberately NOT filtered — they act on rows the caller
 * has already identified, not on statistics.
 */
public interface MoveErrorRepository extends JpaRepository<MoveError, UUID> {

    List<MoveError> findByGameId(UUID gameId);

    @Query("""
        SELECT me FROM MoveError me
        JOIN me.game g
        WHERE g.username = :username
          AND (g.source IS NULL OR g.source = com.praxis.domain.enums.GameSource.CHESS_COM)
        """)
    List<MoveError> findAllByUsername(String username);

    /**
     * As above, but with the game loaded. MoveError.game is LAZY and the prax
     * analytics run outside a transaction, so anything that reads e.getGame()
     * must fetch it here or take a LazyInitializationException.
     */
    @Query("""
        SELECT me FROM MoveError me
        JOIN FETCH me.game g
        WHERE g.username = :username
          AND (g.source IS NULL OR g.source = com.praxis.domain.enums.GameSource.CHESS_COM)
        """)
    List<MoveError> findAllByUsernameWithGame(@Param("username") String username);

    // Only mistakes where LLM produced an explanation — used for motif frequency
    @Query("""
        SELECT me FROM MoveError me
        JOIN me.game g
        WHERE g.username = :username AND me.analysisState = 'EXPLAINED'
          AND (g.source IS NULL OR g.source = com.praxis.domain.enums.GameSource.CHESS_COM)
        """)
    List<MoveError> findExplainedByUsername(String username);

    @Transactional
    @Modifying
    void deleteByGameId(UUID gameId);

    @Query("""
        SELECT COUNT(me) FROM MoveError me
        JOIN me.game g
        WHERE g.username = :username AND me.severity = 'BLUNDER'
          AND (g.source IS NULL OR g.source = com.praxis.domain.enums.GameSource.CHESS_COM)
        """)
    int countBlundersByUsername(String username);

    @Query("""
        SELECT me.game.id, COUNT(me) FROM MoveError me
        JOIN me.game g
        WHERE g.username = :username
          AND (g.source IS NULL OR g.source = com.praxis.domain.enums.GameSource.CHESS_COM)
        GROUP BY me.game.id
        """)
    List<Object[]> countSuccessfulPerGameByUsername(@Param("username") String username);

    // Mistakes with a known engine best move — used to generate "solve your own blunder" drills.
    @Query("""
        SELECT me FROM MoveError me
        JOIN me.game g
        WHERE g.username = :username AND me.betterMove IS NOT NULL
          AND (g.source IS NULL OR g.source = com.praxis.domain.enums.GameSource.CHESS_COM)
        """)
    List<MoveError> findDrillCandidatesByUsername(@Param("username") String username);

    // --- Practice games: explicit opt-in ---

    /**
     * Every mistake from games played against the engine, game fetched.
     * Feeds the practice-only pattern view, which must never mix populations
     * with the Chess.com aggregates above.
     */
    @Query("""
        SELECT me FROM MoveError me
        JOIN FETCH me.game g
        WHERE g.username = :username
          AND g.source = com.praxis.domain.enums.GameSource.PRACTICE
        """)
    List<MoveError> findPracticeByUsernameWithGame(@Param("username") String username);
}
