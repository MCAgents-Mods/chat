package io.github.mcagents.clientfabric.common.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A transport that answers from a script and records what it was asked.
 *
 * <p>This is what makes retry, key rotation, and JSON handling testable at all: none of
 * them needs a network, and none of them needs an API key.
 */
public final class StubTransport implements HttpTransport {

    private final Deque<Object> scripted = new ArrayDeque<>();
    private final List<HttpCall> calls = new ArrayList<>();
    private boolean closed;

    /** Queues a reply. */
    public StubTransport reply(int status, String body) {
        scripted.add(new HttpReply(status, body));
        return this;
    }

    /** Queues a transport failure — a dropped connection, a DNS failure, a timeout. */
    public StubTransport fail(Throwable cause) {
        scripted.add(cause);
        return this;
    }

    @Override
    public CompletableFuture<HttpReply> send(HttpCall call) {
        calls.add(call);
        if (scripted.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new AssertionError("The client sent more requests than the test scripted"));
        }
        Object next = scripted.poll();
        if (next instanceof Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.completedFuture((HttpReply) next);
    }

    public List<HttpCall> calls() {
        return List.copyOf(calls);
    }

    public HttpCall lastCall() {
        return calls.getLast();
    }

    public int callCount() {
        return calls.size();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
