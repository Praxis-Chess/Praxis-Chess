package com.praxis.repository;

import com.praxis.domain.MistakeEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MistakeEvidenceRepository extends JpaRepository<MistakeEvidence, UUID> {

    List<MistakeEvidence> findByUsername(String username);

    Optional<MistakeEvidence> findFirstByUsernameAndLabelledAtIsNullOrderBySampleKeyAsc(String username);

    List<MistakeEvidence> findByUsernameAndLabelledAtIsNotNull(String username);

    long countByUsername(String username);

    long countByUsernameAndLabelledAtIsNotNull(String username);
}
