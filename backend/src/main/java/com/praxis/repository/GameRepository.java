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

public interface GameRepository extends JpaRepository<Game, UUID> {

    boolean existsByChessComId(String chessComId);

    List<Game> findByUsernameOrderByPlayedAtDesc(String username);

    List<Game> findByUsernameAndAnalysisStatus(String username, AnalysisStatus status);

    long countByUsernameAndAnalysisStatus(String username, AnalysisStatus status);

    @Query("SELECT g FROM Game g WHERE g.username = :username ORDER BY g.playedAt DESC")
    List<Game> findRecentByUsername(String username);

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
