package io.github.mcagentsmods.chat.common.ui;

import io.github.mcagentsmods.core.api.model.GlobalModels;
import io.github.mcagentsmods.core.api.model.ModelPlatform;
import io.github.mcagentsmods.core.api.model.ModelProvider;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The line the Models page shows under a provider: where a request to it goes, and whether it
 * can be answered.
 *
 * <p>Worked out from the same global model the request is routed by, so the line cannot claim
 * a route the request does not take. No Minecraft here: the page turns a {@link Note} into its
 * translated text.
 */
public final class ProviderNotes {

    /** What a request to the provider meets. */
    public enum Kind {
        /** Called at its address, with a key stored for it. */
        CALLED,
        /** Called at its address, but with no key stored the request fails asking for one. */
        NO_KEY,
        /** No address is known for it; nothing can be called. */
        UNKNOWN
    }

    /**
     * One provider's line.
     *
     * @param kind     what a request meets
     * @param provider the provider, normalised
     * @param host     the host it is called at, or blank when {@link Kind#UNKNOWN}
     */
    public record Note(Kind kind, String provider, String host) {

        public Note {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(host, "host");
        }

        /** Whether the provider is OpenRouter, which the page names rather than giving its host. */
        public boolean isOpenRouter() {
            return ModelPlatform.isOpenRouter(provider);
        }
    }

    private ProviderNotes() {
        throw new AssertionError("No instances.");
    }

    /**
     * The line for {@code provider}.
     *
     * @param models   the global model, for its providers and their addresses
     * @param provider the provider as the field holds it, typed or picked
     * @param keyed    the providers an active key is stored for, normalised
     */
    public static Note of(GlobalModels models, String provider, Set<String> keyed) {
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(keyed, "keyed");
        String id = provider == null || provider.isBlank()
                ? ModelPlatform.OPENROUTER
                : ModelPlatform.normalise(provider);
        Optional<ModelProvider> known = models.provider(id);
        if (known.isEmpty()) {
            return new Note(Kind.UNKNOWN, id, "");
        }
        String host = host(known.get().baseUrl());
        return new Note(keyed.contains(id) ? Kind.CALLED : Kind.NO_KEY, id, host);
    }

    /**
     * The host and port of {@code baseUrl}, which is what a player recognises an address by; the
     * whole address when it does not parse.
     */
    public static String host(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            if (uri.getHost() == null) {
                return baseUrl.trim();
            }
            return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        } catch (IllegalArgumentException unparseable) {
            return baseUrl.trim();
        }
    }
}
