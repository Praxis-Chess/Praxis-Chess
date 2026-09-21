package com.praxis.service;

import com.praxis.repository.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Stamps CHESS_COM onto games that predate the `source` column.
 *
 * ddl-auto adds a new column to existing rows as NULL and never backfills it —
 * {@code @Builder.Default} only applies to entities constructed in Java. Every
 * row written before this column existed came from Chess.com, since practice
 * games could not exist yet.
 *
 * The repository also treats NULL as CHESS_COM, so reads are correct even if
 * this never runs. Belt and braces on purpose: the version without the belt
 * made every statistic in the app report one game instead of a hundred.
 */
@Component
@Order(1)   // before anything that reads games
public class GameSourceBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GameSourceBackfill.class);

    private final GameRepository games;

    public GameSourceBackfill(GameRepository games) {
        this.games = games;
    }

    /**
     * Deliberately NOT @Transactional: the repair method carries its own
     * transaction, and a failure here must not take startup down with it — the
     * NULL-tolerant read keeps the app correct regardless.
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            long pending = games.countBySourceIsNull();
            if (pending == 0) return;
            int updated = games.backfillNullSource();
            log.info("[games] stamped {} legacy game(s) as CHESS_COM", updated);
        } catch (Exception e) {
            log.warn("[games] source backfill skipped: {}", e.getMessage());
        }
    }
}
