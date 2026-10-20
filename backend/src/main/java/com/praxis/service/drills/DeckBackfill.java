package com.praxis.service.drills;

import com.praxis.service.CardGeneratorService;
import com.praxis.service.settings.PlayerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The backfill (POLISH_PLAN.md D3a): when the app starts, games analysed before
 * the deck rule get their cards, through the same generator as after an analysis
 * run. It runs on every start, but generation is idempotent and capped, so after
 * the first start it adds only what a later analysis did not.
 */
@Component
public class DeckBackfill {

    private static final Logger log = LoggerFactory.getLogger(DeckBackfill.class);

    private final CardGeneratorService cardGenerator;
    private final PlayerIdentity player;

    public DeckBackfill(CardGeneratorService cardGenerator, PlayerIdentity player) {
        this.cardGenerator = cardGenerator;
        this.player = player;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        try {
            if (!player.isSet()) return;
            int n = cardGenerator.generateForUser(player.username());
            if (n > 0) log.info("Deck backfill: {} card(s) from games analysed earlier", n);
        } catch (Exception e) {
            log.warn("Deck backfill failed: {}", e.getMessage());
        }
    }
}
