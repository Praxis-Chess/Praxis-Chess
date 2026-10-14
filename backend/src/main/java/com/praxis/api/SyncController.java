package com.praxis.api;

import com.praxis.dto.SyncRequestDto;
import com.praxis.dto.SyncStatusDto;
import com.praxis.service.chesscom.AsyncSyncService;
import com.praxis.service.chesscom.SyncService;
import com.praxis.service.settings.PlayerIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;
    private final AsyncSyncService asyncSyncService;
    private final PlayerIdentity player;

    public SyncController(SyncService syncService, AsyncSyncService asyncSyncService, PlayerIdentity player) {
        this.syncService = syncService;
        this.asyncSyncService = asyncSyncService;
        this.player = player;
    }

    /** 409 until a username exists: a sync for nobody would fetch nothing and say "done". */
    private ResponseEntity<Map<String, Object>> noPlayer(SyncRequestDto request) {
        boolean named = request != null && request.username() != null && !request.username().isBlank();
        if (named || player.isSet()) return null;
        return ResponseEntity.status(409).body(Map.of("message",
                "Add your Chess.com username in Settings first."));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> triggerSync(@RequestBody(required = false) SyncRequestDto request) {
        int months = request != null ? request.effectiveMonths() : 1;
        String username = request != null ? request.username() : null;
        ResponseEntity<Map<String, Object>> refused = noPlayer(request);
        if (refused != null) return refused;
        syncService.enqueueSyncFlag();
        asyncSyncService.syncAsync(username, months);
        return ResponseEntity.accepted().body(Map.of("message", "Sync queued"));
    }

    @GetMapping("/status")
    public ResponseEntity<SyncStatusDto> getStatus() {
        SyncService.SyncStatus status = syncService.getStatus();
        return ResponseEntity.ok(new SyncStatusDto(
                status.state(), status.gamesFetched(),
                status.gamesAnalyzed(), status.gamesPending(),
                status.lastSyncedAt()));
    }

    /**
     * Counts games on Chess.com (current month) that are not yet in our database.
     * Chess.com call is cached server-side for 10 minutes to stay within rate limits.
     */
    @GetMapping("/new-count")
    public ResponseEntity<Map<String, Integer>> countNewGames() {
        int count = syncService.countNewGamesAvailable();
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PostMapping("/force-resync")
    public ResponseEntity<Map<String, Object>> forceResync(@RequestBody(required = false) SyncRequestDto request) {
        int months = request != null ? request.effectiveMonths() : 3;
        String username = request != null ? request.username() : null;
        ResponseEntity<Map<String, Object>> refused = noPlayer(request);
        if (refused != null) return refused;
        syncService.enqueueSyncFlag();
        asyncSyncService.forceResyncAsync(username, months);
        return ResponseEntity.accepted().body(Map.of("message", "Re-sync queued"));
    }
}
