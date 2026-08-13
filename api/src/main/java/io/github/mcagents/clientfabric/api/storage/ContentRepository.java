package io.github.mcagents.clientfabric.api.storage;

import io.github.mcagents.clientfabric.api.model.ContentCategory;
import io.github.mcagents.clientfabric.api.model.ContentQuery;
import io.github.mcagents.clientfabric.api.model.KnowledgeContent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Downloaded Knowledge Packs and the categories they are filed under.
 *
 * <p>Everything here is local. A pack is written once when it is downloaded and read from
 * disk from then on, with or without a network connection.
 *
 * <p>Every method is asynchronous — see {@link LocalDatabase} for why.
 */
public interface ContentRepository {

    /** Every category, by display name. */
    CompletableFuture<List<ContentCategory>> findAllCategories();

    /**
     * Stores a category under the id the central service gave it, replacing what is there.
     *
     * @param id          the identifier from the central service
     * @param displayName what the player sees
     */
    CompletableFuture<ContentCategory> saveCategory(long id, String displayName);

    /** Packs matching a browser query, by title. */
    CompletableFuture<List<KnowledgeContent>> find(ContentQuery query);

    /** One pack, or empty if it has not been downloaded. */
    CompletableFuture<Optional<KnowledgeContent>> findById(String id);

    /**
     * The stored version of every pack, keyed by pack id.
     *
     * <p>This is what an update check sends to the central service: comparing the stored
     * version against the published one is the whole protocol.
     */
    CompletableFuture<Map<String, String>> findStoredVersions();

    /**
     * Writes a downloaded pack, replacing any earlier version of the same pack.
     *
     * @return the stored pack
     */
    CompletableFuture<KnowledgeContent> save(KnowledgeContent content);

    /**
     * Removes a pack.
     *
     * @return whether a pack was removed
     */
    CompletableFuture<Boolean> remove(String id);
}
