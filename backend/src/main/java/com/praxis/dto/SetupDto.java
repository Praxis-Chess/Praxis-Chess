package com.praxis.dto;

import java.util.List;

/**
 * The setup checklist: what this install needs, and what is missing.
 * Records, so Jackson's SNAKE_CASE applies to every component.
 */
public final class SetupDto {

    private SetupDto() {}

    public enum State { OK, WARN, FAIL, SKIP }

    /**
     * One dependency. {@code fix} is the exact thing to do when it is not OK —
     * a command or a place in the app — never a generic "check your setup".
     */
    public record Check(String key, String label, State state, String detail, String fix) {}

    /**
     * A model this install is configured to use.
     *
     * @param state     present, missing, waiting, downloading, creating, failed
     * @param completed bytes downloaded so far (from the models service), 0 if unknown
     * @param total     bytes in total, 0 if unknown
     */
    public record Model(String name, String role, String state, long completed, long total, String message) {}

    /**
     * @param ready  nothing FAILed: syncing and engine analysis work
     * @param docker running inside the Docker install (changes the fix commands)
     */
    public record Status(boolean ready, boolean docker, String stockfish, List<Check> checks, List<Model> models) {}

    /** The player whose games this install reads. */
    public record Player(String username, String source) {}

    public record PlayerUpdate(String username) {}

    /**
     * @param warning set when the name was saved but could not be confirmed,
     *                e.g. Chess.com was unreachable
     */
    public record PlayerSaved(Player player, String warning) {}
}
