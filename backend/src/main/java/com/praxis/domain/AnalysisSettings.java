package com.praxis.domain;

import com.praxis.service.analysis.AnalysisProfile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * One saved engine configuration. Rows are IMMUTABLE: changing a setting saves
 * a new row and deactivates the old one; nothing is edited in place.
 *
 * The reason is that depth changes the ruler. A deeper search re-rates moves, so
 * different moves get flagged and accuracy shifts. Every analysed game records
 * the row it was analysed under ({@link Game#getAnalysisSettingsId()}), and a
 * row that could be edited afterwards would make that record meaningless.
 *
 * `kind` is a plain string on purpose, not a JPA enum. Hibernate generates a
 * CHECK constraint for string enums, and ddl-auto never alters an existing one,
 * so adding a third kind later (the planned CORPUS profile) would fail at insert
 * time. That trap has been hit four times in this codebase already.
 */
@Entity
@Table(name = "analysis_settings", indexes = {
        @Index(name = "idx_analysis_settings_kind_active", columnList = "kind, active")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisSettings {

    public static final String LIBRARY = "LIBRARY";
    public static final String PRACTICE = "PRACTICE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@link #LIBRARY} or {@link #PRACTICE}. */
    @Column(nullable = false, length = 16)
    private String kind;

    /** Human-readable, e.g. "library-v0" or "library · depth 22 · 5 lines". */
    @Column(nullable = false, length = 64)
    private String label;

    @Column(name = "sweep_move_time_ms", nullable = false)
    private int sweepMoveTimeMs;

    @Column(name = "multi_pv_depth", nullable = false)
    private int multiPvDepth;

    @Column(name = "multi_pv_lines", nullable = false)
    private int multiPvLines;

    /** Written explanations per game. NULL means every flagged move. */
    @Column(name = "max_explanations")
    private Integer maxExplanations;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public AnalysisProfile toProfile() {
        return new AnalysisProfile(label, sweepMoveTimeMs, multiPvDepth, multiPvLines,
                maxExplanations == null ? Integer.MAX_VALUE : maxExplanations);
    }

    /** Same engine behaviour — the fields that decide what gets measured. */
    public boolean sameEngineAs(AnalysisSettings o) {
        return o != null
                && sweepMoveTimeMs == o.sweepMoveTimeMs
                && multiPvDepth == o.multiPvDepth
                && multiPvLines == o.multiPvLines
                && java.util.Objects.equals(maxExplanations, o.maxExplanations);
    }
}
