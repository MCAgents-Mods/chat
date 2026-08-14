package io.github.mcagents.clientfabric.common.wallet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import io.github.mcagents.clientfabric.api.wallet.WalletBinding;
import io.github.mcagents.clientfabric.api.wallet.WalletBridge;
import io.github.mcagents.clientfabric.api.wallet.WalletBridgeException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The loopback web server that carries one MetaMask handshake, and then stops.
 *
 * <h2>Why the mod serves a web page</h2>
 *
 * <p>MetaMask is a browser extension. {@code window.ethereum} exists on a web page and
 * nowhere else — there is no Java API, no local socket, no file to read. So for the length
 * of one handshake the mod becomes a web server: it hands the player's browser a page, that
 * page asks MetaMask to sign a one-off message, and the signature comes back here to be
 * checked.
 *
 * <h2>How small this is kept</h2>
 *
 * <ul>
 *   <li><strong>Loopback only.</strong> Bound to {@code 127.0.0.1} on a port the operating
 *       system picks. Nothing outside the machine can reach it.</li>
 *   <li><strong>One-time token.</strong> Every request must carry the 256-bit token that is
 *       only in the URL. Another program on the machine cannot guess it, and another web
 *       page cannot read it.</li>
 *   <li><strong>Loopback {@code Host} required.</strong> A hostile site that points a
 *       domain of its own at {@code 127.0.0.1} arrives with that domain in the {@code Host}
 *       header and is refused, which is what closes DNS rebinding.</li>
 *   <li><strong>Stops on the first success.</strong> The listener is shut the moment a
 *       signature verifies, and also on cancel, on timeout, and on close. There is no state
 *       in which it outlives the screen that opened it.</li>
 * </ul>
 *
 * <h2>What is never asked for</h2>
 *
 * <p>A signature over a message the mod chose. Nothing else. The page has no field for a
 * private key or a recovery phrase, this class would not know what to do with one, and a
 * {@code personal_sign} reaches no chain, moves no funds, and grants no approval.
 *
 * <p>Offline-first still holds: this touches the loopback interface and never the network,
 * and every wallet already bound stays readable whether or not a browser is ever opened.
 */
public final class LocalWalletBridge implements WalletBridge {

    private static final Logger LOGGER = System.getLogger(LocalWalletBridge.class.getName());

    /** How long the player has to finish in the browser before the bridge gives up. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private static final String PAGE_RESOURCE = "/mcagents/wallet-bridge.html";
    private static final int TOKEN_BYTES = 32;
    private static final int NONCE_BYTES = 16;

    /** Enough for a signature and an address; anything larger is not a reply we sent for. */
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpServer server;
    private final ScheduledExecutorService scheduler;
    private final String token;
    private final String message;
    private final URI url;
    private final CompletableFuture<WalletBinding> binding = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private LocalWalletBridge(HttpServer server, ScheduledExecutorService scheduler,
                              String token, String message, URI url) {
        this.server = server;
        this.scheduler = scheduler;
        this.token = token;
        this.message = message;
        this.url = url;
    }

    /**
     * Starts a bridge for one binding attempt.
     *
     * @param accountId the account the wallet will land under, named in the signed message
     *                  so the player can see what they are agreeing to
     * @throws WalletBridgeException if no loopback port can be opened at all
     */
    public static LocalWalletBridge start(String accountId, Duration timeout) {
        Objects.requireNonNull(accountId, "accountId");
        Duration limit = timeout == null || timeout.isZero() || timeout.isNegative()
                ? DEFAULT_TIMEOUT
                : timeout;

        String token = randomHex(TOKEN_BYTES);
        String message = challengeMessage(accountId, randomHex(NONCE_BYTES));

        HttpServer server;
        try {
            // Port 0: the operating system picks a free one. A fixed port would collide
            // with whatever else the player is running and would be guessable besides.
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new WalletBridgeException(
                    "Could not open a local port for the wallet connection", e);
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "MCAgents-wallet-bridge");
                    // The game must be able to exit with a handshake half-finished.
                    thread.setDaemon(true);
                    return thread;
                });
        server.setExecutor(scheduler);

        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/?t=" + token);
        LocalWalletBridge bridge = new LocalWalletBridge(server, scheduler, token, message, url);

        server.createContext("/", bridge::handlePage);
        server.createContext("/challenge", bridge::handleChallenge);
        server.createContext("/callback", bridge::handleCallback);
        server.createContext("/cancel", bridge::handleCancel);
        server.start();

        scheduler.schedule(() -> bridge.fail(
                        "The wallet connection timed out. Try again when the browser is ready."),
                limit.toMillis(), TimeUnit.MILLISECONDS);

        LOGGER.log(Level.INFO, "Wallet bridge listening on 127.0.0.1:{0}",
                server.getAddress().getPort());
        return bridge;
    }

    /** Starts a bridge with the default timeout. */
    public static LocalWalletBridge start(String accountId) {
        return start(accountId, DEFAULT_TIMEOUT);
    }

    @Override
    public URI url() {
        return url;
    }

    @Override
    public CompletableFuture<WalletBinding> binding() {
        return binding;
    }

    /** The message the player is asked to sign, shown on screen as well as in the browser. */
    public String message() {
        return message;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // A screen the player walked away from must not leave a future nobody will finish.
        binding.completeExceptionally(
                new WalletBridgeException("The wallet connection was closed before it finished"));
        server.stop(0);
        scheduler.shutdownNow();
        LOGGER.log(Level.INFO, "Wallet bridge stopped");
    }

    // ------------------------------------------------------------ handlers

    private void handlePage(HttpExchange exchange) throws IOException {
        if (!guard(exchange, "GET")) {
            return;
        }
        byte[] page = readPage();
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        // Nothing here should ever be reused, and least of all by another page.
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        send(exchange, 200, page);
    }

    /** Hands the page the message to sign. The page holds no copy until it asks. */
    private void handleChallenge(HttpExchange exchange) throws IOException {
        if (!guard(exchange, "GET")) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        sendJson(exchange, 200, body);
    }

    /**
     * Checks a signature and, if it holds, finishes the binding and stops the server.
     *
     * <p>The address the page claims is never taken at its word — it is compared against
     * the address recovered from the signature, and a mismatch is a failure rather than a
     * warning.
     */
    private void handleCallback(HttpExchange exchange) throws IOException {
        if (!guard(exchange, "POST")) {
            return;
        }
        JsonObject request;
        try {
            request = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            sendError(exchange, 400, "That reply was not something this bridge asked for.");
            return;
        }

        String address = string(request, "address");
        String signature = string(request, "signature");
        long chainId = chainId(string(request, "chainId"));

        if (!EvmSignatures.proves(message, signature, address)) {
            // Deliberately not saying which of the two was wrong: to anyone probing this
            // endpoint the distinction is the only useful information it could give.
            sendError(exchange, 400,
                    "That signature does not match the address it arrived with.");
            return;
        }

        ChainPlatform platform = ChainPlatform.fromEvmChainId(chainId)
                .orElse(ChainPlatform.ETHEREUM);
        WalletBinding verified = new WalletBinding(
                EvmSignatures.toChecksumAddress(address), platform, chainId, Instant.now());

        JsonObject body = new JsonObject();
        body.addProperty("address", verified.address());
        body.addProperty("platform", platform.displayName());
        sendJson(exchange, 200, body);

        binding.complete(verified);
        // Verified, answered, done. Stopping from another thread so the reply is flushed
        // rather than cut off by the server it was sent through.
        stopAfterResponse();
    }

    private void handleCancel(HttpExchange exchange) throws IOException {
        if (!guard(exchange, "POST")) {
            return;
        }
        send(exchange, 204, new byte[0]);
        fail("The wallet connection was cancelled in the browser.");
    }

    // ------------------------------------------------------------ plumbing

    /**
     * Rejects anything that is not this bridge's own page asking.
     *
     * <p>Three checks, each closing something different: the method, so a link cannot cause
     * a write; the token, so another program on the machine cannot drive the handshake; and
     * the {@code Host} header, so a hostile site cannot point one of its own domains at
     * {@code 127.0.0.1} and reach this from the browser.
     */
    private boolean guard(HttpExchange exchange, String method) throws IOException {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed.");
            return false;
        }
        if (!isLoopbackHost(exchange.getRequestHeaders().getFirst("Host"))) {
            sendError(exchange, 403, "Forbidden.");
            return false;
        }
        if (!tokenMatches(queryValue(exchange.getRequestURI().getRawQuery(), "t"))) {
            sendError(exchange, 403, "Forbidden.");
            return false;
        }
        return true;
    }

    private boolean isLoopbackHost(String host) {
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

    private void stopAfterResponse() {
        Thread closer = new Thread(this::close, "MCAgents-wallet-bridge-stop");
        closer.setDaemon(true);
        closer.start();
    }

    /** Ends the attempt with a reason, unless it has already finished. */
    private void fail(String reason) {
        if (binding.isDone()) {
            return;
        }
        binding.completeExceptionally(new WalletBridgeException(reason));
        stopAfterResponse();
    }

    private static byte[] readPage() throws IOException {
        try (InputStream stream = LocalWalletBridge.class.getResourceAsStream(PAGE_RESOURCE)) {
            if (stream == null) {
                throw new IOException("The wallet bridge page is missing from the mod jar");
            }
            return stream.readAllBytes();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            // Bounded: an unbounded read on a socket anyone local can open is a way to make
            // the game run out of memory.
            byte[] bytes = stream.readNBytes(MAX_BODY_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String string(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonPrimitive()
                ? object.get(member).getAsString()
                : "";
    }

    /** MetaMask reports the chain as hex ({@code 0x1}); some pages send a plain number. */
    private static long chainId(String value) {
        String text = value == null ? "" : value.trim();
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return Long.parseLong(text.substring(2), 16);
            }
            return text.isEmpty() ? 0 : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String queryValue(String rawQuery, String key) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(key)) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        send(exchange, status, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendError(HttpExchange exchange, int status, String reason)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", reason);
        sendJson(exchange, status, body);
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        // No CORS headers, on purpose: without them the browser refuses to let another
        // origin read anything this bridge says.
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    /**
     * The message the player signs.
     *
     * <p>Written to be read. Someone is about to approve this in a wallet, and a prompt they
     * cannot understand is one they should not approve — so it says which account it binds,
     * carries a nonce that makes it good exactly once, and states plainly that it authorises
     * nothing.
     */
    private static String challengeMessage(String accountId, String nonce) {
        return String.join("\n",
                "MCAgents wallet binding",
                "",
                "Account: " + accountId,
                "Nonce: " + nonce,
                "Issued: " + Instant.now(),
                "",
                "Signing proves you control this address.",
                "It authorises nothing, moves no funds, and never reaches a chain.");
    }
}
