package com.praxis.api;

import com.praxis.prax.chat.PraxReasoningService;
import com.praxis.prax.chat.PraxRunRegistry;
import com.praxis.prax.chat.PraxRunner;
import com.praxis.prax.conversation.ConversationService;
import com.praxis.prax.conversation.domain.Conversation;
import com.praxis.prax.conversation.domain.ConversationMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/prax")
public class PraxController {

    private final PraxReasoningService reasoning;
    private final ConversationService conversations;
    private final PraxRunRegistry runs;
    private final PraxRunner runner;

    public PraxController(PraxReasoningService reasoning, ConversationService conversations,
                          PraxRunRegistry runs, PraxRunner runner) {
        this.reasoning = reasoning;
        this.conversations = conversations;
        this.runs = runs;
        this.runner = runner;
    }

    /** Probed once at startup — a false hides the chat entry point entirely. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("available", reasoning.isAvailable()));
    }

    /**
     * @param conversationId thread to continue. Omit to start a new one; the
     *                       response carries the id either way.
     */
    public record AskRequest(String question, String conversationId) {}

    @PostMapping("/ask")
    public ResponseEntity<PraxReasoningService.Answer> ask(@RequestBody AskRequest req) {
        if (req.question() == null || req.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        UUID thread = parseId(req.conversationId());
        return ResponseEntity.ok(reasoning.ask(req.question().strip(), thread));
    }

    // ── progressive answering ────────────────────────────────────────────────

    public record StartedRun(UUID runId) {}

    /**
     * Starts a question and returns immediately.
     *
     * A question takes 15-40s. Holding the request open for that means the
     * workspace shows nothing at all until the very end; polling the run instead
     * lets tool calls and diagrams appear as they happen — and a board is on
     * screen well before the prose is written.
     */
    @PostMapping("/ask/stream")
    public ResponseEntity<StartedRun> askStreaming(@RequestBody AskRequest req) {
        if (req.question() == null || req.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        PraxRunRegistry.Run run = runs.start();
        runner.run(run, req.question().strip(), parseId(req.conversationId()));
        return ResponseEntity.accepted().body(new StartedRun(run.id));
    }

    @GetMapping("/run/{id}")
    public ResponseEntity<PraxRunRegistry.RunView> run(@PathVariable UUID id) {
        PraxRunRegistry.Run run = runs.find(id);
        // 404 rather than an empty run: a client polling an evicted id should
        // stop and say so, not wait for an answer that no longer exists.
        return run == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(runs.view(run));
    }

    // ── conversations ────────────────────────────────────────────────────────
    //
    // Records, never Maps. Jackson's SNAKE_CASE strategy does not apply to Map
    // keys, and a controller that returned one shipped `sessionId` to a client
    // reading `session_id` — every request then went to /session/undefined/.

    public record ConversationSummary(UUID id, String title, OffsetDateTime startedAt,
                                      OffsetDateTime lastMessageAt) {}

    public record MessageView(UUID id, String role, String content,
                              OffsetDateTime createdAt, Map<String, Object> payload) {}

    public record ConversationDetail(UUID id, String title, List<MessageView> messages) {}

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationSummary>> list() {
        return ResponseEntity.ok(conversations.list().stream().map(this::summary).toList());
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<ConversationDetail> get(@PathVariable UUID id) {
        return conversations.find(id)
                .map(c -> ResponseEntity.ok(new ConversationDetail(
                        c.getId(), c.getTitle(),
                        conversations.messagesOf(id).stream().map(this::view).toList())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        conversations.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Kept for the floating card, which has no thread of its own.
     *
     * Starting a new conversation is the real reset now — this exists so the
     * card's "new question" behaviour does not break.
     */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        reasoning.reset();
        return ResponseEntity.noContent().build();
    }

    // --- helpers ---

    private ConversationSummary summary(Conversation c) {
        return new ConversationSummary(c.getId(), c.getTitle(), c.getStartedAt(), c.getLastMessageAt());
    }

    private MessageView view(ConversationMessage m) {
        return new MessageView(m.getId(), m.getRole().name().toLowerCase(),
                m.getContent(), m.getCreatedAt(), conversations.payloadOf(m));
    }

    /** A malformed id is a new conversation, not a 400 — the client may have stale state. */
    private static UUID parseId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
