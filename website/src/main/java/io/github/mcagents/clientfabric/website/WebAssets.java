package io.github.mcagents.clientfabric.website;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;

/**
 * The pages, stylesheets, and scripts, read out of the mod jar.
 *
 * <p>Everything the console serves ships inside the jar under {@code mcagents/web/}. There
 * is no directory on disk to point at and nothing is fetched from anywhere — which is the
 * offline-first mandate applied to markup, and the reason the design system forbids a CDN.
 * A player with no internet gets the same console as a player with one.
 */
final class WebAssets {

    /** Where the assets live inside the jar. The resource path is the URL path. */
    private static final String ROOT = "/mcagents/web/";

    private WebAssets() {
        throw new AssertionError("No instances.");
    }

    /**
     * Reads one asset.
     *
     * @param path a URL path relative to the asset root, already stripped of its prefix
     * @return the bytes, or empty if there is no such asset
     */
    static Optional<byte[]> read(String path) {
        String resource = resolve(path);
        if (resource == null) {
            return Optional.empty();
        }
        try (InputStream stream = WebAssets.class.getResourceAsStream(resource)) {
            return stream == null ? Optional.empty() : Optional.of(stream.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Turns a URL path into a resource path, or {@code null} if it is not one this may serve.
     *
     * <p>The check is a whitelist of characters rather than a search for {@code ..}, because
     * the ways of writing {@code ..} are more numerous than the ways of listing what a
     * filename may contain. Nothing here is served from disk, so a traversal could at worst
     * read another resource out of the jar — but "at worst" is not a reason to allow it.
     */
    private static String resolve(String path) {
        if (path == null || path.isEmpty() || path.length() > 200) {
            return null;
        }
        for (int i = 0; i < path.length(); i++) {
            char character = path.charAt(i);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '/' || character == '-' || character == '_'
                    || character == '.';
            if (!allowed) {
                return null;
            }
        }
        if (path.contains("..") || path.contains("//") || path.startsWith("/")) {
            return null;
        }
        return ROOT + path;
    }

    /** The content type for an asset, by extension. */
    static String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "application/octet-stream";
    }
}
