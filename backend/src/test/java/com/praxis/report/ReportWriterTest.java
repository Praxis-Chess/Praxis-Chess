package com.praxis.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.ai.Feature;
import com.praxis.ai.LlmProvider;
import com.praxis.ai.ModelRouter;
import com.praxis.domain.enums.GamePhase;
import com.praxis.domain.enums.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 12: the model writes words around computed facts; a checked answer is
 * kept, a wrong one gets one retry, and a second failure shows no prose at all.
 */
@DisplayName("Report writer")
class ReportWriterTest {

    private LlmProvider provider;
    private ReportWriter writer;
    private ReportFacts facts;

    @BeforeEach
    void setUp() {
        provider = mock(LlmProvider.class);
        ModelRouter router = mock(ModelRouter.class);
        when(router.forFeature(Feature.REPORTS))
                .thenReturn(new ModelRouter.Route(Feature.REPORTS, provider, "qwen3:4b-instruct", false));
        writer = new ReportWriter(router, new ObjectMapper());

        // 10 blunders, all forks that allowed a tactic, in the middlegame of 10 games.
        List<ReportFactsBuilder.Mistake> ms = new ArrayList<>();
        List<ReportFactsBuilder.GameRow> games = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID g = UUID.randomUUID();
            games.add(new ReportFactsBuilder.GameRow(g, "C50", "Italian Game"));
            ms.add(new ReportFactsBuilder.Mistake(g, 41, Severity.BLUNDER, GamePhase.MIDDLEGAME, null, 30.0 + i,
                    "fen", "Nf6", "e2e4", LocalDate.of(2026, 9, 1), "Italian Game", true, "FORK", "CREATED_TACTIC"));
        }
        facts = ReportFactsBuilder.build(ms, games);
    }

    private static final String GOOD = """
            {"summary": "All 10 of your serious mistakes were blunders in the middlegame, and forks caught you in each.",
             "weaknesses": ["Your moves allowed a tactic 10 times."],
             "openings": ""}""";

    @Test
    void aCheckedAnswerIsKept() {
        when(provider.completeWithRetry(any())).thenReturn(GOOD);

        ReportWriter.Prose p = writer.writeReport(facts);

        assertThat(p.status()).isEqualTo(ReportWriter.Prose.CHECKED);
        assertThat(p.attempts()).isEqualTo(1);
        assertThat(p.text()).containsKey("summary");
        assertThat(p.model()).isEqualTo("qwen3:4b-instruct");
    }

    @Test
    void aWrongAnswerIsRetriedOnceWithItsFailuresListed() {
        String wrong = GOOD.replace("All 10 of", "80% of");
        when(provider.completeWithRetry(any())).thenReturn(wrong, GOOD);

        ReportWriter.Prose p = writer.writeReport(facts);

        assertThat(p.status()).isEqualTo(ReportWriter.Prose.CHECKED);
        assertThat(p.attempts()).isEqualTo(2);
        verify(provider).completeWithRetry(argThat(r -> r.prompt().contains("the number 80 is not in the facts")));
    }

    @Test
    void twoWrongAnswersMeanNoProseIsShown() {
        String wrong = GOOD.replace("All 10 of", "80% of");
        when(provider.completeWithRetry(any())).thenReturn(wrong, wrong);

        ReportWriter.Prose p = writer.writeReport(facts);

        assertThat(p.status()).isEqualTo(ReportWriter.Prose.REJECTED);
        assertThat(p.text()).isNull();
        assertThat(p.violations()).contains("the number 80 is not in the facts");
        verify(provider, times(2)).completeWithRetry(any());
    }

    @Test
    void theWrongNumberOfWeaknessParagraphsIsAFailure() {
        String none = GOOD.replace("\"Your moves allowed a tactic 10 times.\"", "");
        when(provider.completeWithRetry(any())).thenReturn(none, none);

        assertThat(writer.writeReport(facts).violations()).contains("\"weaknesses\" must have exactly 1 entries");
    }

    @Test
    void noModelMeansNoProseNotAnError() {
        when(provider.completeWithRetry(any())).thenThrow(new RuntimeException("connection refused"));

        ReportWriter.Prose p = writer.writeReport(facts);

        assertThat(p.status()).isEqualTo(ReportWriter.Prose.UNAVAILABLE);
        assertThat(p.text()).isNull();
    }

    @Test
    void thePlanIsDecidedInCodeAndOnlyExplained() {
        PlanFacts plan = TrainingPlanBuilder.build(facts);
        // Causes are the priorities. Forks are the same 10 positions, so they are not added as a
        // separate pattern: two entries over one set of positions read as a duplicate.
        assertThat(plan.priorities()).extracting(PlanFacts.Priority::key).containsExactly("CREATED_TACTIC");
        assertThat(plan.pattern()).isNull();
        assertThat(plan.priorities().get(0).why()).isEqualTo("10 of 10 serious mistakes (100%), 10 of them blunders.");
        // 10 positions: a full first session of 6, then 4; never a position twice.
        assertThat(plan.sessions()).extracting(s -> s.positions().size()).containsExactly(6, 4);
        assertThat(plan.sessions().get(0).goal()).isEqualTo("Check what your move allows");

        when(provider.completeWithRetry(any())).thenReturn("""
                {"intro": "Three sessions a week of 20 minutes each.",
                 "priorities": ["Allowing tactics comes first: 10 of your blunders."]}""");
        ReportWriter.Prose p = writer.writePlan(facts, plan);
        assertThat(p.status()).isEqualTo(ReportWriter.Prose.CHECKED);
    }
}
