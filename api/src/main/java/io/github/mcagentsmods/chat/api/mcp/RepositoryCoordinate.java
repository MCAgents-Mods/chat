package io.github.mcagentsmods.chat.api.mcp;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The {@code {org-or-user}/{repo}} pair that decides where a cloned MCP server lands.
 *
 * <p>This type exists to make one class of bug impossible. The coordinate is frequently
 * chosen by a language model rather than by a person, and it is turned straight into a
 * filesystem path under {@code {minecraft_dir}/MCAgents/mcps/}. An unvalidated
 * {@code ../../..} there writes anywhere the game process can reach. So validation happens
 * in the constructor, once, and every other class can take a {@code RepositoryCoordinate}
 * and stop thinking about it.
 *
 * @param owner the GitHub organization or user
 * @param repository the repository name
 */
public record RepositoryCoordinate(String owner, String repository) {

    /**
     * What a path segment may contain.
     *
     * <p>Deliberately narrower than what GitHub accepts. A name outside this set is far
     * more likely to be an attempt at path traversal than a repository someone genuinely
     * wants, and the cost of rejecting a legitimate oddity is one clear error message.
     */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    public RepositoryCoordinate {
        owner = requireSegment(owner, "owner");
        repository = requireSegment(repository, "repository");
    }

    private static String requireSegment(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (!SEGMENT.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Invalid " + field + " '" + value + "' — letters, digits, dot, dash and "
                            + "underscore only, starting with a letter or digit");
        }
        if (trimmed.equals(".") || trimmed.equals("..")) {
            throw new IllegalArgumentException("Invalid " + field + " '" + value + "'");
        }
        return trimmed;
    }

    /**
     * Reads a coordinate out of a clone URL.
     *
     * <p>Only {@code https} is accepted. {@code ssh}, {@code git} and {@code file} URLs are
     * refused: the first two need credentials the mod does not hold, and {@code file://}
     * would let a prompt reach anything on the player's disk.
     *
     * @throws IllegalArgumentException if the URL is not an https URL naming an owner and a
     *         repository
     */
    public static RepositoryCoordinate fromCloneUrl(String url) {
        Objects.requireNonNull(url, "url");
        String trimmed = url.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException(
                    "Only https clone URLs are supported, got '" + url + "'");
        }
        String withoutScheme = trimmed.substring("https://".length());
        int slash = withoutScheme.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Clone URL names no repository: '" + url + "'");
        }
        String path = withoutScheme.substring(slash + 1);
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String[] parts = path.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "Clone URL must name {owner}/{repository}: '" + url + "'");
        }
        return new RepositoryCoordinate(parts[0], parts[1]);
    }

    /** {@code {owner}/{repository}} — how a coordinate is written in prose and in logs. */
    public String slug() {
        return owner + "/" + repository;
    }

    @Override
    public String toString() {
        return slug();
    }
}
