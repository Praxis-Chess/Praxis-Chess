package com.praxis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.config.ReportProperties;
import com.praxis.domain.PlanDrillAnswer;
import com.praxis.repository.PlanDrillAnswerRepository;
import com.praxis.repository.TrainingPlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F12: each answer in a plan session is kept per position, named by (game, ply). */
@DisplayName("Training Plan answers")
class TrainingPlanAnswerTest {

    private final TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
    private final PlanDrillAnswerRepository answers = mock(PlanDrillAnswerRepository.class);
    private final TrainingPlanService service = new TrainingPlanService(plans, null, null, new ObjectMapper(),
            new ReportProperties(false), answers);

    @Test
    @DisplayName("a revealed answer is never counted as correct, and negative inputs are clamped")
    void revealedIsNotCorrect() {
        UUID plan = UUID.randomUUID(), game = UUID.randomUUID();
        when(plans.existsById(plan)).thenReturn(true);
        when(answers.save(any())).thenAnswer(i -> i.getArgument(0));

        service.recordAnswer(plan, 2, game, 36, null, true, -3, true, -5L);

        ArgumentCaptor<PlanDrillAnswer> saved = ArgumentCaptor.forClass(PlanDrillAnswer.class);
        verify(answers).save(saved.capture());
        assertThat(saved.getValue().isCorrect()).isFalse();
        assertThat(saved.getValue().isRevealed()).isTrue();
        assertThat(saved.getValue().getWrongTries()).isZero();
        assertThat(saved.getValue().getResponseMs()).isNull();
        assertThat(saved.getValue().getGameId()).isEqualTo(game);
        assertThat(saved.getValue().getPly()).isEqualTo(36);
        assertThat(saved.getValue().getSessionNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("an answer for a plan that does not exist is not kept")
    void unknownPlan() {
        UUID plan = UUID.randomUUID();
        when(plans.existsById(plan)).thenReturn(false);

        assertThat(service.recordAnswer(plan, 1, UUID.randomUUID(), 10, null, true, 0, false, 900L)).isEmpty();
        verify(answers, never()).save(any());
    }
}
