package com.praxis.play.repository;

import com.praxis.play.domain.PracticeGame;
import com.praxis.play.domain.enums.PracticeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PracticeGameRepository extends JpaRepository<PracticeGame, UUID> {

    List<PracticeGame> findByUsernameOrderByStartedAtDesc(String username);

    List<PracticeGame> findByUsernameAndStatusOrderByStartedAtDesc(String username, PracticeStatus status);

    /**
     * Games eligible for improvement tracking: finished, analysed, and not
     * undone. Rated games only — an undone game is shown to the player but must
     * never move a baseline, or every later comparison inherits the distortion.
     */
    @Query("""
        SELECT p FROM PracticeGame p
        WHERE p.username = :username
          AND p.status = com.praxis.play.domain.enums.PracticeStatus.FINISHED
          AND p.rated = true
          AND p.gameId IS NOT NULL
        ORDER BY p.finishedAt DESC
        """)
    List<PracticeGame> findRatedFinished(@Param("username") String username);
}
