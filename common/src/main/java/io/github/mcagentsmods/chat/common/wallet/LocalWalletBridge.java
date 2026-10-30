package io.github.mcagentsmods.chat.common.wallet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.mcagentsmods.chat.api.wallet.WalletBinding;
import io.github.mcagentsmods.chat.api.wallet.WalletBridge;
import io.github.mcagentsmods.chat.api.wallet.WalletBridgeException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The loopback web server that carries one MetaMask handshake, and then stops.
 *
 * <p>One handshake, and as many addresses as the browser holds: the page signs each account
 * MetaMask exposes in turn, this class checks each signature, and the whole set comes back
 * together. Making the player repeat the browser trip once per address was asking them to do
 * by hand what a loop does.
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
 *   <li><strong>Stops when the browser says it is finished.</strong> The listener is shut
 *       the moment the page reports it has run out of addresses, and also on cancel, on
 *       timeout, and on close. There is no state in which it outlives the screen that
 *       opened it, and the port is closed <em>before</em> {@link #bindings()} completes
 *       rather than shortly after — see {@code stop}.</li>
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

    private final HttpServer server;
    private final ScheduledExecutorService scheduler;
    private final String token;
    private final String accountId;
    private final URI url;
    private final WalletChallenges challenges = new WalletChallenges();
    private final CompletableFuture<List<WalletBinding>> bindings = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * What has been proved so far, newest write last and keyed by address.
     *
     * <p>Keyed rather than appended so a page that retries one address does not report it
     * twice, and ordered so the wallets land in the order the player signed them.
     */
    private final Map<String, WalletBinding> proved = new LinkedHashMap<>();

    private LocalWalletBridge(HttpServer server, ScheduledExecutorService scheduler,
                              String token, String accountId, URI url) {
        this.server = server;
        this.scheduler = scheduler;
        this.token = token;
        this.accountId = accountId;
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

        String token = WalletChallenges.randomHex(TOKEN_BYTES);

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
        LocalWalletBridge bridge = new LocalWalletBridge(server, scheduler, token, accountId, url);

        server.createContext("/", bridge::handlePage);
        server.createContext("/challenge", bridge::handleChallenge);
        server.createContext("/callback", bridge::handleCallback);
        server.createContext("/done", bridge::handleDone);
        server.createContext("/cancel", bridge::handleCancel);
        server.start();

        // Off the scheduler thread, because stopping shuts the scheduler down and doing
        // that from one of its own tasks means interrupting itself mid-shutdown.
        scheduler.schedule(() -> bridge.stopLater(
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
    public CompletableFuture<List<WalletBinding>> bindings() {
        return bindings;
    }

    @Override
    public void close() {
        // A screen the player walked away from must not leave a future nobody will finish.
        stop("The wallet connection was closed before it finished");
    }

    /**
     * Stops the listener, then settles the binding. That order is the contract.
     *
     * <p>Anyone waiting on {@link #binding()} is waiting to find out whether the bridge is
     * finished with, so the port has to be shut <em>before</em> the future tells them it
     * is. Completing first and closing afterwards leaves a window in which the mod says the
     * handshake is over while the socket is still accepting connections — briefly true of
     * this class, and caught by the test that connects to the port the moment the future
     * resolves.
     *
     * <p>Idempotent: the first caller wins and every later one returns at once, so a
     * success cannot be overwritten by the screen closing a moment later.
     *
     * <p>Whatever was already proved settles the future, whichever way the attempt ended. A
     * player who signed two of five addresses and then closed the tab proved two addresses,
     * and throwing them away would only mean asking for them again. The failure is reserved
     * for an attempt that proved nothing at all.
     *
     * @param reason why it ended, used only when nothing was proved
     */
    private void stop(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        List<WalletBinding> verified = snapshot();
        if (verified.isEmpty()) {
            bindings.completeExceptionally(new WalletBridgeException(reason));
        } else {
            bindings.complete(verified);
        }
        scheduler.shutdownNow();
        LOGGER.log(Level.INFO, "Wallet bridge stopped with {0} address(es) proved",
                verified.size());
    }

    private List<WalletBinding> snapshot() {
        synchronized (proved) {
            return List.copyOf(proved.values());
        }
    }

    /**
     * Runs {@link #stop} off the thread that is serving a request.
     *
     * <p>Two reasons it cannot happen inline. The reply has to be flushed rather than cut
     * off by the server it is travelling through; and {@code stop} shuts down the very
     * executor that runs the handlers, which from a handler means interrupting itself.
     */
    private void stopLater(String reason) {
        Thread closer = new Thread(() -> stop(reason), "MCAgents-wallet-bridge-stop");
        closer.setDaemon(true);
        closer.start();
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
        LoopbackHttp.send(exchange, 200, page);
    }

    /**
     * Hands the page a message to sign for one address. The page holds no copy until it asks.
     *
     * <p>The address is required. A challenge that did not name the address it proves would
     * let one signature stand for any address the caller went on to claim — which is exactly
     * the hole that opens once a single trip carries more than one of them.
     */
    private void handleChallenge(HttpExchange exchange) throws IOException {
        if (!guard(exchange, "GET")) {
            return;
        }
        String address = LoopbackHttp.decode(
                LoopbackHttp.queryValue(exchange.getRequestURI().getRawQuery(), "address"));
        if (address == null || address.isBlank()) {
            LoopbackHttp.sendError(exchange, 400, "A challenge has to name the address it proves.");
            return;
        }

        WalletChallenges.Challenge challenge = challenges.issue(accountId, address);
        JsonObject body = new JsonObject();
        body.addProperty("nonce", challenge.nonce());
        body.addProperty("message", challenge.message());
        LoopbackHttp.sendJson(exchange, 200, body);
    }

    /**
     * Checks one signature and, if it holds, adds the address to what has been proved.
     *
     * <p>The address the page claims is never taken at its word — it is compared against
     * the address recovered from the signature, and a mismatch is a failure rather than a
     * warning.
     *
     * <p><strong>This does not stop the bridge.</strong> The page is working through a list
     * of accounts and says when it has reached the end; stopping on the first success is
     * what limited the whole feature to one wallet.
     */
    private void handleCallback(HttpExchange exchange) throws IOException {
        if (!guard(exchange, "POST")) {
            return;
        }
        JsonObject request;
        try {
            request = JsonParser.parseString(LoopbackHttp.readBody(exchange)).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            LoopbackHttp.sendError(exchange, 400, "That reply was not something this bridge asked for.");
            return;
        }

        Optional<WalletBinding> verified = challenges.redeem(
                LoopbackHttp.string(request, "nonce"),
                accountId,
                LoopbackHttp.string(request, "address"),
                LoopbackHttp.string(request, "signature"),
                LoopbackHttp.string(request, "chainId"));

        if (verified.isEmpty()) {
            // Deliberately not saying which of the three was wrong: to anyone probing this
            // endpoint the distinction is the only useful information it could give.
            LoopbackHttp.sendError(exchange, 400,
                    "That signature does not match the address it arrived with.");
            return;
        }

        WalletBinding binding = verified.get();
        int count;
        synchronized (proved) {
            proved.put(binding.address(), binding);
            count = proved.size();
        }

        JsonObject body = new JsonObject();
        body.addProperty("address", binding.address());
        body.addProperty("platform", binding.platform().displayName());
        body.addProperty("proved", count);
        LoopbackHttp.sendJson(exchange, 200, body);
    }

    /**
     * The page has run out of addresses. Hand over what was proved and shut the listener.
     *
     * <p>The browser decides when the set is complete because only the browser knows how
     * many accounts MetaMask exposed. The bridge stops either way — on the timeout, on
     * cancel, or when the screen closes — so a page that never says so costs a wait rather
     * than a listener left running.
     */
    private void handleDone(HttpExchange exchange) throws IOException {
        if (!guard(exchange, "POST")) {
            return;
        }
        LoopbackHttp.send(exchange, 204, new byte[0]);

        // Proved, answered, done. The listener closes before the bindings are handed over —
        // see stop() for why that order matters.
        stopLater("Nothing was signed, so nothing was bound.");
    }

    private void handleCancel(HttpExchange exchange) throws IOException {
        if (!guard(exchange, "POST")) {
            return;
        }
        LoopbackHttp.send(exchange, 204, new byte[0]);
        stopLater("The wallet connection was cancelled in the browser.");
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
            LoopbackHttp.sendError(exchange, 405, "Method not allowed.");
            return false;
        }
        if (!isLoopbackHost(exchange.getRequestHeaders().getFirst("Host"))) {
            LoopbackHttp.sendError(exchange, 403, "Forbidden.");
            return false;
        }
        if (!tokenMatches(LoopbackHttp.queryValue(exchange.getRequestURI().getRawQuery(), "t"))) {
            LoopbackHttp.sendError(exchange, 403, "Forbidden.");
            return false;
        }
        return true;
    }

    /**
     * Whether {@code host} is this listener's own loopback address and port.
     *
     * <p>The port is checked as well as the name. The listener's port is random and never the
     * scheme default, so a browser always sends it; a {@code Host} without it, or with a port
     * or suffix of anything else, was not addressed to this bridge.
     */
    private boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        String port = ":" + url.getPort();
        if (!name.endsWith(port)) {
            return false;
        }
        name = name.substring(0, name.length() - port.length());
        return name.equals("127.0.0.1") || name.equals("localhost") || name.equals("[::1]");
    }

    /** Constant-time, so the token cannot be recovered a byte at a time. */
    private boolean tokenMatches(String candidate) {
        return candidate != null && MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readPage() throws IOException {
        try (InputStream stream = LocalWalletBridge.class.getResourceAsStream(PAGE_RESOURCE)) {
            if (stream == null) {
                throw new IOException("The wallet bridge page is missing from the mod jar");
            }
            return stream.readAllBytes();
        }
    }
}
