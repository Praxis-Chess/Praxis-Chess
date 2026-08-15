package com.praxis.api;

import com.praxis.config.AppProperties;
import com.praxis.domain.Card;
import com.praxis.domain.DrillSession;
import com.praxis.dto.AttemptRequest;
import com.praxis.dto.CardDto;
import com.praxis.dto.SessionDto;
import com.praxis.repository.CardRepository;
import com.praxis.repository.DrillSessionRepository;
import com.praxis.service.CardGeneratorService;
import com.praxis.service.drills.SessionEngineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Session-based drill endpoints.
 *
 * POST /api/sessions          → start or resume a session
 * GET  /api/sessions/{id}     → get session status
 * GET  /api/sessions/{id}/next → get next card in the session queue
 * POST /api/sessions/{id}/attempt → record an attempt and advance FSRS
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionEngineService sessionEngine;
    private final CardGeneratorService cardGenerator;
    private final CardRepository cardRepository;
    private final DrillSessionRepository sessionRepository;
    private final AppProperties appProperties;

    public SessionController(SessionEngineService sessionEngine,
                             CardGeneratorService cardGenerator,
                             CardRepository cardRepository,
                             DrillSessionRepository sessionRepository,
                             AppProperties appProperties) {
        this.sessionEngine = sessionEngine;
        this.cardGenerator = cardGenerator;
        this.cardRepository = cardRepository;
        this.sessionRepository = sessionRepository;
        this.appProperties = appProperties;
    }

    /** Start or resume a session. Generates new cards first if the deck is thin. */
    @PostMapping
    public ResponseEntity<SessionDto> startSession() {
        String username = appProperties.chessCom().username();
        cardGenerator.generateForUser(username);
        DrillSession session = sessionEngine.findOrCreateSession(username);
        return ResponseEntity.ok(SessionDto.from(session));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionDto> getSession(@PathVariable UUID id) {
        return sessionRepository.findById(id)
                .map(s -> ResponseEntity.ok(SessionDto.from(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns the next card the user should review in this session.
     * Returns 204 when the session is complete.
     */
    @GetMapping("/{id}/next")
    public ResponseEntity<CardDto> nextCard(@PathVariable UUID id) {
        DrillSession session = sessionRepository.findById(id).orElse(null);
        if (session == null || session.isCompleted()) return ResponseEntity.noContent().build();

        List<UUID> queue = sessionEngine.parseQueue(session.getCardQueue());
        int idx = session.getCardsCompleted();
        if (idx >= queue.size()) return ResponseEntity.noContent().build();

        UUID cardId = queue.get(idx);
        Card card = cardRepository.findByIdWithDetails(cardId).orElse(null);
        if (card == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(CardDto.from(card));
    }

    /** Record an attempt and advance the card's FSRS state. */
    @PostMapping("/{id}/attempt")
    public ResponseEntity<SessionDto> recordAttempt(@PathVariable UUID id,
                                                    @RequestBody AttemptRequest req) {
        sessionEngine.recordAttempt(id, req.cardId(), req.movePlayed(),
                req.correct(), req.rating(), req.responseMs());
        DrillSession updated = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        return ResponseEntity.ok(SessionDto.from(updated));
    }
}
