package io.github.mcagentsmods.chat.api.storage;

/**
 * A counter that moves whenever stored data changes.
 *
 * <h2>What this is for</h2>
 *
 * <p>A reader that shows stored conversations can tell whether anything changed since it
 * last looked, without reading them again. Every write goes through a decorator that moves this counter, and a reader that
 * cares compares the number it last saw against the number now. Nothing is pushed and
 * nothing is subscribed to: a comparison of two longs is cheap enough to do on every client
 * tick, and it cannot leak a listener or fire on a thread the reader did not expect.
 *
 * <h2>What the number means</h2>
 *
 * <p>Only that something changed. It is not a version, not a count of writes worth
 * reporting, and not comparable across restarts — a reader that sees a different value
 * reloads, and that is the whole protocol.
 */
public interface ChangeFeed {

    /**
     * Moves whenever a conversation or a message is created, changed, or deleted.
     *
     * <p>Safe to call from any thread, including the client thread. It touches no storage.
     */
    long chatRevision();
}
