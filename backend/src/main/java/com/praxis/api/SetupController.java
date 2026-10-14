package com.praxis.api;

import com.praxis.dto.SetupDto.Player;
import com.praxis.dto.SetupDto.PlayerSaved;
import com.praxis.dto.SetupDto.PlayerUpdate;
import com.praxis.dto.SetupDto.Status;
import com.praxis.service.chesscom.ChessComApiClient;
import com.praxis.service.settings.PlayerIdentity;
import com.praxis.service.setup.SetupStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * First-run setup: the checklist of what this install needs, and the player
 * whose games it reads.
 */
@RestController
@RequestMapping("/api")
public class SetupController {

    private final SetupStatusService setup;
    private final PlayerIdentity player;
    private final ChessComApiClient chessCom;

    public SetupController(SetupStatusService setup, PlayerIdentity player, ChessComApiClient chessCom) {
        this.setup = setup;
        this.player = player;
        this.chessCom = chessCom;
    }

    @GetMapping("/setup/status")
    public Status status() {
        return setup.status();
    }

    @GetMapping("/settings/player")
    public Player get() {
        PlayerIdentity.Current c = player.current();
        return new Player(c.username(), c.source().name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 200 with the saved player, or 400 with {@code {"errors":{"username": …}}}
     * — the same shape as the other Settings forms. Chess.com is asked whether
     * the account exists; when it cannot be reached the name is saved anyway
     * with a warning, since an offline moment must not block a correct name.
     */
    @PutMapping("/settings/player")
    public ResponseEntity<?> update(@RequestBody PlayerUpdate body) {
        String raw = body == null ? null : body.username();
        String reason = PlayerIdentity.invalidReason(raw);
        if (reason != null) return invalid(reason);

        ChessComApiClient.Lookup found = chessCom.lookupPlayer(raw.trim());
        if (found == ChessComApiClient.Lookup.NOT_FOUND) {
            return invalid("Chess.com has no player called \"" + raw.trim() + "\".");
        }
        PlayerIdentity.Current c = player.save(raw);
        String warning = found == ChessComApiClient.Lookup.UNREACHABLE
                ? "Saved, but Chess.com could not be reached to confirm the name." : null;
        return ResponseEntity.ok(new PlayerSaved(
                new Player(c.username(), c.source().name().toLowerCase(java.util.Locale.ROOT)), warning));
    }

    private static ResponseEntity<?> invalid(String reason) {
        return ResponseEntity.badRequest().body(Map.of("errors", Map.of("username", reason)));
    }
}
