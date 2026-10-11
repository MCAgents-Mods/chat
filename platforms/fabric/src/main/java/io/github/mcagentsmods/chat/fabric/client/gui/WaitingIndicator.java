package io.github.mcagentsmods.chat.fabric.client.gui;

import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The animated "Waiting." → "Waiting.." → "Waiting..." state shown while a request is in
 * flight.
 *
 * <p>It exists because every call the mod makes is asynchronous, and a player staring at
 * an unchanged screen cannot tell a slow answer from a broken one. The dots move on the
 * client tick, so the animation keeps running while the future is pending and stops the
 * moment it settles — whether it succeeded or failed.
 *
 * <p>Only the counter lives here. Nothing in this class blocks, and nothing waits.
 */
final class WaitingIndicator {

    /** Client ticks each dot is held for — 20 ticks is one second, so this is a third. */
    private static final int TICKS_PER_DOT = 7;

    private static final int MAX_DOTS = 3;

    /** How many requests are in flight; the animation runs while this is above zero. */
    private final AtomicInteger pending = new AtomicInteger();

    private int ticks;

    /**
     * Attaches to a request. The animation runs until the future settles, and the count is
     * decremented on failure exactly as on success — a failed request that left the dots
     * spinning forever would be worse than no indicator at all.
     *
     * @return the same future, so this can be dropped into a call chain
     */
    <T> CompletableFuture<T> track(CompletableFuture<T> request) {
        pending.incrementAndGet();
        return request.whenComplete((result, failure) -> pending.decrementAndGet());
    }

    /** Whether anything is in flight. */
    boolean isWaiting() {
        return pending.get() > 0;
    }

    /** Advances the animation. Called once per client tick from the screen. */
    void tick() {
        if (isWaiting()) {
            ticks++;
        } else {
            ticks = 0;
        }
    }

    /**
     * The label to draw, with between one and three dots.
     *
     * @param message the text before the dots, already translated
     */
    Component label(Component message) {
        int dots = (ticks / TICKS_PER_DOT) % MAX_DOTS + 1;
        return Component.literal(message.getString() + ".".repeat(dots));
    }
}
