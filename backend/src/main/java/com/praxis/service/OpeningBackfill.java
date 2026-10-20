package com.praxis.service;

import com.praxis.domain.Game;
import com.praxis.repository.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Names the openings of games synced before sync read them (N11): at startup,
 * every game with no ECO gets one from its PGN header, and a name from the ECO
 * table. Idempotent: a game that has an ECO is never touched.
 */
@Component
public class OpeningBackfill {

    private static final Logger log = LoggerFactory.getLogger(OpeningBackfill.class);

    private final GameRepository games;
    private final EcoTable ecoTable;

    public OpeningBackfill(GameRepository games, EcoTable ecoTable) {
        this.games = games;
        this.ecoTable = ecoTable;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfill() {
        try {
            List<Game> todo = games.findByOpeningEcoIsNull();
            int named = 0;
            for (Game g : todo) {
                String eco = OpeningFromPgn.eco(g.getRawPgn());
                if (eco == null) continue;
                g.setOpeningEco(eco);
                if (g.getOpeningName() == null || g.getOpeningName().isBlank()) g.setOpeningName(ecoTable.lookup(eco));
                named++;
            }
            if (named > 0) {
                games.saveAll(todo);
                log.info("Opening backfill: named {} game(s) from their PGN headers", named);
            }
        } catch (Exception e) {
            log.warn("Opening backfill failed: {}", e.getMessage());
        }
    }
}
