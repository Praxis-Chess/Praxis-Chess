package com.praxis.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The evidence graph for one of the player's mistakes, the rules' labels for it,
 * and — once someone has looked — a human label (§17.1).
 *
 * <p><b>Keyed on the position, not on {@code move_errors}.</b> The plan called for
 * a foreign key to {@link MoveError}; this deliberately has none. Re-analysing a
 * game deletes its {@code move_errors} rows and writes new ones with new IDs, so
 * a foreign key would either block re-analysis or cascade-delete these rows —
 * including hand labels that took real time to make. A label is about a position
 * and a move, and (game, ply) identifies those across any number of
 * re-analyses. {@code moveErrorId} is kept as a plain, unconstrained reference to
 * whichever row it was built from.
 *
 * <p>Every taxonomy value is a plain string column, never a Java enum, because
 * Hibernate would turn an enum into a CHECK constraint that {@code ddl-auto:
 * update} never alters (§7.4) — the trap this project has hit four times.
 */
@Entity
@Table(name = "mistake_evidence",
        uniqueConstraints = @UniqueConstraint(name = "uk_mistake_evidence_position",
                columnNames = {"game_id", "move_number"}),
        indexes = {
                @Index(name = "idx_mistake_evidence_user_sample", columnList = "username, sample_key"),
                @Index(name = "idx_mistake_evidence_labelled", columnList = "labelled_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MistakeEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    /** Ply index in the game, as {@code move_errors.move_number}. */
    @Column(name = "move_number", nullable = false)
    private int moveNumber;

    @Column(name = "fen_before", nullable = false, columnDefinition = "text")
    private String fenBefore;

    @Column(name = "played_san", nullable = false, length = 16)
    private String playedSan;

    @Column(length = 16)
    private String severity;

    /** The move_errors row this was built from. No foreign key — see the class comment. */
    @Column(name = "move_error_id")
    private UUID moveErrorId;

    @Column(name = "graph_json", nullable = false, columnDefinition = "text")
    private String graphJson;

    @Column(name = "graph_version", nullable = false)
    private int graphVersion;

    @Column(name = "engine_depth", nullable = false)
    private int engineDepth;

    /** The settings version the game was analysed under, for like-for-like comparison. */
    @Column(name = "analysis_settings_id")
    private Long analysisSettingsId;

    // ── rules ────────────────────────────────────────────────────────────────

    @Column(name = "rule_consequence", length = 24)
    private String ruleConsequence;

    /** Every mechanism rule that fired, comma-separated, in rule order. */
    @Column(name = "rule_mechanisms", length = 160)
    private String ruleMechanisms;

    @Column(name = "rule_mechanism", length = 24)
    private String ruleMechanism;

    @Column(name = "rule_composite")
    private boolean ruleComposite;

    @Column(name = "rule_motif", length = 24)
    private String ruleMotif;

    @Column(name = "rule_visibility", length = 16)
    private String ruleVisibility;

    @Column(name = "rule_diagnosis_json", columnDefinition = "text")
    private String ruleDiagnosisJson;

    /** Whether the rules' own diagnosis passed the verifier. It must; a false here is a bug. */
    @Column(name = "rule_diagnosis_verified")
    private boolean ruleDiagnosisVerified;

    // ── budget (§6.6) ────────────────────────────────────────────────────────

    @Column(name = "item_count")
    private int itemCount;

    @Column(name = "token_estimate")
    private int tokenEstimate;

    @Column(name = "within_budget")
    private boolean withinBudget;

    /**
     * A stable pseudo-random position in the labelling queue. Labelling in this
     * order makes "the first 100 labelled" a random sample of the library rather
     * than its most recent games.
     */
    @Column(name = "sample_key", nullable = false)
    private int sampleKey;

    @Column(name = "built_at", nullable = false)
    private OffsetDateTime builtAt;

    // ── human label ──────────────────────────────────────────────────────────

    @Column(name = "human_consequence", length = 24)
    private String humanConsequence;

    @Column(name = "human_mechanism", length = 24)
    private String humanMechanism;

    @Column(name = "human_note", columnDefinition = "text")
    private String humanNote;

    @Column(name = "labelled_at")
    private OffsetDateTime labelledAt;
}
