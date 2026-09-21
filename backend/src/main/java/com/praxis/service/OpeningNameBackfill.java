package com.praxis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Fills in opening names for games that were analysed with only an ECO code.
 *
 * GameAnalysisTransactionService used to resolve the name inside its
 * {@code openingEco == null} branch. The sync writes opening_eco straight from
 * the Chess.com API, so for every synced game that branch never ran and the
 * name was never set — 93 of 104 games came out as bare ECO codes.
 *
 * Prax is not the victim here: {@code ChessIntelligence.openingLabel} already
 * resolves a blank name from the ECO table at read time, because this gap was
 * known. The readers WITHOUT that fallback are the ones showing bare codes —
 * {@code GameSummaryDto} (the Library list), {@code DashboardController},
 * {@code InsightsService}, and the frontend pages built on them, where an
 * opening reads "C41" instead of "Philidor Defense".
 *
 * Fixing the pipeline stops it happening to new games; nothing re-analyses a
 * hundred already-analysed ones, so the existing rows need this.
 *
 * Name only — the ECO is left exactly as the sync wrote it.
 */
@Component
@Order(2)   // after GameSourceBackfill, before anything that reads openings
public class OpeningNameBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpeningNameBackfill.class);

    private final JdbcTemplate jdbc;
    private final EcoTable ecoTable;

    public OpeningNameBackfill(JdbcTemplate jdbc, EcoTable ecoTable) {
        this.jdbc = jdbc;
        this.ecoTable = ecoTable;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!ecoTable.isLoaded()) {
            log.warn("[openings] ECO table is empty — skipping backfill");
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT DISTINCT opening_eco FROM games
                    WHERE (opening_name IS NULL OR opening_name = '')
                      AND opening_eco IS NOT NULL AND opening_eco <> ''
                    """);
            if (rows.isEmpty()) return;

            int named = 0;
            int unknown = 0;

            // One UPDATE per distinct ECO rather than per row: 30 statements
            // instead of 93, and the set is bounded by the ECO table either way.
            for (Map<String, Object> row : rows) {
                String eco = (String) row.get("opening_eco");
                String name = ecoTable.lookup(eco);
                if (name == null || name.isBlank()) {
                    unknown++;
                    continue;
                }
                named += jdbc.update("""
                        UPDATE games SET opening_name = ?
                        WHERE opening_eco = ? AND (opening_name IS NULL OR opening_name = '')
                        """, name, eco);
            }

            if (named > 0) {
                log.info("[openings] named {} games from {} ECO codes", named, rows.size() - unknown);
            }
            if (unknown > 0) {
                // Not an error: the table covers the 500 common codes, not all
                // of them. Said out loud so a gap is visible rather than silent.
                log.info("[openings] {} ECO code(s) are not in eco.properties", unknown);
            }
        } catch (Exception e) {
            // A missing name degrades opening filters; it must not stop startup.
            log.warn("[openings] backfill failed: {}", e.getMessage());
        }
    }
}
