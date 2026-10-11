package io.github.mcagentsmods.chat.api.storage;

import io.github.mcagentsmods.chat.api.model.ContentCategory;
import io.github.mcagentsmods.chat.api.model.ContentQuery;
import io.github.mcagentsmods.chat.api.model.KnowledgeContent;
import io.github.mcagentsmods.chat.api.model.KnowledgeSummary;
import io.github.mcagentsmods.chat.api.model.Page;
import io.github.mcagentsmods.chat.api.model.PageRequest;

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
     * Stores a category under the id it is given, replacing what is there.
     *
     * @param id          the category's identifier
     * @param displayName what the player sees
     */
    CompletableFuture<ContentCategory> saveCategory(long id, String displayName);

    /** Packs matching a browser query, by title. */
    CompletableFuture<List<KnowledgeContent>> find(ContentQuery query);

    /**
     * One window of the packs matching a query, without their bodies.
     *
     * <p>Summaries, and a window at a time, because the alternative is reading every
     * pack's text and embedding off disk to render a list of titles — see
     * {@link KnowledgeSummary}. The total is counted so a caller knows whether anything
     * follows without asking for it.
     */
    CompletableFuture<Page<KnowledgeSummary>> findSummaryPage(ContentQuery query, PageRequest page);

    /** One pack, or empty if it has not been downloaded. */
    CompletableFuture<Optional<KnowledgeContent>> findById(String id);

    /**
     * The stored version of every pack, keyed by pack id.
     *
     * <p>Comparing a stored version against another is all a version check needs.
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
