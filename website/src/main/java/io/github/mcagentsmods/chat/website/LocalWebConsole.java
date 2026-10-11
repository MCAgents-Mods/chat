package io.github.mcagentsmods.chat.website;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.mcagentsmods.chat.api.agent.Agent;
import io.github.mcagentsmods.chat.api.agent.ModelClient;
import io.github.mcagentsmods.chat.api.agent.PersonaStore;
import io.github.mcagentsmods.chat.api.storage.LocalDatabase;
import io.github.mcagentsmods.chat.api.web.WebConsole;
import io.github.mcagentsmods.chat.api.web.WebConsoleException;
import io.github.mcagentsmods.chat.common.agent.JdkHttpTransport;
import io.github.mcagentsmods.chat.common.agent.CachingModelClient;
import io.github.mcagentsmods.chat.common.agent.EmbeddingBackfill;
import io.github.mcagentsmods.chat.common.agent.RepositoryApiKeySource;
import io.github.mcagentsmods.chat.common.agent.openrouter.OpenRouterClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HexFormat;

/**
 * The local web console: the player's own data, served to their own browser.
 *
 * <h2>Not the wallet bridge, on purpose</h2>
 *
 * <p>{@code LocalWalletBridge} is a listener that lives for one handshake and stops the
 * instant a signature verifies — that is its contract and anything that extends its life is
 * a defect. <strong>This is the opposite by design.</strong> The console runs from the
 * moment the player opens it until they close it, because a browser tab whose server
 * vanished is a page that stops working while they are reading it.
 *
 * <p>They share their defences and nothing else, and neither should be changed to resemble
 * the other.
 *
 * <h2>Defences</h2>
 *
 * <ul>
 *   <li>Bound to {@code 127.0.0.1} on a port the operating system picks.</li>
 *   <li>A 256-bit token minted per run, required on every request, carried in the URL or in
 *       {@code X-MCAgents-Token}.</li>
 *   <li>A loopback {@code Host} header, which is what closes DNS rebinding.</li>
 *   <li>No CORS headers, so another origin cannot read a reply even if it could send one.</li>
 *   <li>Keys are listed as masks and handles. No route returns a key's value — see
 *       {@code ApiKeyHandles} for how one is named without being one.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 *
 * <p>Requests are served on a small pool rather than one thread, because asking the agent a
 * question can take a minute and the player will keep browsing while they wait. The pool is
 * bounded: this runs inside a game, and an unbounded pool answering a page that opened
 * twenty requests is memory the game does not have.
 */
public final class LocalWebConsole implements WebConsole {

    private static final Logger LOGGER = System.getLogger(LocalWebConsole.class.getName());

    private static final int TOKEN_BYTES = 32;

    /**
     * Enough for the largest thing a page sends, which is now {@code AGENTS.md} rather than
     * a prompt.
     *
     * <p>Still bounded, and the bound still matters: this listens on a socket anything on
     * the machine can open, and an unbounded read there is a way to make the game run out
     * of memory. It is sized so that a persona at the in-game editor's own character limit
     * fits with its JSON escaping, because a save that was silently cut in half would be
     * the worst possible failure for a file somebody wrote by hand.
     */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    /** Enough to answer a page's parallel fetches without letting the pool grow unbounded. */
    private static final int WORKER_THREADS = 4;

    /** Seconds {@link #close()} allows a request already in flight to finish. */
    private static final int STOP_GRACE_SECONDS = 2;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpServer server;
    private final ExecutorService workers;
    private final WebApi api;
    private final String token;
    private final URI url;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    private LocalWebConsole(HttpServer server, ExecutorService workers, WebApi api,
                            String token, URI url) {
        this.server = server;
        this.workers = workers;
        this.api = api;
        this.token = token;
        this.url = url;
    }

    /**
     * Starts the console.
     *
     * @param database where everything it shows comes from
     * @param agent    what answers a question asked in the browser; the same contract the
     *                 in-game screen uses, so the two behave identically
     * @throws WebConsoleException if no loopback port can be opened
     */
    public static LocalWebConsole start(LocalDatabase database, Agent agent) {
        return start(database, agent, defaultModelClient(database));
    }

    /**
     * @param personaStore the player's {@code AGENTS.md}, for a caller that has one but has
     *                     no model client to give — the default one is built, exactly as it
     *                     is for the overload above
     */
    public static LocalWebConsole start(LocalDatabase database, Agent agent,
                                        PersonaStore personaStore) {
        return start(database, agent, defaultModelClient(database), personaStore);
    }

    /**
     * @param modelClient used only to embed stored packs; the console never completes a chat
     *                    with it, because that is what {@code agent} is for
     */
    public static LocalWebConsole start(LocalDatabase database, Agent agent,
                                        ModelClient modelClient) {
        // Without a game directory there is no AGENTS.md to serve. The persona routes say
        // so rather than inventing an empty file to save into.
        return start(database, agent, modelClient, PersonaStore.notConfigured());
    }

    /**
     * @param personaStore the player's {@code AGENTS.md}. <strong>The same instance the game
     *                     uses</strong>: a save made on the chat page has to be the persona
     *                     the next in-game question is asked with, and a second store would
     *                     hold a second copy of the file for exactly as long as it took the
     *                     player to notice
     */
    public static LocalWebConsole start(LocalDatabase database, Agent agent,
                                        ModelClient modelClient, PersonaStore personaStore) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(modelClient, "modelClient");
        Objects.requireNonNull(personaStore, "personaStore");

        HttpServer server;
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new WebConsoleException("Could not open a local port for the web console", e);
        }

        ExecutorService workers = Executors.newFixedThreadPool(WORKER_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "MCAgents-web-console");
            // The game must be able to exit with a page half-loaded.
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);

        String token = randomHex();
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/knowledge/?t=" + token);

        LocalWebConsole console = new LocalWebConsole(server, workers,
                new WebApi(database, agent,
                        new EmbeddingBackfill(database, modelClient), personaStore),
                token, url);

        server.createContext("/", console::route);
        server.start();

        LOGGER.log(Level.INFO, "Web console listening on 127.0.0.1:{0}",
                server.getAddress().getPort());
        return console;
    }

    /**
     * The same stack the game's agent uses, built here for a console started without one.
     *
     * <p>Constructed rather than shared because {@code common} has no way to reach the
     * client the mod keeps. In the game the real client is passed in, so this is only ever
     * built by a caller that had none to give.
     */
    private static ModelClient defaultModelClient(LocalDatabase database) {
        return new CachingModelClient(new OpenRouterClient(
                new JdkHttpTransport(), new RepositoryApiKeySource(database.apiKeys())));
    }

    @Override
    public URI url() {
        return url;
    }

    @Override
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public boolean isRunning() {
        return !closed.get();
    }

    @Override
    public CompletableFuture<Void> stopped() {
        return stopped;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // A short grace period rather than zero: a page mid-load should finish rather than
        // show the player a half-rendered console as its parting image.
        server.stop(STOP_GRACE_SECONDS);
        workers.shutdownNow();
        stopped.complete(null);
        LOGGER.log(Level.INFO, "Web console stopped");
    }

    // ------------------------------------------------------------- routing

    private void route(HttpExchange exchange) throws IOException {
        try {
            if (!guard(exchange)) {
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

            if (path.startsWith("/api/")) {
                handleApi(exchange, method, path);
                return;
            }
            if ("GET".equals(method)) {
                handlePage(exchange, path);
                return;
            }
            sendError(exchange, 405, "Method not allowed.");
        } catch (IllegalArgumentException e) {
            // Thrown by the API for input it will not act on. The message is written to be
            // read by the player, so it goes back as it stands.
            sendError(exchange, 400, e.getMessage());
        } catch (RuntimeException e) {
            // Never the exception's own text: it can name a file path or a query. The
            // detail belongs in the log, where the player can find it and nobody else can.
            LOGGER.log(Level.ERROR, "The web console failed to answer a request", e);
            sendError(exchange, 500, "Something went wrong. The reason is in the game's log.");
        }
    }

    private void handleApi(HttpExchange exchange, String method, String path) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

        if (path.equals("/api/revision") && method.equals("GET")) {
            sendJson(exchange, 200, api.revision());
            return;
        }
        if (path.equals("/api/embeddings")) {
            if (method.equals("GET")) {
                sendJson(exchange, 200, api.embeddingStatus());
                return;
            }
            if (method.equals("POST")) {
                sendJson(exchange, 200, api.embedKnowledge());
                return;
            }
        }
        if (path.equals("/api/agent/persona")) {
            if (method.equals("GET")) {
                sendJson(exchange, 200, api.persona());
                return;
            }
            if (method.equals("PUT")) {
                sendJson(exchange, 200, api.savePersona(readBody(exchange)));
                return;
            }
        }
        if (path.equals("/api/models")) {
            if (method.equals("GET")) {
                sendJson(exchange, 200, api.models());
                return;
            }
            if (method.equals("PUT")) {
                sendJson(exchange, 200, api.saveModels(readBody(exchange)));
                return;
            }
        }
        if (path.equals("/api/routes")) {
            if (method.equals("GET")) {
                sendJson(exchange, 200, api.routes());
                return;
            }
            if (method.equals("PUT")) {
                sendJson(exchange, 200, api.saveRoute(readBody(exchange)));
                return;
            }
        }
        if (path.equals("/api/keys")) {
            if (method.equals("GET")) {
                sendJson(exchange, 200, api.keys(query));
                return;
            }
            if (method.equals("POST")) {
                sendJson(exchange, 201, api.addKey(readBody(exchange)));
                return;
            }
        }
        if (path.startsWith("/api/keys/") && method.equals("DELETE")) {
            // A handle, never a key. See ApiKeyHandles.
            sendChanged(exchange, api.deleteKey(decode(path.substring("/api/keys/".length()))),
                    "No such key.");
            return;
        }
        if (path.equals("/api/categories") && method.equals("GET")) {
            sendJson(exchange, 200, api.categories());
            return;
        }
        if (path.equals("/api/knowledge") && method.equals("GET")) {
            sendJson(exchange, 200, api.knowledge(query));
            return;
        }
        if (path.startsWith("/api/knowledge/") && method.equals("DELETE")) {
            String id = decode(path.substring("/api/knowledge/".length()));
            sendChanged(exchange, api.deletePack(id), "No such Knowledge Pack.");
            return;
        }
        if (path.startsWith("/api/knowledge/") && method.equals("GET")) {
            String id = decode(path.substring("/api/knowledge/".length()));
            sendOrNotFound(exchange, api.knowledgeById(id), "No such Knowledge Pack.");
            return;
        }
        if (path.equals("/api/wallets")) {
            if (method.equals("GET")) {
                sendJson(exchange, 200, api.wallets());
                return;
            }
            if (method.equals("POST")) {
                sendJson(exchange, 201, api.createWallet(readBody(exchange)));
                return;
            }
        }
        if (path.equals("/api/wallets/challenge") && method.equals("GET")) {
            sendJson(exchange, 200, api.walletChallenge(query));
            return;
        }
        if (path.equals("/api/wallets/verify") && method.equals("POST")) {
            sendJson(exchange, 200, api.verifyWallet(readBody(exchange)));
            return;
        }
        if (path.startsWith("/api/wallets/")) {
            String rest = path.substring("/api/wallets/".length());
            int slash = rest.indexOf('/');
            String walletId = decode(slash < 0 ? rest : rest.substring(0, slash));
            String sub = slash < 0 ? "" : rest.substring(slash + 1);

            // Every one of these names an account as well as a wallet, and the account is
            // checked rather than trusted — a request that names one account and a wallet in
            // another changes nothing. See AccountWallets.
            if (sub.startsWith("addresses")) {
                if (sub.equals("addresses") && method.equals("POST")) {
                    sendJson(exchange, 200, api.addWalletAddress(walletId, readBody(exchange)));
                    return;
                }
                if (method.equals("DELETE")) {
                    String platform = decode(sub.substring("addresses/".length()));
                    sendChanged(exchange,
                            api.removeWalletAddress(walletId, platform, query),
                            "No such address in that account.");
                    return;
                }
            } else if (sub.isEmpty()) {
                if (method.equals("PUT")) {
                    sendChanged(exchange, api.renameWallet(walletId, readBody(exchange)),
                            "No such wallet in that account.");
                    return;
                }
                if (method.equals("DELETE")) {
                    sendChanged(exchange, api.deleteWallet(walletId, query),
                            "No such wallet in that account.");
                    return;
                }
            }
        }
        if (path.equals("/api/chats")) {
            if (method.equals("GET")) {
                sendJson(exchange, 200, api.chats(query));
                return;
            }
            if (method.equals("POST")) {
                sendJson(exchange, 201, api.createChat(readBody(exchange)));
                return;
            }
        }
        if (path.startsWith("/api/chats/")) {
            String rest = path.substring("/api/chats/".length());
            if (rest.endsWith("/messages")) {
                String id = decode(rest.substring(0, rest.length() - "/messages".length()));
                if (method.equals("GET")) {
                    sendOrNotFound(exchange, api.chatMessages(id), "No such conversation.");
                    return;
                }
                if (method.equals("POST")) {
                    sendOrNotFound(exchange, api.sendMessage(id, readBody(exchange)),
                            "No such conversation.");
                    return;
                }
            } else {
                String id = decode(rest);
                if (method.equals("DELETE")) {
                    sendChanged(exchange, api.deleteChat(id), "No such conversation.");
                    return;
                }
                if (method.equals("PUT")) {
                    sendChanged(exchange, api.renameChat(id, readBody(exchange)),
                            "No such conversation.");
                    return;
                }
            }
        }
        sendError(exchange, 404, "No such endpoint.");
    }

    /**
     * Serves a page or an asset.
     *
     * <p>The three page paths map to files rather than being generated, and every one of
     * them is the same shell — the pages fetch what they show. That keeps the markup out of
     * Java, where it would be a string to escape rather than a file to edit.
     */
    private void handlePage(HttpExchange exchange, String path) throws IOException {
        String asset = switch (path) {
            case "/", "" -> null;
            case "/knowledge", "/knowledge/" -> "pages/knowledge.html";
            case "/chat", "/chat/" -> "pages/chat.html";
            case "/keys", "/keys/" -> "pages/keys.html";
            case "/model", "/model/" -> "pages/model.html";
            case "/wallets", "/wallets/" -> "pages/wallets.html";
            default -> path.startsWith("/assets/")
                    ? path.substring("/assets/".length())
                    // Anything else under /knowledge/ is a pack id, and the detail page
                    // reads it back out of the address bar rather than being told it here.
                    : path.startsWith("/knowledge/") ? "pages/knowledge-detail.html" : null;
        };

        if (asset == null) {
            // The root is not a page. Sending the browser on with the token attached is
            // what makes the address in the game window work when it is pasted.
            redirect(exchange, "/knowledge/?t=" + token);
            return;
        }

        Optional<byte[]> body = WebAssets.read(asset);
        if (body.isEmpty()) {
            sendError(exchange, 404, "No such page.");
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", WebAssets.contentType(asset));
        // The console shows live data and its address carries a token; neither should be
        // kept by a cache or handed to anything the page links to.
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        send(exchange, 200, body.get());
    }

    // ------------------------------------------------------------ security

    /**
     * Rejects anything that is not one of this console's own pages asking.
     *
     * <p>The {@code Host} check is what closes DNS rebinding: a hostile site that resolves
     * one of its own names to {@code 127.0.0.1} arrives carrying that name, and the token it
     * cannot read is not the only thing standing in its way.
     */
    private boolean guard(HttpExchange exchange) throws IOException {
        if (!isLoopbackHost(exchange.getRequestHeaders().getFirst("Host"))) {
            sendError(exchange, 403, "Forbidden.");
            return false;
        }
        if (isPublicAsset(exchange)) {
            return true;
        }
        String presented = exchange.getRequestHeaders().getFirst("X-MCAgents-Token");
        if (presented == null) {
            presented = parseQuery(exchange.getRequestURI().getRawQuery()).get("t");
        }
        if (!tokenMatches(presented)) {
            sendError(exchange, 403, "Forbidden.");
            return false;
        }
        return true;
    }

    /**
     * Whether this is a stylesheet, a script, or a chrome partial.
     *
     * <p>These are served without a token, and they have to be: a {@code <link>} in a static
     * page carries no query string, and the alternative is generating the markup in Java so
     * a token can be substituted into every asset URL — which would turn three files anyone
     * can edit into strings to escape.
     *
     * <p>Nothing is given away by it. Every asset is a file shipped inside the mod jar,
     * identical for every player and carrying no data of theirs. Everything that reads the
     * database is under {@code /api/}, and the pages themselves stay behind the token, so
     * the only thing an unguarded request can obtain is a copy of the console's own CSS.
     */
    private static boolean isPublicAsset(HttpExchange exchange) {
        return "GET".equalsIgnoreCase(exchange.getRequestMethod())
                && exchange.getRequestURI().getPath().startsWith("/assets/");
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        int colon = name.lastIndexOf(':');
        if (colon > 0 && !name.startsWith("[")) {
            name = name.substring(0, colon);
        }
        return name.equals("127.0.0.1") || name.equals("localhost")
                || name.equals("[::1]") || name.equals("::1");
    }

    /** Constant-time, so the token cannot be recovered a byte at a time. */
    private boolean tokenMatches(String candidate) {
        return candidate != null && MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ plumbing

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                values.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
            } else if (!pair.isEmpty()) {
                values.put(decode(pair), "");
            }
        }
        return values;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A malformed escape is not worth a 500; it simply matches nothing.
            return value;
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            // Bounded: an unbounded read on a socket anything local can open is a way to
            // make the game run out of memory.
            return new String(stream.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        }
    }

    private void sendOrNotFound(HttpExchange exchange, Optional<JsonObject> body, String missing)
            throws IOException {
        if (body.isPresent()) {
            sendJson(exchange, 200, body.get());
        } else {
            sendError(exchange, 404, missing);
        }
    }

    private void sendChanged(HttpExchange exchange, boolean changed, String missing)
            throws IOException {
        if (changed) {
            JsonObject body = new JsonObject();
            body.addProperty("ok", true);
            sendJson(exchange, 200, body);
        } else {
            sendError(exchange, 404, missing);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        send(exchange, 303, new byte[0]);
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        send(exchange, status, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendError(HttpExchange exchange, int status, String reason)
            throws IOException {
        sendJson(exchange, status, WebJson.error(
                reason == null || reason.isBlank() ? "Request refused." : reason));
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        // No Access-Control-Allow-Origin, deliberately: without it a browser refuses to let
        // another origin read anything this console says.
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private static String randomHex() {
        byte[] value = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
