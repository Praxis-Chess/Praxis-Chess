package com.praxis.repository;

import com.praxis.domain.AnalysisSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisSettingsRepository extends JpaRepository<AnalysisSettings, Long> {

    Optional<AnalysisSettings> findFirstByKindAndActiveTrueOrderByIdDesc(String kind);

    Optional<AnalysisSettings> findFirstByKindAndLabel(String kind, String label);

    List<AnalysisSettings> findByKindOrderByIdDesc(String kind);
}
