package com.praxis.repository;

import com.praxis.domain.PlanDrillAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanDrillAnswerRepository extends JpaRepository<PlanDrillAnswer, UUID> {

}
