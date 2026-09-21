package com.praxis.prax.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Runs a question off the request thread, reporting progress as it goes.
 *
 * Separate from PraxReasoningService so that class keeps one job: answer a
 * question. This one is only about WHERE the work happens and who is told about
 * it — which is also why the @Async annotation lives here. Spring's proxying
 * means a self-invoked @Async method runs synchronously, so it must be on a bean
 * the caller reaches from outside.
 */
@Service
public class PraxRunner {

    private static final Logger log = LoggerFactory.getLogger(PraxRunner.class);

    private final PraxReasoningService reasoning;
    private final PraxRunRegistry runs;

    public PraxRunner(PraxReasoningService reasoning, PraxRunRegistry runs) {
        this.reasoning = reasoning;
        this.runs = runs;
    }

    @Async("praxExecutor")
    public void run(PraxRunRegistry.Run run, String question, UUID conversationId) {
        try {
            PraxReasoningService.Answer answer = reasoning.ask(question, conversationId,
                    (step, artifacts) -> runs.addStep(run, step, artifacts));
            runs.succeed(run, answer);
        } catch (Exception e) {
            // The run must always reach a terminal state. A client polling a run
            // that silently stopped would wait forever, which is worse than an
            // error it can show.
            log.warn("[prax] run {} threw: {}", run.id, e.getMessage(), e);
            runs.fail(run, e.getMessage() == null ? "Prax could not finish that" : e.getMessage());
        }
    }
}
