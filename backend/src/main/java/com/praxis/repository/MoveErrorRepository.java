package com.praxis.repository;

import com.praxis.domain.MoveError;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface MoveErrorRepository extends JpaRepository<MoveError, UUID> {

    List<MoveError> findByGameId(UUID gameId);

    @Query("""
        SELECT me FROM MoveError me
        JOIN me.game g
        WHERE g.username = :username
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
        """)
    List<MoveError> findAllByUsernameWithGame(@Param("username") String username);

    // Only mistakes where LLM produced an explanation — used for motif frequency
    @Query("""
        SELECT me FROM MoveError me
        JOIN me.game g
        WHERE g.username = :username AND me.analysisState = 'EXPLAINED'
        """)
    List<MoveError> findExplainedByUsername(String username);

    @Transactional
    @Modifying
    void deleteByGameId(UUID gameId);

    @Query("""
        SELECT COUNT(me) FROM MoveError me
        JOIN me.game g
        WHERE g.username = :username AND me.severity = 'BLUNDER'
        """)
    int countBlundersByUsername(String username);

    @Query("""
        SELECT me.game.id, COUNT(me) FROM MoveError me
        JOIN me.game g
        WHERE g.username = :username
        GROUP BY me.game.id
        """)
    List<Object[]> countSuccessfulPerGameByUsername(@Param("username") String username);

    // Mistakes with a known engine best move — used to generate "solve your own blunder" drills.
    @Query("""
        SELECT me FROM MoveError me
        JOIN me.game g
        WHERE g.username = :username AND me.betterMove IS NOT NULL
        """)
    List<MoveError> findDrillCandidatesByUsername(@Param("username") String username);
}
