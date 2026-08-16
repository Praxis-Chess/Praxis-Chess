package com.praxis.api;

import com.praxis.prax.chat.PraxReasoningService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/prax")
public class PraxController {

    private final PraxReasoningService reasoning;

    public PraxController(PraxReasoningService reasoning) {
        this.reasoning = reasoning;
    }

    /** Probed once at startup — a false hides the chat entry point entirely. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("available", reasoning.isAvailable()));
    }

    public record AskRequest(String question) {}

    @PostMapping("/ask")
    public ResponseEntity<PraxReasoningService.Answer> ask(@RequestBody AskRequest req) {
        if (req.question() == null || req.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(reasoning.ask(req.question().strip()));
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        reasoning.reset();
        return ResponseEntity.noContent().build();
    }
}
