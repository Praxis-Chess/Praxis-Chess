package com.praxis.service.ai;

import java.util.List;

public final class PromptTemplates {

    private PromptTemplates() {}

    /**
     * Per-move explanation prompt. Engine supplies better_move, eval delta, and top lines;
     * LLM only needs to explain WHY and classify the tactical motif.
     */
    public static String moveAnalysis(String fenBefore, String sanPlayed, String bestMoveUci,
                                      double evalBefore, double evalAfter,
                                      String playerColor, String phase, int moveNumber,
                                      List<String> engineLines) {
        String engineMove = bestMoveUci != null ? bestMoveUci : "unknown";
        double lost = Math.abs(evalAfter - evalBefore);

        StringBuilder linesSection = new StringBuilder();
        if (!engineLines.isEmpty()) {
            linesSection.append("\nEngine top lines (UCI, first 4 moves each):");
            for (int i = 0; i < engineLines.size(); i++) {
                linesSection.append("\n  ").append(i + 1).append(". ").append(engineLines.get(i));
            }
        }

        return """
            You are a chess coach. Explain concisely why the played move is worse than the engine's move.
            Do NOT suggest moves — the engine move is already determined.

            Position (FEN): %s
            Move played: %s  (eval: %+.2f → %+.2f, lost %.2f pawns)
            Engine best:  %s%s
            Phase: %s | Move #%d | Player: %s

            Respond ONLY with JSON:
            {
              "explanation": "<one sentence: why the played move is bad>",
              "tactical_motif": "FORK" | "PIN" | "SKEWER" | "BACK_RANK" | "DISCOVERED_ATTACK" | "HANGING_PIECE" | "POSITIONAL" | "OTHER"
            }
            """.formatted(fenBefore, sanPlayed, evalBefore, evalAfter, lost,
                          engineMove, linesSection, phase, moveNumber, playerColor);
    }

}
