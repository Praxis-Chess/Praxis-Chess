package com.praxis.repository;

import com.praxis.domain.MistakeEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MistakeEvidenceRepository extends JpaRepository<MistakeEvidence, UUID> {

    List<MistakeEvidence> findByUsername(String username);

    List<MistakeEvidence> findByUsernameAndLabelledAtIsNotNull(String username);

    long countByUsername(String username);

    long countByUsernameAndLabelledAtIsNotNull(String username);

    Optional<MistakeEvidence> findByGameIdAndMoveNumber(UUID gameId, int moveNumber);

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
}
