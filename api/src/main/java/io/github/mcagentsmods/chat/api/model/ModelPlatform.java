package io.github.mcagentsmods.chat.api.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Who serves a model — the {@code platform} half of a {@link ModelName}, and where that
 * platform's own API lives.
 *
 * <h2>OpenRouter is the fallback, not the only route</h2>
 *
 * <p>{@link #OPENROUTER} is what everything falls back to: a key stored without a platform, a
 * model id with no platform segment, a platform with no direct URL, and a platform the player
 * holds no key for all end up going through the gateway, exactly as they did before direct
 * routes existed. A direct call happens only when a player has deliberately stored a key for
 * that platform <em>and</em> a base URL is known for it.
 *
 * <h2>The known base URLs are suggestions, not a whitelist</h2>
 *
 * <p>Same rule as {@link ModelCatalog}: the map below saves a player from typing a URL for the
 * platforms whose API is at a well-known address, and
 * {@link #baseUrlSettingKey(String) the setting} overrides it for anything else — a
 * self-hosted gateway, a regional endpoint, a platform that shipped after this release. A
 * platform that appears in neither is not rejected; it routes through OpenRouter.
 *
 * <p><strong>Only OpenAI-compatible platforms are listed.</strong> Every direct call reuses the
 * request shape in {@code common.agent.openrouter} — {@code POST {base}/chat/completions} and
 * {@code POST {base}/embeddings}, with a bearer token — because that shape is OpenAI's and
 * OpenRouter's alike. Anthropic and Google are deliberately absent: their own APIs take a
 * different path, a different auth header, and a different body, so listing them would produce
 * a request the platform rejects. They work through OpenRouter, which is where they belong
 * until something speaks their wire format.
 */
public final class ModelPlatform {

    /** The gateway every unrouted request goes through, and the default for a stored key. */
    public static final String OPENROUTER = "openrouter";

    /** Where OpenRouter itself lives. Not in the map below: it is the fallback, not a route. */
    public static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";

    /** Prefix of the setting that overrides, or supplies, one platform's base URL. */
    public static final String BASE_URL_SETTING_PREFIX = "api.base-url.";

    /**
     * Platforms whose OpenAI-compatible API is at a documented, stable address.
     *
     * <p>Ordered so the interface can offer them in a sensible order rather than a hash order.
     */
    private static final Map<String, String> DIRECT_BASE_URLS = directBaseUrls();

    private static Map<String, String> directBaseUrls() {
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("openai", "https://api.openai.com/v1");
        urls.put("deepseek", "https://api.deepseek.com/v1");
        urls.put("mistralai", "https://api.mistral.ai/v1");
        urls.put("groq", "https://api.groq.com/openai/v1");
        urls.put("xai", "https://api.x.ai/v1");
        urls.put("together", "https://api.together.xyz/v1");
        return Map.copyOf(urls);
    }

    private ModelPlatform() {
        throw new AssertionError("No instances.");
    }

    /**
     * The platform id to store: trimmed and lowercased, with anything blank falling back to
     * {@link #OPENROUTER}.
     *
     * <p>Applied before the write, like every other half of a model name.
     */
    public static String normalise(String platform) {
        if (platform == null || platform.isBlank()) {
            return OPENROUTER;
        }
        return platform.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether {@code platform} names the gateway rather than a platform to call directly. */
    public static boolean isOpenRouter(String platform) {
        return OPENROUTER.equals(normalise(platform));
    }

    /** The base URL this release knows for {@code platform}, if it knows one. */
    public static Optional<String> knownBaseUrl(String platform) {
        return Optional.ofNullable(DIRECT_BASE_URLS.get(normalise(platform)));
    }

    /** The platforms this release can route to without the player configuring a URL. */
    public static Map<String, String> knownDirectBaseUrls() {
        return DIRECT_BASE_URLS;
    }

    /**
     * The {@code settings} key holding {@code platform}'s base URL.
     *
     * <p>A value stored here wins over {@link #knownBaseUrl(String)}, which is what makes a
     * self-hosted or regional endpoint reachable without a release.
     */
    public static String baseUrlSettingKey(String platform) {
        return BASE_URL_SETTING_PREFIX + normalise(platform);
    }

    /**
     * Whether {@code url} is one this mod will send a key to.
     *
     * <p>{@code https} only, with one exception: a loopback host, so a player running a local
     * gateway (Ollama, LM Studio, a proxy) can point at it. Anything else — a plain-text URL to
     * a remote host — would put the player's key on the wire in the clear, and
     * {@code .agents/security/api-keys.md} is the reason that is refused rather than warned
     * about.
     */
    public static boolean isUsableBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("https://")) {
            return true;
        }
        return trimmed.startsWith("http://localhost")
                || trimmed.startsWith("http://127.0.0.1")
                || trimmed.startsWith("http://[::1]");
    }
}
