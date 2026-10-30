package io.github.mcagentsmods.chat.common.mcp;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Watches the mcps directory and runs a reload once it goes quiet after a change.
 *
 * <p>{@link WatchingMcpHost} owns what a reload does; this class owns only noticing that one
 * is due: the watch service, the daemon thread that drains it, and the settle window.
 *
 * <p>Only the root and the owner directories are registered, not every repository.
 * {@link WatchService} is not recursive, and registering every checkout would mean a key per
 * node_modules tree — thousands of watches to notice something the settle window already
 * handles.
 */
final class McpConfigWatcher implements AutoCloseable {

    /**
     * How long to wait after a filesystem event before reloading.
     *
     * <p>A clone writes hundreds of files and each one is an event. Reloading on the first
     * would connect to a half-written repository, so the window is reset by every further
     * event and the reload happens once the directory goes quiet.
     */
    private static final Duration SETTLE = Duration.ofSeconds(2);

    private final Path directory;
    private final Runnable onSettled;

    private volatile boolean closed;
    private final Object lock = new Object();
    private WatchService service;
    private Thread thread;

    /**
     * @param directory the mcps directory, holding one directory per owner
     * @param onSettled the reload, run on the watch thread once a burst of changes settles;
     *                  a {@link RuntimeException} it throws is swallowed and watching goes on
     */
    McpConfigWatcher(Path directory, Runnable onSettled) {
        this.directory = directory;
        this.onSettled = onSettled;
    }

    /**
     * Starts watching. Does nothing once closed; a directory that cannot be watched costs
     * only automatic pickup, since discovery still works.
     */
    void start() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            WatchService started;
            try {
                started = FileSystems.getDefault().newWatchService();
            } catch (IOException cannotWatch) {
                return; // Discovery still works; only automatic pickup is lost.
            }
            try {
                registerWatch(started, directory);
                registerOwners(started);
            } catch (IOException cannotWatch) {
                // Nothing else holds the service yet, and an open one keeps its native handles.
                closeQuietly(started);
                return;
            }
            service = started;
            thread = new Thread(() -> watchLoop(started), "MCAgents-mcp-watch");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Stops watching. The flag is set first, and the service is closed under the lock
     * {@link #start} holds, so a start still running either sees the flag or has finished
     * before this closes what it made.
     */
    @Override
    public void close() {
        closed = true;
        synchronized (lock) {
            if (service != null) {
                closeQuietly(service);
                service = null;
            }
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    private void registerOwners(WatchService watched) throws IOException {
        try (var owners = Files.list(directory)) {
            owners.filter(Files::isDirectory).forEach(owner -> registerWatch(watched, owner));
        }
    }

    private static void registerWatch(WatchService watched, Path path) {
        try {
            path.register(watched,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException | ClosedWatchServiceException ignored) {
            // One unwatchable directory does not stop the others.
        }
    }

    private static void closeQuietly(WatchService watched) {
        try {
            watched.close();
        } catch (IOException ignored) {
            // Nothing further to do; the thread is a daemon and exits on its own.
        }
    }

    /** Takes the service as a parameter so a close that clears the field cannot null it here. */
    private void watchLoop(WatchService watched) {
        while (!closed) {
            WatchKey key;
            try {
                key = watched.take();
            } catch (InterruptedException | ClosedWatchServiceException stopping) {
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
                    WatchKey next = watched.poll(SETTLE.toMillis(), TimeUnit.MILLISECONDS);
                    if (next == null) {
                        settled = true;
                    } else {
                        key = next;
                    }
                } catch (InterruptedException | ClosedWatchServiceException stopping) {
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
                registerOwners(watched);
                onSettled.run();
            } catch (IOException | RuntimeException reloadFailed) {
                // Reported through the statuses on the next successful pass.
            }
        }
    }
}
