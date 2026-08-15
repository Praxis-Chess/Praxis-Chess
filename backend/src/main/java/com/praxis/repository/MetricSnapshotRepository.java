package com.praxis.repository;

import com.praxis.domain.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, UUID> {

    Optional<MetricSnapshot> findByUsernameAndSnapshotDate(String username, LocalDate date);

    List<MetricSnapshot> findByUsernameOrderBySnapshotDateDesc(String username);
}
