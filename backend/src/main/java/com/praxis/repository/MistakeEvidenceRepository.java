package com.praxis.repository;

import com.praxis.domain.MistakeEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MistakeEvidenceRepository extends JpaRepository<MistakeEvidence, UUID> {

    List<MistakeEvidence> findByUsername(String username);

    long countByUsername(String username);

    long countByUsernameAndLabelledAtIsNotNull(String username);

    Optional<MistakeEvidence> findByGameIdAndMoveNumber(UUID gameId, int moveNumber);

    List<MistakeEvidence> findByGameId(UUID gameId);

    /**
     * How far the sample build has reached, in sample order. Rows built on demand
     * past this point are not part of the sample yet.
     */
    @Query("""
        SELECT max(e.sampleKey) FROM MistakeEvidence e
        WHERE e.username = :username AND (e.onDemand IS NULL OR e.onDemand = false)
        """)
    Integer sampleFrontier(@Param("username") String username);

    Optional<MistakeEvidence> findFirstByUsernameAndLabelledAtIsNullAndSampleKeyLessThanEqualOrderBySampleKeyAsc(
            String username, int sampleKey);

    /**
     * Copy the row as it is in the database into mistake_evidence_history, before
     * an in-place update overwrites it. Reads the stored row, so call it before
     * the change is saved.
     */
    @Transactional
    @Modifying
    @Query(value = """
        INSERT INTO mistake_evidence_history
            (id, evidence_id, game_id, move_number, graph_version, archived_at, reason, row_json)
        SELECT gen_random_uuid(), e.id, e.game_id, e.move_number, e.graph_version, now(), :reason,
               row_to_json(e)::text
        FROM mistake_evidence e WHERE e.id = :id
        """, nativeQuery = true)
    int archive(@Param("id") UUID id, @Param("reason") String reason);

    /**
     * Point each diagnosis at the current mistake row at its (game, ply). The
     * reference goes stale when a re-analysis writes new rows; it is a plain
     * column, not a foreign key, so it has to be refreshed.
     */
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE mistake_evidence e SET move_error_id = me.id
        FROM move_errors me
        WHERE me.game_id = e.game_id AND me.move_number = e.move_number
          AND me.superseded_at IS NULL
          AND e.move_error_id IS DISTINCT FROM me.id
          AND e.game_id = :gameId
        """, nativeQuery = true)
    int relinkToCurrentMistakes(@Param("gameId") UUID gameId);
}
