package io.github.mcagentsmods.chat.common.agent.openrouter;

import io.github.mcagentsmods.chat.api.agent.ProviderRoutes;
import io.github.mcagentsmods.chat.common.agent.StubKeySource;
import io.github.mcagentsmods.chat.common.agent.StubTransport;

import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Builds a client wired to stubs, with a backoff short enough that retry tests finish in
 * milliseconds instead of seconds.
 *
 * <p>Shortening the backoff does not weaken the tests: what they assert is how many
 * attempts happen and when the client gives up, and neither depends on how long it waited.
 */
final class TestAccess {

    private static final Duration TEST_BACKOFF = Duration.ofMillis(1);

    private TestAccess() {
    }

    static StubTransport transport() {
        return new StubTransport();
    }

    static StubKeySource keys(String... values) {
        return new StubKeySource(values);
    }

    static OpenRouterClient client(StubTransport transport, StubKeySource keys) {
        return client(transport, keys, ProviderRoutes.throughOpenRouter());
    }

    /** A client whose routing is under the test's control, for the direct-route cases. */
    static OpenRouterClient client(StubTransport transport, StubKeySource keys,
                                   ProviderRoutes routes) {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "test-openrouter-retry");
            thread.setDaemon(true);
            return thread;
        });
        return new OpenRouterClient(transport, keys, routes, scheduler, true, TEST_BACKOFF);
    }
}
