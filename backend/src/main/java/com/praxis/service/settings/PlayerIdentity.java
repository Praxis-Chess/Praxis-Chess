package com.praxis.service.settings;

import com.praxis.config.AppProperties;
import com.praxis.domain.AppSettings;
import com.praxis.repository.AppSettingsRepository;
import com.praxis.repository.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Whose games this install reads: the Chess.com username.
 *
 * Entered in Settings and stored in {@code app_settings}; when none is stored,
 * {@code praxis-chess.chess-com.username} from config is used, so an install
 * set up the old way keeps working with no migration. Everything that needs the
 * player's name asks here, never the config directly — otherwise a username
 * typed into Settings would be half applied.
 *
 * Read on nearly every request, so the value is cached and replaced on save.
 */
@Component
public class PlayerIdentity {

    private static final Logger log = LoggerFactory.getLogger(PlayerIdentity.class);

    /** Chess.com names: letters, digits, underscore, hyphen. Also keeps the URL path safe. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{2,30}");

    public enum Source { SETTINGS, CONFIG, NONE }

    public record Current(String username, Source source) {}

    private final AppSettingsRepository settings;
    private final GameRepository games;
    private final AppProperties props;

    private volatile Current cached;

    public PlayerIdentity(AppSettingsRepository settings, GameRepository games, AppProperties props) {
        this.settings = settings;
        this.games = games;
        this.props = props;
    }

    /** The username, or "" when none is set anywhere yet. Never null. */
    public String username() {
        return current().username();
    }

    public boolean isSet() {
        return !username().isEmpty();
    }

    public Current current() {
        Current c = cached;
        if (c != null) return c;
        try {
            String stored = settings.findById(AppSettings.SINGLETON_ID)
                    .map(AppSettings::getChessComUsername).orElse(null);
            c = stored != null && !stored.isBlank()
                    ? new Current(stored.trim(), Source.SETTINGS)
                    : fromConfig();
            cached = c;
            return c;
        } catch (Exception e) {
            // Database not reachable yet (startup order): answer from config but
            // don't cache it, so the stored value is picked up once it is.
            log.debug("[identity] settings not readable yet: {}", e.getMessage());
            return fromConfig();
        }
    }

    private Current fromConfig() {
        String u = configured();
        return new Current(u, u.isEmpty() ? Source.NONE : Source.CONFIG);
    }

    private String configured() {
        String u = props.chessCom() == null ? null : props.chessCom().username();
        if (u == null || u.isBlank() || u.startsWith("YOUR_CHESS_COM_USERNAME")) return "";
        return u.trim();
    }

    /** Null when acceptable, else the reason it is not. */
    public static String invalidReason(String raw) {
        if (raw == null || raw.isBlank()) return "Enter your Chess.com username.";
        if (!VALID.matcher(raw.trim()).matches()) {
            return "A Chess.com username has 2–30 letters, digits, underscores or hyphens.";
        }
        return null;
    }

    /**
     * Stores the username. Games are stored under the exact spelling they were
     * synced with, so a name that matches stored games without regard to case
     * takes their spelling — "Magnus" after syncing as "magnus" must not hide
     * the whole library.
     */
    @Transactional
    public Current save(String raw) {
        String reason = invalidReason(raw);
        if (reason != null) throw new IllegalArgumentException(reason);
        String name = raw.trim();
        List<String> spellings = games.findStoredSpellings(name);
        if (!spellings.isEmpty()) name = spellings.get(0);

        AppSettings row = settings.findById(AppSettings.SINGLETON_ID)
                .orElseGet(() -> AppSettings.builder().id(AppSettings.SINGLETON_ID).build());
        row.setChessComUsername(name);
        settings.save(row);
        cached = new Current(name, Source.SETTINGS);
        log.info("[identity] Chess.com username set to '{}' in Settings", name);
        return cached;
    }
}
