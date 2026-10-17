package io.github.mcagentsmods.chat.shared.client;

import io.github.mcagentsmods.core.api.hologram.Hologram;
import io.github.mcagentsmods.core.client.hologram.Holograms;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * What chat's page holograms share: the requests to storage they wait on, and dropping an
 * answer that arrives after the page has closed.
 *
 * <p>A page is built afresh each time it opens and keeps its own draft; nothing it shows is
 * read from storage inside a supplier, which core reads every frame. Every request's answer
 * is applied back on the game's thread, and only while this page's hologram still stands.
 */
abstract class HologramPage {

    private static final Logger LOGGER = LoggerFactory.getLogger(HologramPage.class);

    private int pending;
    private boolean closed;

    /** @return the hologram this page shows */
    abstract Hologram build();

    /** Opens the page in front of the player; a page of the same kind already open closes. */
    final void open() {
        Holograms.open(build().withOnClose(() -> closed = true));
    }

    /** @return whether a request this page made is still on its way */
    final boolean waiting() {
        return pending > 0;
    }

    /**
     * Waits for {@code request}, then hands its answer to {@code onSuccess} on the game's
     * thread, or logs the failure and hands it to {@code onFailure}. Neither runs once the page
     * has closed.
     *
     * @param failureMessage what the log says when the request fails
     */
    final <T> void request(CompletableFuture<T> request, Consumer<T> onSuccess, String failureMessage,
                           Consumer<Throwable> onFailure) {
        pending++;
        request.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            pending--;
            if (failure != null) {
                LOGGER.error("{}", failureMessage, failure);
            }
            if (closed) {
                return;
            }
            if (failure != null) {
                onFailure.accept(failure);
            } else {
                onSuccess.accept(result);
            }
        }));
    }

    /** The same, with nothing to show for a failure but the log line. */
    final <T> void request(CompletableFuture<T> request, Consumer<T> onSuccess, String failureMessage) {
        request(request, onSuccess, failureMessage, failure -> { });
    }

    /** The reason a request failed, for a player to read. */
    static String describe(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
