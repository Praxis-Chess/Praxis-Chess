package com.praxis.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Wire types for the Settings page.
 *
 * All records, deliberately. Jackson's SNAKE_CASE naming applies to record
 * components but NOT to Map keys — the bug behind /api/play/session/undefined —
 * so every structured field is a component. The one Map ({@link Invalid#errors})
 * uses keys chosen here in snake_case, because they are field names the client
 * matches literally.
 */
public final class SettingsDto {

    private SettingsDto() {}

    // ── engine ────────────────────────────────────────────────────────────────

    /** An engine configuration as submitted. Boxed so a missing field is detectable. */
    public record EngineConfig(Integer sweepMoveTimeMs, Integer multiPvDepth,
                               Integer multiPvLines, Integer maxExplanations) {}

    /** A saved, immutable configuration. {@code maxExplanations == null} means every flagged move. */
    public record EngineVersion(Long id, String label, int sweepMoveTimeMs, int multiPvDepth,
                                int multiPvLines, Integer maxExplanations, OffsetDateTime createdAt) {}

    public record Bounds(int sweepMin, int sweepMax, int depthMin, int depthMax,
                         int linesMin, int linesMax, int explanationsMax,
                         LocalDate earliestDate, long practiceBudgetMs) {}

    // ── settings ──────────────────────────────────────────────────────────────

    public record View(LocalDate syncFrom, LocalDate syncTo,
                       LocalDate analysisFrom, LocalDate analysisTo,
                       EngineVersion library, EngineVersion practice, Bounds bounds) {}

    public record Update(LocalDate syncFrom, LocalDate syncTo,
                         LocalDate analysisFrom, LocalDate analysisTo,
                         EngineConfig library, EngineConfig practice) {}

    /** The result of a save: which kinds got a new version. */
    public record Saved(View settings, boolean libraryVersionCreated, boolean practiceVersionCreated) {}

    /** 400 body: field → message. */
    public record Invalid(Map<String, String> errors) {}

    // ── coverage ──────────────────────────────────────────────────────────────

    public record SettingsCount(Long settingsId, String label, int games) {}

    public record DayCoverage(LocalDate date, int synced, int analyzed, int pending, int failed) {}

    public record MonthCoverage(String month, int synced, int analyzed, int pending, int failed,
                                List<SettingsCount> analyzedWith, List<DayCoverage> days) {}

    public record PracticeCoverage(int played, int analyzed) {}

    /**
     * @param mixedSettings  analysed library games span more than one settings version
     * @param outdatedInRange analysed library games inside the analysis range that
     *                        were NOT analysed with the active version
     */
    public record Coverage(LocalDate firstGame, LocalDate lastGame,
                           int synced, int analyzed, int pending, int failed,
                           List<MonthCoverage> months, PracticeCoverage practice,
                           Long activeLibrarySettingsId, boolean mixedSettings,
                           List<SettingsCount> analyzedWith, int outdatedInRange) {}

    // ── estimate ──────────────────────────────────────────────────────────────

    /**
     * @param measured    false when fewer than AnalysisCostModel.MIN_SAMPLES games
     *                    have timings — no number is shown rather than a guess
     * @param basisLabel  the settings version the timings were measured under
     */
    public record KindEstimate(boolean measured, int samples, String basisLabel,
                               Long perGameMs, boolean explanationsMeasured) {}

    public record Estimate(KindEstimate library, int gamesInRange, Long libraryTotalMs,
                           KindEstimate practice, long practiceBudgetMs, Boolean practiceWithinBudget) {}

    public record EstimateRequest(EngineConfig library, EngineConfig practice) {}
}
