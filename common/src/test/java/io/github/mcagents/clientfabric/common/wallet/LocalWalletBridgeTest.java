package io.github.mcagents.clientfabric.common.wallet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import io.github.mcagents.clientfabric.api.wallet.WalletBinding;
import io.github.mcagents.clientfabric.api.wallet.WalletBridgeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loopback bridge, driven the way the browser page drives it.
 *
 * <p>These tests stand in for the browser: they ask for a challenge naming an address, sign
 * it with a test key, and post the result back. What they are really checking is everything
 * around that — that a wrong signature is refused, that a request without the token gets
 * nowhere, that one trip carries <strong>every</strong> address the browser holds rather
 * than the first of them, and above all that <strong>the listener is gone once the attempt
 * settles</strong>.
 */
class LocalWalletBridgeTest {

    private static final TestSigner PLAYER = TestSigner.PLAYER;
    private static final TestSigner SECOND = TestSigner.SECOND;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private LocalWalletBridge bridge;

    @AfterEach
    void stop() {
        if (bridge != null) {
            bridge.close();
        }
    }

    @Test
    void bindsAnAddressTheBrowserProves() {
        bridge = LocalWalletBridge.start("Personal");

        Challenge challenge = challengeFor(PLAYER.address());
        assertTrue(challenge.message().contains("Account: Personal"));
        assertTrue(challenge.message().contains("Address: " + PLAYER.address()),
                "a challenge that does not name its address could be answered with any other");
        assertTrue(challenge.message().contains("It authorises nothing"),
                "the player is about to approve this; it has to say what it does");

        assertEquals(200, prove(challenge, PLAYER, "0x1").statusCode());
        done();

        List<WalletBinding> bound = settled();
        assertEquals(1, bound.size());
        assertEquals(PLAYER.address(), bound.getFirst().address());
        assertEquals(ChainPlatform.ETHEREUM, bound.getFirst().platform());
        assertEquals(1, bound.getFirst().chainId());
    }

    @Test
    void bindsEveryAddressTheBrowserOffersInOneTrip() {
        // The point of the whole change. A browser holds as many accounts as the player has
        // connected, and the bridge used to stop on the first signature that verified —
        // which made a player with five wallets take the same journey five times.
        bridge = LocalWalletBridge.start("Personal");

        prove(challengeFor(PLAYER.address()), PLAYER, "0x1");
        prove(challengeFor(SECOND.address()), SECOND, "0x1");
        done();

        List<WalletBinding> bound = settled();
        assertEquals(List.of(PLAYER.address(), SECOND.address()),
                bound.stream().map(WalletBinding::address).toList(),
                "both addresses, in the order they were signed");
    }

    @Test
    void keepsListeningAfterAnAddressVerifies() {
        // The page is working through a list; stopping at the first success is exactly the
        // behaviour being replaced.
        bridge = LocalWalletBridge.start("Personal");

        prove(challengeFor(PLAYER.address()), PLAYER, "0x1");

        assertFalse(bridge.bindings().isDone(),
                "one address is not a finished handshake — only the page knows how many there are");
        assertEquals(200, prove(challengeFor(SECOND.address()), SECOND, "0x1").statusCode(),
                "and the bridge is still there to take the next one");
    }

    @Test
    void reportsTheSameAddressOnceHoweverOftenItIsSigned() {
        // A page that retries an address it already got through must not turn one wallet
        // into two.
        bridge = LocalWalletBridge.start("Personal");

        prove(challengeFor(PLAYER.address()), PLAYER, "0x1");
        prove(challengeFor(PLAYER.address()), PLAYER, "0x1");
        done();

        assertEquals(1, settled().size());
    }

    @Test
    void stopsListeningOnceTheAttemptSettles() {
        // The whole design rests on this: the mod is a web server for one handshake and not
        // a moment longer.
        //
        // Checked the instant the future resolves, with no grace period, because the
        // ordering is the point. The bridge used to complete the binding and *then* start
        // closing, which left a window where it reported the handshake finished while the
        // socket was still accepting connections — a race that passed on one machine and
        // failed on another.
        bridge = LocalWalletBridge.start("Personal");
        int port = bridge.url().getPort();

        prove(challengeFor(PLAYER.address()), PLAYER, "0x1");
        done();
        settled();

        assertThrows(IOException.class, () -> connectTo(port),
                "the port must already be closed when the bindings resolve");
    }

    /**
     * Opens a bare TCP connection and nothing more.
     *
     * <p>A plain socket rather than the HTTP client: the client pools connections, so a
     * request after the server has gone can be answered from a socket it opened earlier,
     * and the test would be measuring the pool instead of the port.
     */
    private static void connectTo(int port) throws IOException {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
        }
    }

    @Test
    void filesTheAddressUnderTheChainTheBrowserWasOn() {
        bridge = LocalWalletBridge.start("Personal");

        prove(challengeFor(PLAYER.address()), PLAYER, "0x2105");
        done();

        WalletBinding binding = settled().getFirst();
        assertEquals(ChainPlatform.BASE, binding.platform());
        assertEquals(8453, binding.chainId());
    }

    @Test
    void fallsBackToEthereumForAChainItDoesNotKnow() {
        // Every EVM chain holds the same address, so an unknown chain id is a naming
        // problem rather than a reason to refuse a signature that verified.
        bridge = LocalWalletBridge.start("Personal");

        prove(challengeFor(PLAYER.address()), PLAYER, "0x4d2");
        done();

        WalletBinding binding = settled().getFirst();
        assertEquals(ChainPlatform.ETHEREUM, binding.platform());
        assertEquals(1234, binding.chainId());
    }

    @Test
    void refusesAChallengeThatNamesNoAddress() {
        bridge = LocalWalletBridge.start("Personal");

        assertEquals(400, statusOf(bridge.url().toString().replace("/?t=", "/challenge?t=")));
    }

    @Test
    void refusesASignatureThatDoesNotMatchTheAddressItArrivedWith() {
        bridge = LocalWalletBridge.start("Personal");
        Challenge challenge = challengeFor(PLAYER.address());

        HttpResponse<String> response = callback(challenge.nonce(),
                "0x0000000000000000000000000000000000000001",
                PLAYER.sign(challenge.message()), "0x1");

        assertEquals(400, response.statusCode());
        done();
        assertTrue(failure().getMessage().contains("Nothing was signed"),
                "a refused signature must not bind anything");
    }

    @Test
    void refusesASignatureOfferedTwiceAgainstOneChallenge() {
        // The nonce is spent on the first redemption, so a signature captured in flight
        // cannot be replayed — including against the very challenge it answered.
        bridge = LocalWalletBridge.start("Personal");
        Challenge challenge = challengeFor(PLAYER.address());
        String signature = PLAYER.sign(challenge.message());

        assertEquals(200, callback(challenge.nonce(), PLAYER.address(), signature, "0x1")
                .statusCode());
        assertEquals(400, callback(challenge.nonce(), PLAYER.address(), signature, "0x1")
                .statusCode());
    }

    @Test
    void refusesASignatureOverSomeOtherMessage() {
        // A signature captured from an earlier attempt carries an earlier nonce, so it
        // cannot be replayed into this one.
        bridge = LocalWalletBridge.start("Personal");
        Challenge challenge = challengeFor(PLAYER.address());
        String stale = PLAYER.sign(
                "MCAgents wallet binding\n\nAccount: Personal\nNonce: stale");

        assertEquals(400, callback(challenge.nonce(), PLAYER.address(), stale, "0x1")
                .statusCode());
    }

    @Test
    void refusesEveryRequestWithoutTheToken() {
        bridge = LocalWalletBridge.start("Personal");
        int port = bridge.url().getPort();

        assertEquals(403, statusOf("http://127.0.0.1:" + port + "/"));
        assertEquals(403, statusOf("http://127.0.0.1:" + port + "/challenge"));
        assertEquals(403, statusOf("http://127.0.0.1:" + port + "/challenge?t=guessed"));
        assertFalse(bridge.bindings().isDone(), "and none of that ended the attempt");
    }

    @Test
    void refusesARequestThatArrivedThroughSomeOtherHostname() {
        // A site that points a domain of its own at 127.0.0.1 reaches the bridge with that
        // domain in the Host header. This is what stops it.
        //
        // Written straight onto a socket because HttpClient forbids setting Host — which is
        // itself the reason the check is worth having: the request this refuses is one no
        // well-behaved client would send, so only a hostile one arrives this way.
        bridge = LocalWalletBridge.start("Personal");
        String path = "/?t=" + bridge.url().getQuery().substring("t=".length());

        assertTrue(rawGet("wallet-stealer.example", path).startsWith("HTTP/1.1 403"),
                "a request carrying someone else's hostname must be refused");
        assertTrue(rawGet("127.0.0.1:" + bridge.url().getPort(), path).startsWith("HTTP/1.1 200"),
                "and the same request from the real origin must still work");
    }

    /** Sends a request by hand, so the {@code Host} header can be anything at all. */
    private String rawGet(String hostHeader, String path) {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", bridge.url().getPort())) {
            socket.setSoTimeout(5_000);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("The bridge did not answer a hand-written request", e);
        }
    }

    @Test
    void refusesAWriteDressedUpAsALink() {
        bridge = LocalWalletBridge.start("Personal");

        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(bridge.url().toString().replace("/?t=", "/callback?t=")))
                .GET());

        assertEquals(405, response.statusCode());
    }

    @Test
    void servesAPageThatNeverAsksForASecret() {
        bridge = LocalWalletBridge.start("Personal");

        String page = send(HttpRequest.newBuilder().uri(bridge.url()).GET()).body();

        assertTrue(page.contains("never ask for your Secret Recovery Phrase"));
        assertFalse(page.contains("type=\"password\""),
                "there is nothing on this page worth a password field");
        assertFalse(page.contains("http://") && page.contains("cdn"),
                "the page has to render with no internet at all");
    }

    @Test
    void keepsWhatWasProvedWhenTheBrowserCancelsPartWayThrough() {
        // Two of five signed and then the tab closes. Those two were proved, and throwing
        // them away would only mean asking the player to sign them again.
        bridge = LocalWalletBridge.start("Personal");

        prove(challengeFor(PLAYER.address()), PLAYER, "0x1");
        cancel();

        assertEquals(List.of(PLAYER.address()),
                settled().stream().map(WalletBinding::address).toList());
    }

    @Test
    void endsTheAttemptWhenTheBrowserCancelsWithNothingSigned() {
        bridge = LocalWalletBridge.start("Personal");
        int port = bridge.url().getPort();

        cancel();

        assertTrue(failure().getMessage().contains("cancelled"));
        // The same ordering as a success: whatever ends the attempt, the port is shut
        // before anyone waiting on the future is told about it.
        assertThrows(IOException.class, () -> connectTo(port));
    }

    @Test
    void givesUpWhenNobodyEverAnswers() {
        bridge = LocalWalletBridge.start("Personal", Duration.ofMillis(200));
        int port = bridge.url().getPort();

        assertTrue(failure().getMessage().contains("timed out"));
        assertThrows(IOException.class, () -> connectTo(port));
    }

    @Test
    void closingTwiceIsHarmlessAndAlwaysFinishesTheFuture() {
        bridge = LocalWalletBridge.start("Personal");

        bridge.close();
        bridge.close();

        assertTrue(bridge.bindings().isCompletedExceptionally(),
                "a screen waiting on this future must never wait forever");
    }

    @Test
    void listensOnLoopbackAndNowhereElse() {
        bridge = LocalWalletBridge.start("Personal");

        assertEquals("127.0.0.1", bridge.url().getHost());
        assertTrue(bridge.url().getPort() > 0, "the operating system picks the port");
    }

    @Test
    void givesEveryAttemptItsOwnTokenAndEveryAddressItsOwnNonce() {
        bridge = LocalWalletBridge.start("Personal");
        LocalWalletBridge second = LocalWalletBridge.start("Personal");
        try {
            assertNotEquals(bridge.url().getQuery(), second.url().getQuery());
            assertNotEquals(challengeFor(PLAYER.address()).nonce(),
                    challengeFor(SECOND.address()).nonce(),
                    "one nonce for two addresses would let one signature stand for both");
        } finally {
            second.close();
        }
    }

    // -------------------------------------------------------------- driving

    private record Challenge(String nonce, String message) {
    }

    private Challenge challengeFor(String address) {
        JsonObject body = JsonParser.parseString(send(HttpRequest.newBuilder()
                        .uri(URI.create(bridge.url().toString().replace("/?t=", "/challenge?t=")
                                + "&address=" + URLEncoder.encode(address, StandardCharsets.UTF_8)))
                        .GET()).body())
                .getAsJsonObject();
        return new Challenge(body.get("nonce").getAsString(), body.get("message").getAsString());
    }

    private HttpResponse<String> prove(Challenge challenge, TestSigner signer, String chainId) {
        return callback(challenge.nonce(), signer.address(),
                signer.sign(challenge.message()), chainId);
    }

    private HttpResponse<String> callback(
            String nonce, String address, String signature, String chainId) {
        JsonObject body = new JsonObject();
        body.addProperty("nonce", nonce);
        body.addProperty("address", address);
        body.addProperty("signature", signature);
        body.addProperty("chainId", chainId);

        return send(HttpRequest.newBuilder()
                .uri(URI.create(bridge.url().toString().replace("/?t=", "/callback?t=")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    private void done() {
        post("/done?t=");
    }

    private void cancel() {
        post("/cancel?t=");
    }

    private void post(String path) {
        send(HttpRequest.newBuilder()
                .uri(URI.create(bridge.url().toString().replace("/?t=", path)))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private List<WalletBinding> settled() {
        return bridge.bindings().orTimeout(10, TimeUnit.SECONDS).join();
    }

    private Throwable failure() {
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> bridge.bindings().orTimeout(10, TimeUnit.SECONDS).join());
        assertTrue(thrown.getCause() instanceof WalletBridgeException,
                "expected a WalletBridgeException, got " + thrown.getCause());
        return thrown.getCause();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return client.send(request.timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("The bridge did not answer", e);
        }
    }

    private int statusOf(String uri) {
        return send(HttpRequest.newBuilder().uri(URI.create(uri)).GET()).statusCode();
    }

}
