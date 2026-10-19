package com.praxis.dto;

import com.praxis.domain.MistakeEvidence;
import com.praxis.domain.MoveError;
import com.praxis.evidence.diagnosis.DiagnosisRules;
import com.praxis.evidence.diagnosis.TestGraphs;
import com.praxis.evidence.graph.GraphJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase 9: a mistake carries the rules' verified diagnosis to Game Analysis. */
@DisplayName("Mistake DTO")
class MoveErrorDtoTest {

    private static MoveError mistake() {
        var m = mock(MoveError.class);
        when(m.getMoveNumber()).thenReturn(6);
        when(m.getMovePlayed()).thenReturn("Nf6");
        return m;
    }

    @Test
    void carriesTheVerifiedDiagnosisWhenThereIsOne() {
        var diagnosis = DiagnosisRules.diagnose(TestGraphs.scholarsMate());
        var e = MistakeEvidence.builder()
                .ruleConsequence("MATED").ruleMechanism("IGNORED_THREAT").ruleMotif("OTHER")
                .ruleDiagnosisJson(GraphJson.write(diagnosis)).ruleDiagnosisVerified(true).build();

        var dto = MoveErrorDto.from(mistake(), e);

        assertThat(dto.verified()).isNotNull();
        assertThat(dto.verified().consequence()).isEqualTo("MATED");
        assertThat(dto.verified().mechanism()).isEqualTo("IGNORED_THREAT");
        assertThat(dto.verified().explanation()).isEqualTo(diagnosis.explanation()).isNotBlank();
        assertThat(dto.verified().passed()).isTrue();
    }

    /** Phase 9b: the model's commentary reaches the page only when every claim of it passed. */
    @Test
    void showsTheModelsCommentaryOnlyWhenItPassedTheVerifier() {
        var json = GraphJson.write(DiagnosisRules.diagnose(TestGraphs.scholarsMate()));
        var passed = MistakeEvidence.builder().ruleDiagnosisJson(json)
                .commentary("Nf6 ignores Qxf7#.").commentaryModel("praxis-grid-2b-r3").commentaryVerified(true).build();
        var failed = MistakeEvidence.builder().ruleDiagnosisJson(json)
                .commentary("Nf6 is a fork.").commentaryModel("praxis-grid-2b-r3").commentaryVerified(false).build();

        assertThat(MoveErrorDto.from(mistake(), passed).verified().commentary()).isEqualTo("Nf6 ignores Qxf7#.");
        assertThat(MoveErrorDto.from(mistake(), passed).verified().commentaryModel()).isEqualTo("praxis-grid-2b-r3");
        assertThat(MoveErrorDto.from(mistake(), failed).verified().commentary()).isNull();
    }

    /** Phase 12 (D2): the old model's text and label are kept in the database, never shown. */
    @Test
    void neverShowsTheOldModelsExplanationOrLabel() {
        var m = mistake();
        when(m.getExplanation()).thenReturn("An unchecked sentence from the old per-move model.");
        when(m.getTacticalMotif()).thenReturn(com.praxis.domain.enums.TacticalMotif.PIN);
        var e = MistakeEvidence.builder().ruleMotif("FORK").ruleMechanism("CREATED_TACTIC")
                .graphVersion(com.praxis.evidence.graph.EvidenceGraphBuilder.GRAPH_VERSION)
                .ruleDiagnosisJson(GraphJson.write(DiagnosisRules.diagnose(TestGraphs.scholarsMate())))
                .ruleDiagnosisVerified(true).build();

        var dto = MoveErrorDto.from(m, e);

        assertThat(dto.explanation()).isNull();
        assertThat(dto.tacticalMotif()).isEqualTo("FORK");
        assertThat(MoveErrorDto.from(m).tacticalMotif()).isNull();   // undiagnosed: no label at all
    }

    @Test
    void hasNoneBeforeTheMistakeIsDiagnosed() {
        assertThat(MoveErrorDto.from(mistake()).verified()).isNull();
    }
}
