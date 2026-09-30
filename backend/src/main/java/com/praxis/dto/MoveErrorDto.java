package com.praxis.dto;

import com.praxis.domain.MistakeEvidence;
import com.praxis.domain.MoveError;
import com.praxis.evidence.graph.GraphJson;

import java.util.UUID;

public record MoveErrorDto(
    UUID id,
    int moveNumber,
    String movePlayed,
    String betterMove,
    String fenPosition,
    String severity,
    String tacticalMotif,
    String explanation,
    String gamePhase,
    Integer clockRemaining,
    String analysisState,
    /** The rules' verified diagnosis (Phase 9); null until the mistake has a current one. */
    Verified verified
) {
    /**
     * What the rules concluded from the evidence, every claim checked against it.
     * The headline on Game Analysis since Phase 9: the rules won the pre-registered
     * comparison (training/reports/grid_v1.md), and they cover every mistake,
     * where the LLM wrote up at most three per game.
     */
    public record Verified(String consequence, String mechanism, String motif, String explanation, boolean passed,
                           String commentary, String commentaryModel) {

        static Verified of(MistakeEvidence e) {
            var d = GraphJson.readDiagnosis(e.getRuleDiagnosisJson());
            // Phase 9b: the trained model's commentary, only when every claim of it
            // passed the verifier; a failed answer never reaches the page.
            boolean checked = Boolean.TRUE.equals(e.getCommentaryVerified());
            return new Verified(e.getRuleConsequence(), e.getRuleMechanism(), e.getRuleMotif(),
                    d.explanation(), e.isRuleDiagnosisVerified(),
                    checked ? e.getCommentary() : null, checked ? e.getCommentaryModel() : null);
        }
    }

    public static MoveErrorDto from(MoveError e) {
        return from(e, null);
    }

    public static MoveErrorDto from(MoveError e, MistakeEvidence evidence) {
        return new MoveErrorDto(
                e.getId(), e.getMoveNumber(), e.getMovePlayed(), e.getBetterMove(),
                e.getFenPosition(), e.getSeverity() != null ? e.getSeverity().name() : null,
                e.getTacticalMotif() != null ? e.getTacticalMotif().name() : null,
                e.getExplanation(),
                e.getGamePhase() != null ? e.getGamePhase().name() : null,
                e.getClockRemaining(),
                e.getAnalysisState() != null ? e.getAnalysisState().name() : null,
                evidence == null ? null : Verified.of(evidence));
    }
}
