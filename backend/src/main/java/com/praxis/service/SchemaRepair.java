package com.praxis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Relaxes constraints that ddl-auto will not.
 *
 * THE TRAP, hit four times in this project now:
 *
 *   ddl-auto: update ADDS tables and columns. It never ALTERS an existing one.
 *
 * So a column created NOT NULL stays NOT NULL forever, no matter what the Java
 * says. Removing `nullable = false` from an entity changes the mapping and
 * nothing else — Hibernate simply stops complaining while PostgreSQL keeps
 * rejecting the insert.
 *
 * Previous instances: analysis_runs (int vs NULL rows), games.source (added as
 * NULL, never backfilled), and now games.chess_com_id — which silently broke
 * every practice game. The archive insert failed, so the game never reached the
 * games table, so no analysis ran, so no report appeared. The player saw
 * "analysis is taking longer than expected" forever.
 *
 * There is no Flyway in this project, so repairs live here. Each one is
 * idempotent and checks before it acts.
 */
@Component
@Order(0)   // before GameSourceBackfill, which writes to the same table
public class SchemaRepair implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaRepair.class);

    private final JdbcTemplate jdbc;

    public SchemaRepair(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deliberately NOT @Transactional, and every step is caught individually:
     * a repair that cannot run must degrade to a warning, never to a backend
     * that will not start.
     */
    @Override
    public void run(ApplicationArguments args) {
        dropNotNull("games", "chess_com_id",
                "practice games have no Chess.com identity");
    }

    private void dropNotNull(String table, String column, String why) {
        try {
            Boolean nullable = jdbc.queryForObject(
                    "SELECT is_nullable = 'YES' FROM information_schema.columns "
                            + "WHERE table_name = ? AND column_name = ?",
                    Boolean.class, table, column);

            // Column absent (fresh database, Hibernate has not created it yet in
            // this ordering) or already relaxed — nothing to do either way.
            if (nullable == null || nullable) return;

            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL");
            log.info("[schema] {}.{} is now nullable — {}", table, column, why);

        } catch (Exception e) {
            log.warn("[schema] could not relax {}.{}: {}", table, column, e.getMessage());
        }
    }
}
