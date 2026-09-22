package com.praxis.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * The date ranges for sync and analysis. A single row (id = 1).
 *
 * Every bound is nullable: a null bound means "no limit on that side", and a
 * fully-null range means "behave as before" — Sync Now fetches the current
 * month, Re-Sync the last three, and analysis takes every pending game. So an
 * install that never opens the Settings page is unaffected.
 *
 * Kept separate from {@link AnalysisSettings} because date ranges don't change
 * how a game is measured, only which games are fetched and analysed. They are
 * not versioned, and games don't record them.
 */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "sync_from")
    private LocalDate syncFrom;

    @Column(name = "sync_to")
    private LocalDate syncTo;

    @Column(name = "analysis_from")
    private LocalDate analysisFrom;

    @Column(name = "analysis_to")
    private LocalDate analysisTo;
}
