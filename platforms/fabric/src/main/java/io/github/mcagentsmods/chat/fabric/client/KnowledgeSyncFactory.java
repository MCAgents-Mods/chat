package io.github.mcagentsmods.chat.fabric.client;

import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.agent.JdkHttpTransport;
import io.github.mcagentsmods.chat.common.service.CentralService;
import io.github.mcagentsmods.chat.common.service.KnowledgeSync;

/**
 * Builds the catalog sync, and owns the transport it runs on.
 *
 * <p>One transport, created on first use and reused: it holds a connection pool, and
 * building one per button press would discard it every time. Created lazily so that a
 * player who never presses refresh and never reaches the service pays nothing for it.
 */
public final class KnowledgeSyncFactory {

    private static volatile JdkHttpTransport transport;

    private KnowledgeSyncFactory() {
        throw new AssertionError("No instances.");
    }

    public static KnowledgeSync create(LocalDatabase database) {
        return new KnowledgeSync(service(), database);
    }

    /**
     * The catalog, on the shared transport.
     *
     * <p>Exposed so the console reaches the service through the same connection pool the
     * startup fetch uses rather than opening a second one for the life of the game.
     */
    public static CentralService service() {
        return new CentralService(sharedTransport());
    }

    private static JdkHttpTransport sharedTransport() {
        JdkHttpTransport existing = transport;
        if (existing != null) {
            return existing;
        }
        synchronized (KnowledgeSyncFactory.class) {
            if (transport == null) {
                transport = new JdkHttpTransport();
            }
            return transport;
        }
    }

    /** Releases the transport. Called on game shutdown. */
    public static void close() {
        synchronized (KnowledgeSyncFactory.class) {
            if (transport != null) {
                transport.close();
                transport = null;
            }
        }
    }
}
