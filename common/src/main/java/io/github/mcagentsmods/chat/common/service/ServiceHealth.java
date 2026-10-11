package io.github.mcagentsmods.chat.common.service;

import java.time.Instant;
import java.util.Objects;

/**
 * Whether the central service answered, and what it said.
 *
 * <p>Deliberately not an exception. The service is hosted somewhere that sleeps when idle,
 * so "not reachable" is an ordinary, expected, temporary state — the first answer after a
 * quiet night is usually a refused connection, and treating that as an error would make the
 * normal case look like a fault.
 *
 * @param reachable whether it answered at all
 * @param version   the version it reported, or empty when it did not answer
 * @param detail    something short to show a player when it did not
 * @param checkedAt when this was established
 */
public record ServiceHealth(boolean reachable, String version, String detail, Instant checkedAt) {

    public ServiceHealth {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }

    public static ServiceHealth reachable(String version) {
        return new ServiceHealth(true, version, "", Instant.now());
    }

    public static ServiceHealth unreachable(String detail) {
        return new ServiceHealth(false, "", detail, Instant.now());
    }
}
