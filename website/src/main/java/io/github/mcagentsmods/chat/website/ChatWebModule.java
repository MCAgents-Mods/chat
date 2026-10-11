package io.github.mcagentsmods.chat.website;

import com.google.gson.JsonObject;
import io.github.mcagentsmods.chat.api.MCAgents;
import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.agent.PersonaStore;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.common.agent.EmbeddingBackfill;
import io.github.mcagentsmods.core.api.model.ModelClient;
import io.github.mcagentsmods.core.api.web.NavLink;
import io.github.mcagentsmods.core.api.web.WebModule;
import io.github.mcagentsmods.core.api.web.WebRequest;
import io.github.mcagentsmods.core.api.web.WebResponse;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Chat's pages in MCAgents core's web console.
 *
 * <p>Core owns the server: the loopback port, the per-run token, the {@code Host} check, the
 * content security policy, the body limit and the shared page shell. This module is what is
 * chat's — six pages, their scripts and styles, and the JSON they read and write — served at
 * {@code /chat/…}, {@code /assets/chat/…} and {@code /api/chat/…}.
 *
 * <p>{@link #handle} runs on one of the console's worker threads, never the game's, so the
 * waits inside {@link WebApi} are allowed. An {@link IllegalArgumentException} it throws goes
 * back to the page as a 400 with its message, which is why those messages are written for the
 * player; anything else is logged by core and answered with a 500 that names nothing.
 */
public final class ChatWebModule implements WebModule {

    /** Chat's id in the console: its pages are under {@code /chat/}. */
    public static final String ID = "chat";

    private static final List<NavLink> NAVIGATION = List.of(
            new NavLink("Knowledge", "knowledge/"),
            new NavLink("Chat", "chat/"),
            new NavLink("Models", "model/"),
            new NavLink("Wallets", "wallets/"),
            new NavLink("API keys", "keys/"));

    private final WebApi api;

    private ChatWebModule(WebApi api) {
        this.api = api;
    }

    /**
     * The module over the game's own stores and clients.
     *
     * <p>The same agent the in-game screen uses, so a question asked in the browser is answered
     * as one asked in the game; the same model client, so embedding from the console reuses
     * the game's embedding cache; and the same persona store, because the console writes
     * {@code AGENTS.md} and a second store would leave the game answering from stale text.
     *
     * @param database     the player's local database
     * @param agent        the agent that answers questions
     * @param modelClient  the client embeddings are made with
     * @param personaStore where {@code AGENTS.md} is read and written
     * @return the module, ready to register with core's registry
     */
    public static ChatWebModule create(LocalDatabase database, Agent agent,
                                       ModelClient modelClient, PersonaStore personaStore) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(modelClient, "modelClient");
        Objects.requireNonNull(personaStore, "personaStore");
        return new ChatWebModule(new WebApi(database, agent,
                new EmbeddingBackfill(database, modelClient), personaStore));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return MCAgents.MOD_NAME;
    }

    @Override
    public List<NavLink> navigation() {
        return NAVIGATION;
    }

    @Override
    public Optional<String> page(String path) {
        String resource = switch (path) {
            case "", "knowledge", "knowledge/" -> "pages/knowledge.html";
            case "chat", "chat/" -> "pages/chat.html";
            case "keys", "keys/" -> "pages/keys.html";
            case "model", "model/" -> "pages/model.html";
            case "wallets", "wallets/" -> "pages/wallets.html";
            // /chat/knowledge/{id}: the page reads the id from its own address.
            default -> path.startsWith("knowledge/") ? "pages/knowledge-detail.html" : null;
        };
        return Optional.ofNullable(resource);
    }

    @Override
    public String resourceRoot() {
        return "mcagents/web/";
    }

    /**
     * Answers {@code /api/chat/{path}}.
     *
     * <p>The path arrives decoded once, by core. It is never decoded again here: a second
     * decode would let {@code %252F} become a slash after routing had already split on them.
     */
    @Override
    public WebResponse handle(WebRequest request) {
        String method = request.method();
        String path = request.path();
        Map<String, String> query = request.query();

        switch (path) {
            case "revision" -> {
                if (method.equals("GET")) {
                    return ok(api.revision());
                }
            }
            case "embeddings" -> {
                if (method.equals("GET")) {
                    return ok(api.embeddingStatus());
                }
                if (method.equals("POST")) {
                    return ok(api.embedKnowledge());
                }
            }
            case "agent/persona" -> {
                if (method.equals("GET")) {
                    return ok(api.persona());
                }
                if (method.equals("PUT")) {
                    return ok(api.savePersona(request.body()));
                }
            }
            case "models" -> {
                if (method.equals("GET")) {
                    return ok(api.models());
                }
                if (method.equals("PUT")) {
                    return ok(api.saveModels(request.body()));
                }
            }
            case "routes" -> {
                if (method.equals("GET")) {
                    return ok(api.routes());
                }
                if (method.equals("PUT")) {
                    return ok(api.saveRoute(request.body()));
                }
            }
            case "keys" -> {
                if (method.equals("GET")) {
                    return ok(api.keys(query));
                }
                if (method.equals("POST")) {
                    return json(201, api.addKey(request.body()));
                }
            }
            case "categories" -> {
                if (method.equals("GET")) {
                    return ok(api.categories());
                }
            }
            case "knowledge" -> {
                if (method.equals("GET")) {
                    return ok(api.knowledge(query));
                }
            }
            case "wallets" -> {
                if (method.equals("GET")) {
                    return ok(api.wallets());
                }
                if (method.equals("POST")) {
                    return json(201, api.createWallet(request.body()));
                }
            }
            case "wallets/challenge" -> {
                if (method.equals("GET")) {
                    return ok(api.walletChallenge(query));
                }
            }
            case "wallets/verify" -> {
                if (method.equals("POST")) {
                    return ok(api.verifyWallet(request.body()));
                }
            }
            case "chats" -> {
                if (method.equals("GET")) {
                    return ok(api.chats(query));
                }
                if (method.equals("POST")) {
                    return json(201, api.createChat(request.body()));
                }
            }
            default -> {
                WebResponse answer = item(method, path, query, request.body());
                if (answer != null) {
                    return answer;
                }
            }
        }
        return WebResponse.error(404, "No such endpoint.");
    }

    /** The routes that name one thing in their path, or {@code null} for none of them. */
    private WebResponse item(String method, String path, Map<String, String> query, String body) {
        if (path.startsWith("keys/") && method.equals("DELETE")) {
            return changed(api.deleteKey(path.substring("keys/".length())), "No such key.");
        }
        if (path.startsWith("knowledge/")) {
            String id = path.substring("knowledge/".length());
            if (method.equals("DELETE")) {
                return changed(api.deletePack(id), "No such Knowledge Pack.");
            }
            if (method.equals("GET")) {
                return orNotFound(api.knowledgeById(id), "No such Knowledge Pack.");
            }
        }
        if (path.startsWith("wallets/")) {
            String rest = path.substring("wallets/".length());
            int slash = rest.indexOf('/');
            String walletId = slash < 0 ? rest : rest.substring(0, slash);
            String sub = slash < 0 ? "" : rest.substring(slash + 1);
            if (sub.equals("addresses") && method.equals("POST")) {
                return ok(api.addWalletAddress(walletId, body));
            }
            if (sub.startsWith("addresses/") && method.equals("DELETE")) {
                return changed(api.removeWalletAddress(walletId,
                                sub.substring("addresses/".length()), query),
                        "No such address in that account.");
            }
            if (sub.isEmpty() && method.equals("PUT")) {
                return changed(api.renameWallet(walletId, body), "No such wallet in that account.");
            }
            if (sub.isEmpty() && method.equals("DELETE")) {
                return changed(api.deleteWallet(walletId, query), "No such wallet in that account.");
            }
        }
        if (path.startsWith("chats/")) {
            String rest = path.substring("chats/".length());
            if (rest.endsWith("/messages")) {
                String id = rest.substring(0, rest.length() - "/messages".length());
                if (method.equals("GET")) {
                    return orNotFound(api.chatMessages(id), "No such conversation.");
                }
                if (method.equals("POST")) {
                    return orNotFound(api.sendMessage(id, body), "No such conversation.");
                }
            } else {
                if (method.equals("DELETE")) {
                    return changed(api.deleteChat(rest), "No such conversation.");
                }
                if (method.equals("PUT")) {
                    return changed(api.renameChat(rest, body), "No such conversation.");
                }
            }
        }
        return null;
    }

    private static WebResponse ok(JsonObject body) {
        return json(200, body);
    }

    private static WebResponse json(int status, JsonObject body) {
        return WebResponse.json(status, body.toString());
    }

    private static WebResponse orNotFound(Optional<JsonObject> body, String missing) {
        return body.map(ChatWebModule::ok).orElseGet(() -> WebResponse.error(404, missing));
    }

    private static WebResponse changed(boolean changed, String missing) {
        return changed ? WebResponse.ok("{\"ok\":true}") : WebResponse.error(404, missing);
    }
}
