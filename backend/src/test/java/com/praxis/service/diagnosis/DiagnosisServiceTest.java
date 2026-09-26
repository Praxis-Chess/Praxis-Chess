package com.praxis.service.diagnosis;

import com.praxis.config.AppProperties;
import com.praxis.domain.MistakeEvidence;
import com.praxis.evidence.EvidenceEngine;
import com.praxis.evidence.diagnosis.TestGraphs;
import com.praxis.evidence.graph.EvidenceGraphBuilder;
import com.praxis.evidence.graph.GraphJson;
import com.praxis.repository.MistakeEvidenceRepository;
import com.praxis.repository.MoveErrorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The product path into the evidence store, and the labelling queue's guard
 * against it. Engine-free: these cover the paths that do not build.
 */
@DisplayName("Diagnosis service")
class DiagnosisServiceTest {

    private static final UUID GAME = UUID.fromString("7c3c9a2e-1111-4a22-8b33-445566778899");

    private MistakeEvidenceRepository evidence;
    private MoveErrorRepository moveErrors;
    private EvidenceEngine engine;
    private DiagnosisService service;

    @BeforeEach
    void setUp() {
        evidence = mock(MistakeEvidenceRepository.class);
        moveErrors = mock(MoveErrorRepository.class);
        engine = mock(EvidenceEngine.class);
        AppProperties props = mock(AppProperties.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(props.chessCom().username()).thenReturn("player");
        service = new DiagnosisService(moveErrors, evidence, engine, props);
    }

    private static MistakeEvidence row(int version) {
        return MistakeEvidence.builder()
                .gameId(GAME).moveNumber(6).username("player")
                .graphJson(GraphJson.write(TestGraphs.scholarsMate()))
                .graphVersion(version).sampleKey(10).build();
    }

    /** A current row is read, never rebuilt: the engine is not touched. */
    @Test
    void aStoredDiagnosisIsReadNotRebuilt() {
        when(evidence.findByGameIdAndMoveNumber(GAME, 6))
                .thenReturn(Optional.of(row(EvidenceGraphBuilder.GRAPH_VERSION)));

        var why = service.why(GAME, 6);

        assertThat(why).isPresent();
        assertThat(why.get().moveLabel()).isEqualTo("3... Nf6");
        assertThat(why.get().diagnosis().mechanism()).isEqualTo("IGNORED_THREAT");
        assertThat(why.get().facts()).isNotEmpty();
        verifyNoInteractions(engine, moveErrors);
    }

    @Test
    void noMistakeAtThatPlyIsEmpty() {
        when(evidence.findByGameIdAndMoveNumber(GAME, 7)).thenReturn(Optional.empty());
        when(moveErrors.findByGameIdAndPlyWithGame(GAME, 7)).thenReturn(List.of());

        assertThat(service.why(GAME, 7)).isEmpty();
        verifyNoInteractions(engine);
    }

    /**
     * The queue only reaches as far as the sample build has. A mistake someone
     * opened on Game Analysis, built on demand past that point, waits its turn.
     */
    @Test
    void theLabellingQueueStopsAtTheSampleFrontier() {
        when(evidence.sampleFrontier("player")).thenReturn(500);
        when(evidence.findFirstByUsernameAndLabelledAtIsNullAndSampleKeyLessThanEqualOrderBySampleKeyAsc("player", 500))
                .thenReturn(Optional.empty());

        assertThat(service.next()).isEmpty();
        verify(evidence).findFirstByUsernameAndLabelledAtIsNullAndSampleKeyLessThanEqualOrderBySampleKeyAsc("player", 500);
    }

    /**
     * A review changes the label but keeps the FIRST labelling time: labelled_at
     * is what separates the labels the rules were tuned on from fresh ones.
     */
    @Test
    void aReviewKeepsTheFirstLabellingTime() {
        var first = java.time.OffsetDateTime.parse("2026-09-23T20:00:00Z");
        var labelled = row(EvidenceGraphBuilder.GRAPH_VERSION);
        labelled.setLabelledAt(first);
        labelled.setHumanConsequence("NOT_CONCRETE");
        labelled.setHumanMechanism("NONE");
        UUID id = UUID.randomUUID();
        when(evidence.findById(id)).thenReturn(Optional.of(labelled));

        service.label(id, "LOST_MATERIAL", "IGNORED_THREAT", null);

        assertThat(labelled.getLabelledAt()).isEqualTo(first);
        assertThat(labelled.getRelabelledAt()).isNotNull();
        assertThat(labelled.getHumanMechanism()).isEqualTo("IGNORED_THREAT");
    }

    @Test
    void aFirstLabelSetsTheLabellingTime() {
        var unlabelled = row(EvidenceGraphBuilder.GRAPH_VERSION);
        UUID id = UUID.randomUUID();
        when(evidence.findById(id)).thenReturn(Optional.of(unlabelled));

        service.label(id, "MATED", "IGNORED_THREAT", null);

        assertThat(unlabelled.getLabelledAt()).isNotNull();
        assertThat(unlabelled.getRelabelledAt()).isNull();
    }

    /** Only labels after the first hundred count as fresh, ordered by first labelling time. */
    @Test
    void freshScoresCountOnlyLabelsAfterTheFirstHundred() {
        var base = java.time.OffsetDateTime.parse("2026-09-23T00:00:00Z");
        List<MistakeEvidence> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 103; i++) {
            var e = row(EvidenceGraphBuilder.GRAPH_VERSION);
            e.setLabelledAt(base.plusMinutes(i));
            e.setRuleConsequence("LOST_MATERIAL");
            e.setRuleMechanism("IGNORED_THREAT");
            e.setRuleComposite(false);
            // The first 100 disagree; the 3 fresh ones agree.
            e.setHumanConsequence(i < 100 ? "NOT_CONCRETE" : "LOST_MATERIAL");
            e.setHumanMechanism(i < 100 ? "NONE" : "IGNORED_THREAT");
            rows.add(e);
        }
        java.util.Collections.shuffle(rows, new java.util.Random(1));

        var fresh = DiagnosisService.fresh(rows);

        assertThat(fresh.labelled()).isEqualTo(3);
        assertThat(fresh.consequenceAgreement()).isEqualTo(1.0);
        assertThat(fresh.singleCauseAccuracy()).isEqualTo(1.0);
    }

    /** Before any sample build, only on-demand rows exist: nothing is up for labelling. */
    @Test
    void onDemandRowsAloneAreNotASample() {
        when(evidence.sampleFrontier("player")).thenReturn(null);

        assertThat(service.next()).isEmpty();
        verify(evidence, never())
                .findFirstByUsernameAndLabelledAtIsNullAndSampleKeyLessThanEqualOrderBySampleKeyAsc(anyString(), anyInt());
    }
}
