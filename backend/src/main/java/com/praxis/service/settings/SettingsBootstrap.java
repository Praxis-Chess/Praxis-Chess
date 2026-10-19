package com.praxis.service.settings;

import com.praxis.domain.AnalysisSettings;
import com.praxis.domain.AppSettings;
import com.praxis.repository.AnalysisSettingsRepository;
import com.praxis.repository.AppSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Makes sure the settings tables mean something on an existing install.
 *
 * <ol>
 *   <li>Creates the {@code library-v0} and {@code practice-v0} rows — the
 *       built-in {@code AnalysisProfile} values every game so far was analysed
 *       with — if no configuration exists yet.</li>
 *   <li>Attributes every already-analysed game with no recorded settings to the
 *       matching v0 row. Without this, the whole existing library would read as
 *       "unrecorded", and the first changed setting would look like the only
 *       version that ever existed.</li>
 * </ol>
 * Both steps are idempotent. Failures are logged and never stop startup:
 * analysis still works without the attribution, it's just less informative.
 */
@Component
@Order(3)   // after SchemaRepair (0), GameSourceBackfill (1), OpeningNameBackfill (2)
public class SettingsBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SettingsBootstrap.class);

    private final AnalysisSettingsRepository versions;
    private final AppSettingsRepository app;
    private final JdbcTemplate jdbc;

    public SettingsBootstrap(AnalysisSettingsRepository versions, AppSettingsRepository app, JdbcTemplate jdbc) {
        this.versions = versions;
        this.app = app;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!app.existsById(AppSettings.SINGLETON_ID)) {
                app.save(AppSettings.builder().id(AppSettings.SINGLETON_ID).build());
            }
            AnalysisSettings lib = ensureV0(AnalysisSettings.LIBRARY);
            AnalysisSettings pra = ensureV0(AnalysisSettings.PRACTICE);

            int libGames = attribute(lib.getId(), "COALESCE(source, 'CHESS_COM') = 'CHESS_COM'");
            int praGames = attribute(pra.getId(), "source = 'PRACTICE'");
            if (libGames + praGames > 0) {
                log.info("[settings] attributed {} library and {} practice games to their v0 settings",
                        libGames, praGames);
            }
        } catch (Exception e) {
            log.warn("[settings] bootstrap failed: {}", e.getMessage());
        }
        try {
            retireExplanations();
        } catch (Exception e) {
            log.warn("[settings] could not switch off per-move explanations: {}", e.getMessage());
        }
    }

    /**
     * Phase 12, once per install: per-move written explanations off. Every mistake
     * has a verified diagnosis instead, and the old model's text is no longer
     * shown. Recorded as a new settings version like any other change; a version
     * no game was analysed with yet (a fresh install) is changed in place.
     */
    private void retireExplanations() {
        AppSettings a = app.findById(AppSettings.SINGLETON_ID).orElse(null);
        if (a == null || a.getExplanationsRetiredAt() != null) return;
        for (String kind : java.util.List.of(AnalysisSettings.LIBRARY, AnalysisSettings.PRACTICE)) {
            AnalysisSettings cur = versions.findFirstByKindAndActiveTrueOrderByIdDesc(kind).orElse(null);
            if (cur == null || Integer.valueOf(0).equals(cur.getMaxExplanations())) continue;
            Integer used = jdbc.queryForObject(
                    "SELECT count(*) FROM games WHERE analysis_settings_id = ?", Integer.class, cur.getId());
            if (used == null || used == 0) {
                cur.setMaxExplanations(0);
                versions.save(cur);
                log.info("[settings] {}: per-move explanations off", cur.getLabel());
                continue;
            }
            cur.setActive(false);
            versions.save(cur);
            AnalysisSettings off = AnalysisSettings.builder()
                    .kind(kind)
                    .label(kind.toLowerCase(java.util.Locale.ROOT) + "-v" + versions.findByKindOrderByIdDesc(kind).size())
                    .sweepMoveTimeMs(cur.getSweepMoveTimeMs())
                    .multiPvDepth(cur.getMultiPvDepth())
                    .multiPvLines(cur.getMultiPvLines())
                    .maxExplanations(0)
                    .active(true)
                    .build();
            versions.save(off);
            log.info("[settings] {}: per-move explanations off (was {} per game, now settings {})",
                    kind, cur.getMaxExplanations() == null ? "all" : cur.getMaxExplanations(), off.getLabel());
        }
        a.setExplanationsRetiredAt(java.time.OffsetDateTime.now());
        app.save(a);
    }

    /** The v0 row for a kind, created if the kind has no rows at all. */
    private AnalysisSettings ensureV0(String kind) {
        String label = kind.toLowerCase(java.util.Locale.ROOT) + "-v0";
        return versions.findFirstByKindAndLabel(kind, label)
                .orElseGet(() -> {
                    AnalysisSettings v0 = SettingsService.v0(kind);
                    // Only the very first row is active. If other versions already
                    // exist (v0 deleted by hand?), recreate v0 as history, not as
                    // the live configuration.
                    v0.setActive(versions.findByKindOrderByIdDesc(kind).isEmpty());
                    return versions.save(v0);
                });
    }

    private int attribute(Long settingsId, String sourcePredicate) {
        return jdbc.update("UPDATE games SET analysis_settings_id = ? "
                + "WHERE analysis_status = 'ANALYZED' AND analysis_settings_id IS NULL AND "
                + sourcePredicate, settingsId);
    }
}
