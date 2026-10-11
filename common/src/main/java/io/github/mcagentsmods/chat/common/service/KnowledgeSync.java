package io.github.mcagentsmods.chat.common.service;

import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.model.ContentCategory;
import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;

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
 * <p>Nothing here runs on a timer or in the background. The player presses update, or
 * downloads one pack, or the mod fetches the one base pack at startup, and that is the
 * complete list of reasons this class does anything. A mod that quietly re-downloaded a
 * library would be spending someone else's bandwidth to answer a question nobody asked.
 *
 * <h2>Nothing here downloads a pack the player did not choose</h2>
 *
 * <p>There is deliberately no "fetch everything published" operation. Updating brings the
 * packs on this machine up to date; taking a new one is done from that pack's own page,
 * where the player can see what they are taking. A button that filled the library because
 * somebody wanted their existing packs refreshed would spend their bandwidth, their disk,
 * and — since every new pack then needs embedding — their model credit, on content they
 * never chose.
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
     * Updates the packs this machine already has, and fetches nothing new.
     *
     * <p>This is the update button, and it is the whole of what that button does: a player
     * pressing "update knowledge" is asking for what they have to be current, not for
     * everything the service publishes to be pulled onto their disk.
     *
     * <p>New packs are downloaded one at a time from their own page, which is where a player
     * can see what they are taking.
     */
    public CompletableFuture<SyncResult> updateStored() {
        return service.categories()
                .thenCompose(this::storeCategories)
                .thenCompose(ignored -> service.publishedVersions())
                .thenCompose(published -> database.contents().findStoredVersions()
                        .thenCompose(stored -> {
                            List<String> wanted = new ArrayList<>();
                            stored.forEach((id, version) -> {
                                String latest = published.get(id);
                                // Absent means the service no longer publishes it. The local
                                // copy is left alone: it still answers questions, and
                                // deleting somebody's pack because a catalog changed is not
                                // an update.
                                if (latest != null && !latest.equals(version)) {
                                    wanted.add(id);
                                }
                            });
                            wanted.sort(String::compareTo);
                            return downloadEach(wanted,
                                    new SyncResult(0, 0, published.size(), List.of()));
                        }));
    }

    /**
     * Downloads one pack, whether or not it is already stored.
     *
     * <p>This is the download button on a pack's own page. Unlike {@link #updateStored()} it
     * does not compare versions first: the player pressed the button for this pack, and a
     * button that decided the pack was already current and did nothing would be a button that
     * appears broken.
     *
     * <p>The categories are stored first for the same reason they are in
     * {@link #ensureBaseKnowledge()} — {@code contents.category_id} is a foreign key, and a
     * player downloading their first pack has no categories yet.
     */
    public CompletableFuture<KnowledgeContent> download(String id) {
        Objects.requireNonNull(id, "id");
        return service.categories()
                .thenCompose(this::storeCategories)
                .thenCompose(ignored -> service.content(id))
                .thenCompose(this::store);
    }

    /**
     * What the service publishes that is not stored here, and what is out of date.
     *
     * <p>The check without the download. The chat page runs this when it opens, so the
     * player is told there is something to fetch rather than having to press a button to
     * find out — and pressing the button remains the only thing that actually spends
     * bandwidth.
     */
    public CompletableFuture<UpdateStatus> checkForUpdates() {
        return service.publishedVersions().thenCompose(published ->
                database.contents().findStoredVersions().thenApply(stored -> {
                    List<String> missing = new ArrayList<>();
                    List<String> outdated = new ArrayList<>();
                    published.forEach((id, version) -> {
                        String have = stored.get(id);
                        if (have == null) {
                            missing.add(id);
                        } else if (!have.equals(version)) {
                            outdated.add(id);
                        }
                    });
                    missing.sort(String::compareTo);
                    outdated.sort(String::compareTo);
                    return new UpdateStatus(missing, outdated, published.size());
                }));
    }

    /**
     * Whether one pack is behind what the service publishes.
     *
     * <p>The check on a pack's own page, where the whole-library check would be the wrong
     * question: a reader looking at one pack wants to know about that pack, and asking about
     * every other one costs a listing they did not ask for.
     *
     * <p>One request, by id, and it downloads nothing.
     */
    public CompletableFuture<PackStatus> checkPack(String id) {
        Objects.requireNonNull(id, "id");
        return database.contents().findById(id).thenCompose(stored ->
                service.content(id).thenApply(published -> new PackStatus(
                        id,
                        stored.map(KnowledgeContent::version).orElse(null),
                        published.version())));
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

    /**
     * Writes a downloaded pack, deciding what happens to any vector already stored for it.
     *
     * <h2>A new version drops the embedding</h2>
     *
     * <p>An embedding describes the text it was made from. A new version is new text, so the
     * stored vector no longer describes the pack it is attached to — it would keep matching
     * questions about wording that is no longer there, and would keep the pack out of results
     * it now belongs in. Silently wrong, and invisible: the pack still looks embedded.
     *
     * <p>So the vector goes, the pack shows as needing one, and the next press of the embed
     * button restores it. Losing an embedding costs one provider call; keeping a stale one
     * costs every answer that pack takes part in.
     *
     * <h2>The same version keeps it</h2>
     *
     * <p>Re-downloading a pack at the version already stored — which the download button on a
     * pack's own page will happily do — brings back byte-identical text. Throwing the vector
     * away there would charge the player to compute the same numbers again for no reason.
     *
     * <p>Version is the protocol's word for "the text changed": the service publishes a new
     * version rather than editing one in place, so comparing versions is comparing texts.
     */
    private CompletableFuture<KnowledgeContent> store(KnowledgeContent published) {
        return database.contents().findById(published.id()).thenCompose(stored -> {
            boolean sameText = stored.isPresent()
                    && stored.get().version().equals(published.version())
                    && stored.get().hasEmbedding();

            return database.contents().save(sameText
                    ? withEmbedding(published, stored.get().embeddedData())
                    : published);
        });
    }

    private static KnowledgeContent withEmbedding(KnowledgeContent pack, String embedding) {
        return new KnowledgeContent(pack.id(), pack.categoryId(), pack.version(),
                pack.premium(), pack.title(), pack.rawContent(), embedding,
                pack.createdAt(), pack.updatedAt());
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
                .thenCompose(this::store)
                .handle((saved, failure) -> failure == null
                        ? carried.withDownloaded()
                        : carried.withFailure(id))
                .thenCompose(next -> downloadEach(rest, next));
    }

    /**
     * Where one pack stands against what is published.
     *
     * @param id             the pack
     * @param storedVersion  what this machine has, or {@code null} if it has none
     * @param publishedVersion what the service publishes
     */
    public record PackStatus(String id, String storedVersion, String publishedVersion) {

        /** Whether downloading would change anything. */
        public boolean updateAvailable() {
            return !publishedVersion.equals(storedVersion);
        }

        /** Whether this machine has the pack at all. */
        public boolean stored() {
            return storedVersion != null;
        }
    }

    /**
     * What a check found, without downloading any of it.
     *
     * @param missingIds  published packs this machine does not have
     * @param outdatedIds packs stored here at a version the service has replaced
     * @param published   how many packs the service publishes in total
     */
    public record UpdateStatus(List<String> missingIds, List<String> outdatedIds, int published) {

        public UpdateStatus {
            missingIds = List.copyOf(missingIds);
            outdatedIds = List.copyOf(outdatedIds);
        }

        /** How many packs a refresh would fetch. */
        public int available() {
            return missingIds.size() + outdatedIds.size();
        }

        /** Whether there is anything worth telling the player about. */
        public boolean hasUpdates() {
            return available() > 0;
        }
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
