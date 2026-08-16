package com.praxis.api;

import com.praxis.service.voice.TtsClient;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final TtsClient tts;

    public VoiceController(TtsClient tts) {
        this.tts = tts;
    }

    /**
     * The frontend probes this once at startup. A false here makes it bind
     * MockVoice, so the listen control never renders rather than rendering and
     * failing.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok(Map.of("available", tts.isHealthy()));
    }

    public record SpeakRequest(String text, String voice, Double speed) {}

    @PostMapping("/speak")
    public ResponseEntity<byte[]> speak(@RequestBody SpeakRequest req) {
        byte[] wav = tts.synthesize(req.text(), req.voice(), req.speed());
        // 503 rather than 500: the caller degrades to silence, it is not an error.
        if (wav == null) return ResponseEntity.status(503).build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                // Same sentence re-read (replay, revisit) costs nothing the second time.
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .body(wav);
    }
}
