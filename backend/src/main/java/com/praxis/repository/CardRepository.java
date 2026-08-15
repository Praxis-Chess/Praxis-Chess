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

    /** Wipe all FSRS cards for a user — called before Re-analyze All to avoid FK violations. */
    @Modifying
    @Query("DELETE FROM Card c WHERE c.username = :username")
    void deleteByUsername(@Param("username") String username);
}
