package io.github.mcagentsmods.chat.api.mcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The {@code {org-or-user}/{repo}} pair that decides where a cloned MCP server lands.
 *
 * <p>This type exists to make one class of bug impossible. The coordinate is frequently
 * chosen by a language model rather than by a person, and it is turned straight into a
 * filesystem path under {@code {minecraft_dir}/MCAgents/chat/mcps/}. An unvalidated
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
     * <p>The path must be exactly {@code /{owner}/{repository}}, with an optional
     * {@code .git}. Anything longer is refused rather than cut down to its first two segments,
     * because what git fetches and what the player is told was fetched must be the same
     * repository: {@code https://host/a/b/../../x} would otherwise be filed, and reported, as
     * {@code a/b}.
     *
     * @throws IllegalArgumentException if the URL is not an https URL naming exactly an owner
     *         and a repository
     */
    public static RepositoryCoordinate fromCloneUrl(String url) {
        String[] parts = pathOf(url);
        return new RepositoryCoordinate(parts[0], parts[1]);
    }

    /**
     * The host a clone URL points at, lowercased.
     *
     * <p>The coordinate does not carry it, because the directory layout has no place for it,
     * so whoever tells the player what was cloned names it from here: two hosts can both serve
     * an {@code owner/repository} of the same name.
     *
     * @throws IllegalArgumentException under the same conditions as {@link #fromCloneUrl}
     */
    public static String hostOf(String url) {
        pathOf(url);
        return URI.create(url.trim()).getHost().toLowerCase(Locale.ROOT);
    }

    /**
     * The URL git is given: rebuilt from the host and the coordinate, never the text as
     * received, so nothing the coordinate does not describe reaches git.
     *
     * @throws IllegalArgumentException under the same conditions as {@link #fromCloneUrl}
     */
    public static String canonicalCloneUrl(String url) {
        RepositoryCoordinate coordinate = fromCloneUrl(url);
        return "https://" + hostOf(url) + "/" + coordinate.slug() + ".git";
    }

    private static String[] pathOf(String url) {
        Objects.requireNonNull(url, "url");
        String trimmed = url.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException(
                    "Only https clone URLs are supported, got '" + url + "'");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("Not a valid clone URL: '" + url + "'");
        }
        // Credentials, a port, a query or a fragment all mean the URL is doing something a
        // repository address does not need, and an unparsed authority means the host is not
        // what it appears to be.
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Clone URL must be https://{host}/{owner}/{repository}: '" + url + "'");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        String[] parts = path.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Clone URL must name exactly {owner}/{repository}: '" + url + "'");
        }
        return parts;
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
