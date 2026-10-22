package io.github.mcagentsmods.chat.common.mcp;

import io.github.mcagentsmods.chat.api.mcp.McpContextRegistry;
import io.github.mcagentsmods.chat.api.mcp.McpHost;
import io.github.mcagentsmods.chat.api.mcp.McpPromptDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpResourceDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpServerSpec;
import io.github.mcagentsmods.chat.api.mcp.McpServerStatus;
import io.github.mcagentsmods.chat.api.mcp.McpSession;
import io.github.mcagentsmods.chat.api.mcp.McpToolDescriptor;
import io.github.mcagentsmods.chat.api.mcp.McpTransport;
import io.github.mcagentsmods.chat.common.ClientPaths;
import io.github.mcagentsmods.core.api.http.HttpTransport;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The MCP host: discovers servers, keeps sessions to them, and publishes one merged
 * registry.
 *
 * <p>It watches {@code {minecraft_dir}/MCAgents/chat/mcps/} so a repository cloned while the
 * game is running is picked up without a restart. That is the whole reason the watcher
 * exists — asking a player to restart Minecraft to use a server they just added would make
 * the clone tool nearly pointless.
 *
 * <p><b>A reload is a diff, not a restart.</b> Servers whose wiring is unchanged keep their
 * live sessions; only what actually changed is stopped and started. Restarting everything
 * on every filesystem event would drop working connections every time a server wrote to its
 * own directory, which some of them do constantly.
 *
 * <p><b>Nothing here fails the mod.</b> A server that will not start becomes a
 * {@link McpServerStatus.State#FAILED} row and the rest carry on.
 */
public final class WatchingMcpHost implements McpHost {

    /**
     * How long to wait after a filesystem event before reloading.
     *
     * <p>A clone writes hundreds of files and each one is an event. Reloading on the first
     * would connect to a half-written repository, so the window is reset by every further
     * event and the reload happens once the directory goes quiet.
     */
    private static final Duration SETTLE = Duration.ofSeconds(2);

    private final ClientPaths paths;
    private final McpDiscovery discovery;
    private final HttpTransport transport;
    private final Executor executor;
    private final Duration requestTimeout;

    private final Map<String, McpSession> sessions = new LinkedHashMap<>();
    private final Map<String, McpServerSpec> wiring = new LinkedHashMap<>();
    private final List<Consumer<McpContextRegistry>> listeners = new CopyOnWriteArrayList<>();

    private volatile McpContextRegistry registry = McpContextRegistry.empty();
    private volatile List<McpServerSpec> configured = List.of();
    private volatile boolean closed;
    private final Object watchLock = new Object();
    private WatchService watcher;
    private Thread watchThread;

    /**
     * @param paths          where the mcps directory is
     * @param transport      used by HTTP servers; shared with the model client and not
     *                       closed by this host
     * @param executor       runs discovery, connection and reloads; never the client thread
     * @param requestTimeout how long one MCP request may take
     */
    public WatchingMcpHost(
            ClientPaths paths, HttpTransport transport, Executor executor, Duration requestTimeout) {
        this.paths = paths;
        this.transport = transport;
        this.executor = executor;
        this.requestTimeout = requestTimeout;
        this.discovery = new McpDiscovery(paths.mcpDirectory());
    }

    /** A host with a thirty-second request limit, which is long enough for a slow tool. */
    public WatchingMcpHost(ClientPaths paths, HttpTransport transport, Executor executor) {
        this(paths, transport, executor, Duration.ofSeconds(30));
    }

    @Override
    public CompletableFuture<McpContextRegistry> start() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                paths.createMcpDirectory();
            } catch (IOException cannotCreate) {
                // Not fatal. Discovery will find nothing, which is a valid state.
                return publish(McpContextRegistry.merge(
                        List.of(), List.of(), List.of(), List.of(), null));
            }
            McpContextRegistry first = refresh();
            beginWatching();
            return first;
        }, executor);
    }

    @Override
    public McpContextRegistry registry() {
        return registry;
    }

    @Override
    public CompletableFuture<McpContextRegistry> reload() {
        return CompletableFuture.supplyAsync(this::refresh, executor);
    }

    @Override
    public Optional<McpSession> session(String serverId) {
        synchronized (sessions) {
            McpSession session = sessions.get(serverId);
            return session != null && session.isAlive() ? Optional.of(session) : Optional.empty();
        }
    }

    @Override
    public void onRegistryChanged(Consumer<McpContextRegistry> listener) {
        listeners.add(listener);
    }

    @Override
    public List<McpServerSpec> configuredServers() {
        return configured;
    }

    /**
     * Re-reads everything and reconciles the live sessions against it.
     *
     * <p>Synchronized because two reloads at once — a manual one and a watched one — would
     * otherwise race to start the same server twice.
     */
    private synchronized McpContextRegistry refresh() {
        if (closed) {
            return registry;
        }
        List<String> problems = new ArrayList<>();
        Map<String, String> awaitingApproval = new LinkedHashMap<>();
        List<McpServerSpec> specs = discovery.discover(problems, awaitingApproval);
        configured = specs;

        stopServersNoLongerWanted(specs);

        List<McpToolDescriptor> tools = new ArrayList<>();
        List<McpResourceDescriptor> resources = new ArrayList<>();
        List<McpPromptDescriptor> prompts = new ArrayList<>();
        List<McpServerStatus> statuses = new ArrayList<>();

        for (McpServerSpec spec : specs) {
            if (!spec.enabled()) {
                closeSession(spec.id());
                String waiting = awaitingApproval.get(spec.id());
                statuses.add(waiting == null
                        ? McpServerStatus.disabled(spec)
                        : McpServerStatus.awaitingApproval(spec, waiting));
                continue;
            }
            try {
                McpSession session = sessionFor(spec);
                List<McpToolDescriptor> serverTools = session.listTools()
                        .get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                tools.addAll(serverTools);
                resources.addAll(quietly(session::listResources));
                prompts.addAll(quietly(session::listPrompts));
                statuses.add(McpServerStatus.connected(spec, serverTools.size()));
            } catch (Exception failed) {
                closeSession(spec.id());
                statuses.add(McpServerStatus.failed(spec, reasonOf(failed)));
            }
        }

        List<String> dropped = new ArrayList<>();
        McpContextRegistry merged =
                McpContextRegistry.merge(tools, resources, prompts, statuses, dropped);
        problems.addAll(dropped);
        return publish(merged);
    }

    /**
     * Reuses a live session when the wiring has not changed, and starts one otherwise.
     *
     * <p>Reuse is the point: a stdio server is a process, and restarting it on every reload
     * throws away whatever state it had built up, for no gain.
     */
    private McpSession sessionFor(McpServerSpec spec) {
        synchronized (sessions) {
            McpSession existing = sessions.get(spec.id());
            if (existing != null && existing.isAlive() && spec.sameWiring(wiring.get(spec.id()))) {
                return existing;
            }
            closeSession(spec.id());
        }
        if (closed) {
            throw new IllegalStateException("The MCP host is closed");
        }
        // Started outside the lock: a handshake can take the whole request timeout, and
        // holding the map that long would hold close(), and the game's shutdown, with it.
        // refresh() is synchronized, so no second start of the same server can interleave.
        McpSession started = spec.transport() == McpTransport.HTTP
                ? HttpMcpSession.connect(spec, transport, requestTimeout)
                : StdioMcpSession.start(spec, paths.mcpDirectory(), requestTimeout);
        synchronized (sessions) {
            // close() sets the flag before it empties the map, so a server started while it
            // ran is either seen here and stopped, or is in the map when close() empties it.
            if (closed) {
                started.close();
                throw new IllegalStateException("The MCP host is closed");
            }
            sessions.put(spec.id(), started);
            wiring.put(spec.id(), spec);
        }
        return started;
    }

    private void stopServersNoLongerWanted(List<McpServerSpec> specs) {
        List<String> wanted = specs.stream().map(McpServerSpec::id).toList();
        synchronized (sessions) {
            List.copyOf(sessions.keySet()).stream()
                    .filter(id -> !wanted.contains(id))
                    .forEach(this::closeSession);
        }
    }

    private void closeSession(String serverId) {
        synchronized (sessions) {
            McpSession session = sessions.remove(serverId);
            wiring.remove(serverId);
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Lists what a server may not offer at all.
     *
     * <p>Resources and prompts are optional in MCP, and a server without them answers
     * {@code tools/list} happily and errors on {@code resources/list}. Treating that as a
     * connection failure would exclude a large share of real servers, so it is absence.
     */
    private <T> List<T> quietly(java.util.function.Supplier<CompletableFuture<List<T>>> call) {
        try {
            return call.get().get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception unsupported) {
            return List.of();
        }
    }

    /**
     * The sentence to show for a failure.
     *
     * <p>Both wrappers are unwrapped — a future waited on with {@code get} fails with an
     * {@link java.util.concurrent.ExecutionException}, one waited on with {@code join}
     * with a {@link java.util.concurrent.CompletionException} — because a status row
     * reading "ExecutionException" tells a player nothing at all.
     */
    private static String reasonOf(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.ExecutionException
                || cause instanceof java.util.concurrent.CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private McpContextRegistry publish(McpContextRegistry next) {
        registry = next;
        for (Consumer<McpContextRegistry> listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException listenerFailed) {
                // A listener's problem is not the host's. Losing the notification is bad;
                // losing every other listener because one threw would be worse.
            }
        }
        return next;
    }

    /**
     * Watches the mcps directory and reloads once it settles.
     *
     * <p>Only the root and the owner directories are registered, not every repository.
     * {@link WatchService} is not recursive, and registering every checkout would mean a
     * key per node_modules tree — thousands of watches to notice something the settle
     * window already handles.
     */
    private void beginWatching() {
        synchronized (watchLock) {
            if (closed) {
                return;
            }
            WatchService service;
            try {
                service = FileSystems.getDefault().newWatchService();
            } catch (IOException cannotWatch) {
                return; // Discovery still works; only automatic pickup is lost.
            }
            try {
                registerWatch(service, paths.mcpDirectory());
                registerOwners(service);
            } catch (IOException cannotWatch) {
                // Nothing else holds the service yet, and an open one keeps its native handles.
                closeQuietly(service);
                return;
            }
            watcher = service;
            watchThread = new Thread(() -> watchLoop(service), "MCAgents-mcp-watch");
            watchThread.setDaemon(true);
            watchThread.start();
        }
    }

    private void registerOwners(WatchService service) throws IOException {
        try (var owners = Files.list(paths.mcpDirectory())) {
            owners.filter(Files::isDirectory).forEach(owner -> registerWatch(service, owner));
        }
    }

    private static void registerWatch(WatchService service, Path directory) {
        try {
            directory.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException | java.nio.file.ClosedWatchServiceException ignored) {
            // One unwatchable directory does not stop the others.
        }
    }

    private static void closeQuietly(WatchService service) {
        try {
            service.close();
        } catch (IOException ignored) {
            // Nothing further to do; the thread is a daemon and exits on its own.
        }
    }

    /** Takes the service as a parameter so a close that clears the field cannot null it here. */
    private void watchLoop(WatchService service) {
        while (!closed) {
            WatchKey key;
            try {
                key = service.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException stopping) {
                Thread.currentThread().interrupt();
                return;
            }

            // Drain the burst: a clone produces events continuously, and reloading in the
            // middle of one connects to a repository that is still being written.
            boolean settled = false;
            while (!settled && !closed) {
                key.pollEvents();
                key.reset();
                try {
                    WatchKey next = service.poll(SETTLE.toMillis(), TimeUnit.MILLISECONDS);
                    if (next == null) {
                        settled = true;
                    } else {
                        key = next;
                    }
                } catch (InterruptedException | java.nio.file.ClosedWatchServiceException stopping) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (closed) {
                return;
            }
            try {
                // A new owner directory needs its own key; the root's events do not
                // describe what happens two levels down.
                registerOwners(service);
                refresh();
            } catch (IOException | RuntimeException reloadFailed) {
                // Reported through the statuses on the next successful pass.
            }
        }
    }

    /**
     * Stops watching and stops every server.
     *
     * <p>The flag is set before anything is taken apart. The watcher is closed under the lock
     * {@link #beginWatching} holds, so a start still running either sees the flag or has
     * finished before this closes what it made; and {@link #sessionFor} checks the flag under
     * the sessions lock, so no server is started after the map below is emptied.
     *
     * <p>It does not wait for a reload in progress. That would hold the game's shutdown for as
     * long as a slow server's handshake, and the flag already stops the reload starting more.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        synchronized (watchLock) {
            if (watcher != null) {
                closeQuietly(watcher);
                watcher = null;
            }
            if (watchThread != null) {
                watchThread.interrupt();
            }
        }
        synchronized (sessions) {
            sessions.values().forEach(McpSession::close);
            sessions.clear();
            wiring.clear();
        }
        registry = McpContextRegistry.empty();
    }
}
