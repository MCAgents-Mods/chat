package io.github.mcagentsmods.chat.common.storage;

import io.github.mcagentsmods.chat.api.model.ChatMessage;
import io.github.mcagentsmods.chat.api.model.ChatSession;
import io.github.mcagentsmods.chat.api.model.Page;
import io.github.mcagentsmods.chat.api.model.PageRequest;
import io.github.mcagentsmods.chat.api.storage.ChatRepository;
import io.github.mcagentsmods.core.api.chat.ChatRole;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A chat store that says when it has changed.
 *
 * <p>The player may be typing into the game and into a browser tab at the same time. Both
 * go through this object, so whichever one writes, the other can tell — and it can tell by
 * comparing two numbers rather than by re-reading a conversation twenty times a second.
 *
 * <h2>Why a decorator rather than a counter in the console</h2>
 *
 * <p>Because the console is not the only writer. A counter it owned would move when the
 * browser sent a message and sit still when the game did, so the browser would never learn
 * about anything typed in game — the sync would work in exactly one direction and look
 * finished. Wrapping the repository is what makes both directions the same mechanism.
 *
 * <h2>The counter moves after the write, not before</h2>
 *
 * <p>Each method bumps once the underlying future has completed successfully. Bumping
 * first would tell a reader to reload before there was anything new to read, and bumping on
 * failure would do it when there never would be.
 */
public final class RevisionedChatRepository implements ChatRepository {

    private final ChatRepository delegate;
    private final AtomicLong revision;

    public RevisionedChatRepository(ChatRepository delegate, AtomicLong revision) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.revision = Objects.requireNonNull(revision, "revision");
    }

    // ------------------------------------------------------------- reads

    @Override
    public CompletableFuture<List<ChatSession>> findAllSessions() {
        return delegate.findAllSessions();
    }

    @Override
    public CompletableFuture<List<ChatSession>> searchSessions(String text) {
        return delegate.searchSessions(text);
    }

    @Override
    public CompletableFuture<Page<ChatSession>> findSessionPage(String search, PageRequest page) {
        return delegate.findSessionPage(search, page);
    }

    @Override
    public CompletableFuture<Optional<ChatSession>> findSession(String id) {
        return delegate.findSession(id);
    }

    @Override
    public CompletableFuture<List<ChatMessage>> findMessages(String sessionId) {
        return delegate.findMessages(sessionId);
    }

    // ------------------------------------------------------------ writes

    @Override
    public CompletableFuture<ChatSession> createSession(String title) {
        return bumpOnSuccess(delegate.createSession(title));
    }

    @Override
    public CompletableFuture<Boolean> renameSession(String id, String title) {
        return bumpIfChanged(delegate.renameSession(id, title));
    }

    @Override
    public CompletableFuture<Boolean> deleteSession(String id) {
        return bumpIfChanged(delegate.deleteSession(id));
    }

    @Override
    public CompletableFuture<ChatMessage> appendMessage(String sessionId, ChatRole role, String content) {
        return bumpOnSuccess(delegate.appendMessage(sessionId, role, content));
    }

    /** What a reader compares against what it last saw. */
    public long revision() {
        return revision.get();
    }

    private <T> CompletableFuture<T> bumpOnSuccess(CompletableFuture<T> write) {
        return write.thenApply(result -> {
            revision.incrementAndGet();
            return result;
        });
    }

    /**
     * Moves the counter only when something was actually written.
     *
     * <p>Renaming a conversation that does not exist returns {@code false} and changes
     * nothing, so telling every reader to reload for it would be noise — and on the client
     * thread, noise that costs a database read per tick.
     */
    private CompletableFuture<Boolean> bumpIfChanged(CompletableFuture<Boolean> write) {
        return write.thenApply(changed -> {
            if (Boolean.TRUE.equals(changed)) {
                revision.incrementAndGet();
            }
            return changed;
        });
    }
}
