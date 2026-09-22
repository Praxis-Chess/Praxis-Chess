package com.praxis.repository;

import com.praxis.domain.AnalysisTiming;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalysisTimingRepository extends JpaRepository<AnalysisTiming, UUID> {

    /** Most recent first — the estimate is based on the latest runs, not the oldest. */
    List<AnalysisTiming> findTop50BySettingsIdOrderByCreatedAtDesc(Long settingsId);
}
