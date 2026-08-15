package io.github.mcagents.clientfabric.fabric.client;

import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.common.agent.JdkHttpTransport;
import io.github.mcagents.clientfabric.common.service.CentralService;
import io.github.mcagents.clientfabric.common.service.KnowledgeSync;

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
        return new KnowledgeSync(new CentralService(sharedTransport()), database);
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
