package com.praxis.service;

import com.praxis.dto.SetupDto.Check;
import com.praxis.dto.SetupDto.Model;
import com.praxis.dto.SetupDto.Status;
import com.praxis.service.setup.SetupStatusService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs at startup and logs clear warnings for any missing dependency so the
 * user knows what to fix before hitting Sync or Analyze.
 * Non-fatal — the app starts regardless so the user can fix one thing at a time.
 *
 * The checks themselves live in {@link SetupStatusService}, which also feeds
 * the Setup checklist in the UI: the log and the page say the same thing.
 */
@Service
public class PreflightCheckService {

    private static final Logger log = LoggerFactory.getLogger(PreflightCheckService.class);

    private final SetupStatusService setup;

    public PreflightCheckService(SetupStatusService setup) {
        this.setup = setup;
    }

    @PostConstruct
    public void runChecks() {
        log.info("=== Praxis Chess preflight checks ===");
        try {
            Status s = setup.status();
            for (Check c : s.checks()) {
                String line = "[{}] {}: {}" + (c.fix() == null ? "" : " — fix: " + c.fix());
                switch (c.state()) {
                    case OK, SKIP -> log.info(line, c.state(), c.label(), c.detail());
                    case WARN -> log.warn(line, c.state(), c.label(), c.detail());
                    case FAIL -> log.error(line, c.state(), c.label(), c.detail());
                }
            }
            for (Model m : s.models()) {
                log.info("       model {} ({}): {}", m.name(), m.role(), m.state());
            }
        } catch (Exception e) {
            // A broken check must never stop the app from starting.
            log.warn("[WARN] preflight checks failed: {}", e.getMessage());
        }
        log.info("=== Preflight complete — open http://localhost:8086 ===");
    }
}
