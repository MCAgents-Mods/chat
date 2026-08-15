package io.github.mcagents.clientfabric.common.service;

import io.github.mcagents.clientfabric.api.MCAgents;
import io.github.mcagents.clientfabric.api.model.ContentCategory;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Brings the local library into step with what the service publishes.
 *
 * <h2>Only ever when asked</h2>
 *
 * <p>Nothing here runs on a timer or in the background. The player presses refresh or
 * update, or the mod fetches the one base pack at startup, and that is the complete list of
 * reasons this class does anything. A mod that quietly re-downloaded a library would be
 * spending someone else's bandwidth to answer a question nobody asked.
 *
 * <h2>Version is the whole protocol</h2>
 *
 * <p>A pack is identified by id and version, and the service publishes a new version rather
 * than editing one in place. So deciding what to download is comparing two maps of strings,
 * and applying an update is an overwrite. There is no diffing, no merge, and no partial
 * pack.
 *
 * <h2>A failure here costs a download and nothing else</h2>
 *
 * <p>Every method reports what it managed and what it did not. Nothing it does can leave the
 * local store worse than it found it: packs are written one at a time, each one complete,
 * and a pack that fails to download simply is not written.
 */
public final class KnowledgeSync {

    private static final Logger LOGGER = System.getLogger(KnowledgeSync.class.getName());

    private final CentralService service;
    private final LocalDatabase database;

    public KnowledgeSync(CentralService service, LocalDatabase database) {
        this.service = Objects.requireNonNull(service, "service");
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Downloads everything published that is missing or out of date locally.
     *
     * <p>This is the refresh button. It reads what the service publishes, compares it
     * against what is stored, and fetches only the difference — so pressing it twice in a
     * row does nothing the second time, which is exactly what an impatient player will do.
     */
    public CompletableFuture<SyncResult> refresh() {
        return service.categories()
                .thenCompose(this::storeCategories)
                .thenCompose(ignored -> service.publishedVersions())
                .thenCompose(this::downloadOutdated);
    }

    /**
     * Fetches the pack describing the mod, if it is missing or behind.
     *
     * <p>Called once at startup. It is the one thing the mod fetches without being asked,
     * and it is worth the exception: without it the agent cannot answer "what are you" —
     * the most obvious question a new player has — and the pack is small.
     *
     * <p>Best-effort by construction. The returned future never completes exceptionally, so
     * a caller can start it and forget it.
     */
    public CompletableFuture<Boolean> ensureBaseKnowledge() {
        return database.contents().findById(MCAgents.BASE_KNOWLEDGE_ID)
                .thenCompose(stored -> service.content(MCAgents.BASE_KNOWLEDGE_ID)
                        .thenCompose(published -> {
                            boolean current = stored.isPresent()
                                    && stored.get().version().equals(published.version());
                            if (current) {
                                return CompletableFuture.completedFuture(false);
                            }
                            // The categories first, and this is not optional: on a fresh
                            // install nothing has been stored yet, contents.category_id is a
                            // foreign key, and saving the pack straight away fails on the
                            // one run where it matters most — the player's first.
                            return service.categories()
                                    .thenCompose(this::storeCategories)
                                    .thenCompose(ignored -> database.contents().save(published))
                                    .thenApply(saved -> true);
                        }))
                .handle((updated, failure) -> {
                    if (failure != null) {
                        // Expected on a first run with no internet, and on every run while
                        // the service is asleep. Logged once, never surfaced.
                        LOGGER.log(Level.INFO,
                                "The base knowledge pack was not fetched this time: {0}",
                                failure.getMessage());
                        return false;
                    }
                    return updated;
                });
    }

    private CompletableFuture<Void> storeCategories(List<ContentCategory> categories) {
        // Categories first, and one at a time: contents.category_id is a foreign key, so a
        // pack whose category has not been stored yet cannot be written at all.
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ContentCategory category : categories) {
            chain = chain.thenCompose(ignored -> database.contents()
                    .saveCategory(category.id(), category.displayName())
                    .thenApply(saved -> null));
        }
        return chain;
    }

    private CompletableFuture<SyncResult> downloadOutdated(Map<String, String> published) {
        return database.contents().findStoredVersions().thenCompose(stored -> {
            List<String> wanted = new ArrayList<>();
            published.forEach((id, version) -> {
                if (!version.equals(stored.get(id))) {
                    wanted.add(id);
                }
            });
            wanted.sort(String::compareTo);
            return downloadEach(wanted, new SyncResult(0, 0, published.size(), List.of()));
        });
    }

    /**
     * Downloads packs one after another rather than all at once.
     *
     * <p>Sequential on purpose. This runs while the player is in a game: twenty parallel
     * downloads of pack bodies would compete with the game for bandwidth to save a few
     * seconds on something nobody is watching.
     */
    private CompletableFuture<SyncResult> downloadEach(List<String> ids, SyncResult carried) {
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(carried);
        }
        String id = ids.getFirst();
        List<String> rest = ids.subList(1, ids.size());

        return service.content(id)
                .thenCompose(pack -> database.contents().save(pack))
                .handle((saved, failure) -> failure == null
                        ? carried.withDownloaded()
                        : carried.withFailure(id))
                .thenCompose(next -> downloadEach(rest, next));
    }

    /**
     * What a sync managed.
     *
     * @param downloaded packs written
     * @param failed     packs that could not be, by count
     * @param published  how many the service publishes in total
     * @param failedIds  which ones failed, so a screen can name them
     */
    public record SyncResult(int downloaded, int failed, int published, List<String> failedIds) {

        public SyncResult {
            failedIds = List.copyOf(failedIds);
        }

        SyncResult withDownloaded() {
            return new SyncResult(downloaded + 1, failed, published, failedIds);
        }

        SyncResult withFailure(String id) {
            List<String> extended = new ArrayList<>(failedIds);
            extended.add(id);
            return new SyncResult(downloaded, failed + 1, published, extended);
        }

        /** Whether anything at all changed, so a caller can stay quiet when nothing did. */
        public boolean changedAnything() {
            return downloaded > 0;
        }
    }
}
