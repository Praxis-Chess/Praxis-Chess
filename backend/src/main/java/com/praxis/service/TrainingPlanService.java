package com.praxis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.domain.TrainingPlan;
import com.praxis.report.PlanFacts;
import com.praxis.report.ReportFacts;
import com.praxis.report.ReportWriter;
import com.praxis.report.TrainingPlanBuilder;
import com.praxis.repository.TrainingPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * The Training Plan (Phase 12).
 *
 * <p>Decided in code ({@link TrainingPlanBuilder}): which weaknesses, in what
 * order, what to drill and how often, from the same facts as the Pattern
 * Report. A model explains the plan in a few checked sentences; it never makes
 * one. Every generation adds a row; earlier plans stay as history.
 */
@Service
public class TrainingPlanService {

    private static final Logger log = LoggerFactory.getLogger(TrainingPlanService.class);

    private final TrainingPlanRepository trainingPlanRepository;
    private final PatternAggregator patterns;
    private final ReportWriter writer;
    private final ObjectMapper objectMapper;
    private final com.praxis.config.ReportProperties reports;
    private final com.praxis.repository.PlanDrillAnswerRepository answers;

    public TrainingPlanService(TrainingPlanRepository trainingPlanRepository,
                               PatternAggregator patterns,
                               ReportWriter writer,
                               ObjectMapper objectMapper,
                               com.praxis.config.ReportProperties reports,
                               com.praxis.repository.PlanDrillAnswerRepository answers) {
        this.trainingPlanRepository = trainingPlanRepository;
        this.answers = answers;
        this.patterns = patterns;
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.reports = reports;
    }

    /** Not transactional: the model call must not hold a database connection. */
    public TrainingPlan generate(String username) {
        ReportFacts facts = patterns.facts(username);
        if (facts.mistakes() == 0) {
            throw new IllegalStateException("No analysed mistakes for " + username + " yet. Analyse some games first.");
        }
        PlanFacts plan = TrainingPlanBuilder.build(facts);
        ReportWriter.Prose prose = reports.proseOn() ? writer.writePlan(facts, plan) : ReportWriter.Prose.off();

        TrainingPlan saved = trainingPlanRepository.save(TrainingPlan.builder()
                .username(username)
                .generatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .basedOnGames(plan.basedOnGames())
                .planJson(toJson(plan))
                .openingsToDrill(toJson(plan.openingsToReview().stream().map(ReportFacts.OpeningRow::name).toList()))
                .tacticalPatterns(toJson(plan.pattern() == null ? List.of() : List.of(plan.pattern().key())))
                .factsJson(toJson(facts))
                .proseJson(prose.text() == null ? null : toJson(prose.text()))
                .proseStatus(prose.status())
                .proseModel(prose.model())
                .proseViolations(prose.violations().isEmpty() ? null : toJson(prose.violations()))
                .proseAttempts(prose.attempts())
                .proseMillis(prose.millis())
                .build());
        log.info("Training plan for {}: {} priorities; prose {} by {} ({} ms)", username,
                plan.priorities().size(), prose.status(), prose.model(), prose.millis());
        return saved;
    }

    /** Record a finished session on this plan. Doing it again replaces the earlier result. */
    public Optional<TrainingPlan> sessionDone(java.util.UUID planId, int session, int solved, int total) {
        return trainingPlanRepository.findById(planId).map(p -> {
            java.util.Map<String, Object> progress = new java.util.LinkedHashMap<>();
            try {
                if (p.getProgressJson() != null) {
                    progress.putAll(objectMapper.readValue(p.getProgressJson(), java.util.LinkedHashMap.class));
                }
            } catch (Exception unreadable) {
                log.warn("Unreadable progress on plan {}; starting it again", planId);
            }
            progress.put(String.valueOf(session), java.util.Map.of(
                    "done_at", OffsetDateTime.now(ZoneOffset.UTC).toString(),
                    "solved", Math.max(0, solved), "total", Math.max(0, total)));
            p.setProgressJson(toJson(progress));
            return trainingPlanRepository.save(p);
        });
    }

    /** F10: how each focus of this plan has fared in games since it was made. Empty when there is no plan. */
    public Optional<com.praxis.report.SincePlan.Result> sincePlan(java.util.UUID planId) {
        return trainingPlanRepository.findById(planId).flatMap(p -> {
            if (p.getFactsJson() == null || p.getPlanJson() == null) return Optional.empty();   // made before Phase 12
            try {
                PlanFacts plan = objectMapper.readValue(p.getPlanJson(), PlanFacts.class);
                List<PlanFacts.Priority> foci = new java.util.ArrayList<>(plan.priorities());
                if (plan.pattern() != null) foci.add(plan.pattern());
                return Optional.of(patterns.sincePlan(p.getUsername(),
                        p.getGeneratedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate(), foci));
            } catch (Exception unreadable) {
                log.warn("Plan {} could not be read for 'since this plan': {}", planId, unreadable.getMessage());
                return Optional.empty();
            }
        });
    }

    /** One position answered in a plan session (F12). Empty when the plan does not exist. */
    public Optional<com.praxis.domain.PlanDrillAnswer> recordAnswer(java.util.UUID planId, int session,
                                                                     java.util.UUID gameId, int ply,
                                                                     java.util.UUID moveErrorId, boolean correct,
                                                                     int wrongTries, boolean revealed, Long responseMs) {
        if (!trainingPlanRepository.existsById(planId)) return Optional.empty();
        return Optional.of(answers.save(com.praxis.domain.PlanDrillAnswer.builder()
                .planId(planId).sessionNumber(session).gameId(gameId).ply(ply).moveErrorId(moveErrorId)
                .correct(correct && !revealed).wrongTries(Math.max(0, wrongTries)).revealed(revealed)
                .responseMs(responseMs == null || responseMs < 0 ? null : responseMs)
                .build()));
    }

    public Optional<TrainingPlan> getLatest(String username) {
        return trainingPlanRepository.findTopByUsernameOrderByGeneratedAtDesc(username);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
