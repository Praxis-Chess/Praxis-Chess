package com.praxis.repository;

import com.praxis.domain.Attempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    List<Attempt> findByCardIdOrderByCreatedAtDesc(UUID cardId);

    List<Attempt> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /** Wipe all drill attempts for a user — called before Re-analyze All to avoid FK violations. */
    @Modifying
    @Query("DELETE FROM Attempt a WHERE a.card.username = :username")
    void deleteByCardUsername(@Param("username") String username);

    /** Most-recent N attempts for a card — used to check graduation criteria. */
    @Query(value = """
        SELECT * FROM attempts WHERE card_id = :cardId
        ORDER BY created_at DESC LIMIT :n
        """, nativeQuery = true)
    List<Attempt> findLastNByCardId(@Param("cardId") UUID cardId, @Param("n") int n);
}
