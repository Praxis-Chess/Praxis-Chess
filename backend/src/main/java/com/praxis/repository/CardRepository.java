package com.praxis.repository;

import com.praxis.domain.Card;
import com.praxis.domain.enums.CardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CardRepository extends JpaRepository<Card, UUID> {

    /** A player's cards from mistakes in these games, with the mistake loaded: plan positions → cards (F11). */
    @Query("""
        SELECT c FROM Card c JOIN FETCH c.sourceError e JOIN FETCH e.game g
        WHERE c.username = :username AND g.id IN :gameIds
        """)
    List<Card> findByUsernameAndGameIds(@Param("username") String username,
                                        @Param("gameIds") java.util.Collection<UUID> gameIds);

    List<Card> findByUsernameOrderByCreatedAtDesc(String username);

    /** Cards due today or overdue, ordered by due_date ascending (most overdue first). */
    @Query("""
        SELECT c FROM Card c
        WHERE c.username = :username
          AND c.status <> 'SUSPENDED'
          AND c.dueDate <= :today
        ORDER BY c.dueDate ASC
        """)
    List<Card> findDueCards(@Param("username") String username, @Param("today") LocalDate today);

    /** New cards — never reviewed. */
    List<Card> findByUsernameAndStatusOrderByCreatedAtAsc(String username, CardStatus status);

    /** Deduplicate: find an existing card with the same FEN for this user. */
    Optional<Card> findByUsernameAndFenPosition(String username, String fenPosition);

    long countByUsernameAndStatus(String username, CardStatus status);

    @Query("""
        SELECT COUNT(c) FROM Card c
        WHERE c.username = :username AND c.status <> 'SUSPENDED'
        """)
    long countActiveByUsername(@Param("username") String username);

    /**
     * Fetches a card with its sourceError and the error's parent game in a single join,
     * avoiding LazyInitializationException in CardDto.from() when called outside a transaction.
     */
    @Query("""
        SELECT c FROM Card c
        LEFT JOIN FETCH c.sourceError e
        LEFT JOIN FETCH e.game
        WHERE c.id = :id
        """)
    Optional<Card> findByIdWithDetails(@Param("id") UUID id);

    // ── Re-analysis keeps drill history (Phase 12) ──────────────────────────
    //
    // Native SQL on purpose: these must see superseded move_errors rows, which
    // MoveError's @SQLRestriction hides from every JPQL query.

    /**
     * After a re-analysis of one game: each card still pointing at a superseded
     * row moves to the current row at the same ply. FSRS state and attempts stay
     * with the card.
     */
    @Modifying
    @Query(value = """
        UPDATE cards c SET source_error_id = cur.id, updated_at = now()
        FROM move_errors old, move_errors cur
        WHERE c.source_error_id = old.id
          AND old.game_id = :gameId AND old.superseded_at IS NOT NULL
          AND cur.game_id = old.game_id AND cur.move_number = old.move_number
          AND cur.superseded_at IS NULL
        """, nativeQuery = true)
    int relinkToCurrent(@Param("gameId") UUID gameId);

    /**
     * Then: cards whose move is no longer a mistake under the new analysis stop
     * being drilled, but are kept, with their history, on the old row.
     */
    @Modifying
    @Query(value = """
        UPDATE cards c SET status = 'SUSPENDED', updated_at = now()
        FROM move_errors old
        WHERE c.source_error_id = old.id
          AND old.game_id = :gameId AND old.superseded_at IS NOT NULL
          AND c.status <> 'SUSPENDED'
        """, nativeQuery = true)
    int suspendOrphans(@Param("gameId") UUID gameId);
}
