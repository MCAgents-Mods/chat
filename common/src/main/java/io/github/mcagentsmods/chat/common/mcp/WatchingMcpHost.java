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
import io.github.mcagentsmods.chat.common.agent.HttpTransport;

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
 * <p>It watches {@code {minecraft_dir}/MCAgents/mcps/} so a repository cloned while the
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
 * {@link McpServerStatus.State#FAILED} row and the rest carry on. The offline-first mandate
 * applies to MCP exactly as it applies to the central service.
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
        List<McpServerSpec> specs = discovery.discover(problems);
        configured = specs;

        stopServersNoLongerWanted(specs);

        List<McpToolDescriptor> tools = new ArrayList<>();
        List<McpResourceDescriptor> resources = new ArrayList<>();
        List<McpPromptDescriptor> prompts = new ArrayList<>();
        List<McpServerStatus> statuses = new ArrayList<>();

        for (McpServerSpec spec : specs) {
            if (!spec.enabled()) {
                closeSession(spec.id());
                statuses.add(McpServerStatus.disabled(spec));
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
            McpSession started = spec.transport() == McpTransport.HTTP
                    ? HttpMcpSession.connect(spec, transport)
                    : StdioMcpSession.start(spec, paths.mcpDirectory(), requestTimeout);
            sessions.put(spec.id(), started);
            wiring.put(spec.id(), spec);
            return started;
        }
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
        try {
            watcher = FileSystems.getDefault().newWatchService();
            registerWatch(paths.mcpDirectory());
            try (var owners = Files.list(paths.mcpDirectory())) {
                owners.filter(Files::isDirectory).forEach(this::registerWatch);
            }
        } catch (IOException cannotWatch) {
            watcher = null;
            return; // Discovery still works; only automatic pickup is lost.
        }

        watchThread = new Thread(this::watchLoop, "MCAgents-mcp-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void registerWatch(Path directory) {
        try {
            directory.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException ignored) {
            // One unwatchable directory does not stop the others.
        }
    }

    private void watchLoop() {
        while (!closed) {
            WatchKey key;
            try {
                key = watcher.take();
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
                    WatchKey next = watcher.poll(SETTLE.toMillis(), TimeUnit.MILLISECONDS);
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
                try (var owners = Files.list(paths.mcpDirectory())) {
                    owners.filter(Files::isDirectory).forEach(this::registerWatch);
                }
                refresh();
            } catch (IOException | RuntimeException reloadFailed) {
                // Reported through the statuses on the next successful pass.
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException ignored) {
                // Nothing further to do; the thread is a daemon and exits on its own.
            }
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
        synchronized (sessions) {
            sessions.values().forEach(McpSession::close);
            sessions.clear();
            wiring.clear();
        }
        registry = McpContextRegistry.empty();
    }
}
