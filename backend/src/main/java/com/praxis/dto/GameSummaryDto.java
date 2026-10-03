package com.praxis.dto;

import com.praxis.domain.Game;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GameSummaryDto(
    UUID id,
    String chessComId,
    OffsetDateTime playedAt,
    String timeClass,
    String timeControl,
    String playerColor,
    String result,
    String openingEco,
    String openingName,
    String analysisStatus,
    Integer playerRating,
    Double accuracy,
    int mistakeCount,
    /** Inside the analysis scope (time control and date range)? An unanalysed game
     *  outside it is synced on purpose, not waiting for analysis. */
    boolean inAnalysisScope
) {
    public static GameSummaryDto from(Game g) {
        return from(g, 0, true);
    }

    public static GameSummaryDto from(Game g, int mistakeCount, boolean inAnalysisScope) {
        int rating = "white".equals(g.getPlayerColor())
                ? (g.getWhiteRating() != null ? g.getWhiteRating() : 0)
                : (g.getBlackRating() != null ? g.getBlackRating() : 0);
        return new GameSummaryDto(
                g.getId(), g.getChessComId(), g.getPlayedAt(),
                g.getTimeClass(), g.getTimeControl(), g.getPlayerColor(),
                g.getResult(), g.getOpeningEco(), g.getOpeningName(),
                g.getAnalysisStatus().name(), rating,
                g.getAccuracy(), mistakeCount, inAnalysisScope);
    }
}
