package com.praxis.repository;

import com.praxis.domain.DrillSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DrillSessionRepository extends JpaRepository<DrillSession, UUID> {

    /** Most-recent incomplete session for this user — the one to resume. */
    @Query("""
        SELECT s FROM DrillSession s
        WHERE s.username = :username AND s.completed = false
        ORDER BY s.startedAt DESC
        """)
    Optional<DrillSession> findLatestIncomplete(@Param("username") String username);

    List<DrillSession> findByUsernameOrderByStartedAtDesc(String username);
}
