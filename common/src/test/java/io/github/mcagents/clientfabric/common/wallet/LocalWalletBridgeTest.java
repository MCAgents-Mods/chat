package io.github.mcagents.clientfabric.common.wallet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import io.github.mcagents.clientfabric.api.wallet.WalletBinding;
import io.github.mcagents.clientfabric.api.wallet.WalletBridgeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * <p>These tests stand in for the browser: they fetch the challenge, sign it with a test
 * key, and post the result back. What they are really checking is everything around that —
 * that a wrong signature is refused, that a request without the token gets nowhere, and
 * above all that <strong>the listener is gone once the binding is made</strong>.
 */
class LocalWalletBridgeTest {

    private static final BigInteger PLAYER_KEY = new BigInteger(
            "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318", 16);

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

        String message = challenge();
        assertTrue(message.contains("Account: Personal"));
        assertTrue(message.contains("It authorises nothing"),
                "the player is about to approve this; it has to say what it does");

        HttpResponse<String> response = callback(address(), sign(message), "0x1");

        assertEquals(200, response.statusCode());
        WalletBinding binding = bridge.binding().orTimeout(10, TimeUnit.SECONDS).join();
        assertEquals(address(), binding.address());
        assertEquals(ChainPlatform.ETHEREUM, binding.platform());
        assertEquals(1, binding.chainId());
    }

    @Test
    void stopsListeningOnceTheBindingIsMade() {
        // The whole design rests on this: the mod is a web server for one handshake and not
        // a moment longer.
        bridge = LocalWalletBridge.start("Personal");
        URI url = bridge.url();

        callback(address(), sign(challenge()), "0x1");
        bridge.binding().orTimeout(10, TimeUnit.SECONDS).join();

        assertThrows(IOException.class, () -> get(url),
                "the port should be closed once the signature has verified");
    }

    @Test
    void filesTheAddressUnderTheChainTheBrowserWasOn() {
        bridge = LocalWalletBridge.start("Personal");

        callback(address(), sign(challenge()), "0x2105");

        WalletBinding binding = bridge.binding().orTimeout(10, TimeUnit.SECONDS).join();
        assertEquals(ChainPlatform.BASE, binding.platform());
        assertEquals(8453, binding.chainId());
    }

    @Test
    void fallsBackToEthereumForAChainItDoesNotKnow() {
        // Every EVM chain holds the same address, so an unknown chain id is a naming
        // problem rather than a reason to refuse a signature that verified.
        bridge = LocalWalletBridge.start("Personal");

        callback(address(), sign(challenge()), "0x4d2");

        WalletBinding binding = bridge.binding().orTimeout(10, TimeUnit.SECONDS).join();
        assertEquals(ChainPlatform.ETHEREUM, binding.platform());
        assertEquals(1234, binding.chainId());
    }

    @Test
    void refusesASignatureThatDoesNotMatchTheAddressItArrivedWith() {
        bridge = LocalWalletBridge.start("Personal");
        String signature = sign(challenge());

        HttpResponse<String> response = callback(
                "0x0000000000000000000000000000000000000001", signature, "0x1");

        assertEquals(400, response.statusCode());
        assertFalse(bridge.binding().isDone(), "a refused signature must not bind anything");
    }

    @Test
    void refusesASignatureOverSomeOtherMessage() {
        // A signature captured from an earlier attempt carries an earlier nonce, so it
        // cannot be replayed into this one.
        bridge = LocalWalletBridge.start("Personal");
        String stale = signMessage("MCAgents wallet binding\n\nAccount: Personal\nNonce: stale");

        assertEquals(400, callback(address(), stale, "0x1").statusCode());
        assertFalse(bridge.binding().isDone());
    }

    @Test
    void refusesEveryRequestWithoutTheToken() {
        bridge = LocalWalletBridge.start("Personal");
        int port = bridge.url().getPort();

        assertEquals(403, statusOf("http://127.0.0.1:" + port + "/"));
        assertEquals(403, statusOf("http://127.0.0.1:" + port + "/challenge"));
        assertEquals(403, statusOf("http://127.0.0.1:" + port + "/challenge?t=guessed"));
        assertFalse(bridge.binding().isDone(), "and none of that ended the attempt");
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
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
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
    void endsTheAttemptWhenTheBrowserCancels() {
        bridge = LocalWalletBridge.start("Personal");

        send(HttpRequest.newBuilder()
                .uri(URI.create(bridge.url().toString().replace("/?t=", "/cancel?t=")))
                .POST(HttpRequest.BodyPublishers.noBody()));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> bridge.binding().orTimeout(10, TimeUnit.SECONDS).join());
        assertTrue(failure.getCause() instanceof WalletBridgeException,
                "expected a WalletBridgeException, got " + failure.getCause());
    }

    @Test
    void givesUpWhenNobodyEverAnswers() {
        bridge = LocalWalletBridge.start("Personal", Duration.ofMillis(200));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> bridge.binding().orTimeout(10, TimeUnit.SECONDS).join());
        assertTrue(failure.getCause() instanceof WalletBridgeException,
                "expected a WalletBridgeException, got " + failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("timed out"));
    }

    @Test
    void closingTwiceIsHarmlessAndAlwaysFinishesTheFuture() {
        bridge = LocalWalletBridge.start("Personal");

        bridge.close();
        bridge.close();

        assertTrue(bridge.binding().isCompletedExceptionally(),
                "a screen waiting on this future must never wait forever");
    }

    @Test
    void listensOnLoopbackAndNowhereElse() {
        bridge = LocalWalletBridge.start("Personal");

        assertEquals("127.0.0.1", bridge.url().getHost());
        assertTrue(bridge.url().getPort() > 0, "the operating system picks the port");
    }

    @Test
    void givesEveryAttemptItsOwnTokenAndNonce() {
        bridge = LocalWalletBridge.start("Personal");
        LocalWalletBridge second = LocalWalletBridge.start("Personal");
        try {
            assertNotEquals(bridge.url().getQuery(), second.url().getQuery());
            assertNotEquals(bridge.message(), second.message());
        } finally {
            second.close();
        }
    }

    // -------------------------------------------------------------- driving

    private String challenge() {
        return JsonParser.parseString(send(HttpRequest.newBuilder()
                        .uri(URI.create(bridge.url().toString().replace("/?t=", "/challenge?t=")))
                        .GET()).body())
                .getAsJsonObject().get("message").getAsString();
    }

    private HttpResponse<String> callback(String address, String signature, String chainId) {
        JsonObject body = new JsonObject();
        body.addProperty("address", address);
        body.addProperty("signature", signature);
        body.addProperty("chainId", chainId);

        return send(HttpRequest.newBuilder()
                .uri(URI.create(bridge.url().toString().replace("/?t=", "/callback?t=")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
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

    private void get(URI uri) throws IOException, InterruptedException {
        client.send(HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String address() {
        return EvmSignatures.toAddress(Secp256k1.multiply(Secp256k1.G, PLAYER_KEY));
    }

    private String sign(String message) {
        return signMessage(message);
    }

    /** Signs the way MetaMask would, so the bridge has something real to check. */
    private static String signMessage(String message) {
        byte[] hash = EvmSignatures.hashPersonalMessage(message);
        BigInteger n = Secp256k1.N;
        BigInteger e = new BigInteger(1, hash);
        BigInteger k = new BigInteger(1, Keccak256.digest(
                (PLAYER_KEY.toString(16) + ":" + message)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE);

        Secp256k1.Point point = Secp256k1.multiply(Secp256k1.G, k);
        BigInteger r = point.x().mod(n);
        BigInteger s = k.modInverse(n).multiply(e.add(r.multiply(PLAYER_KEY))).mod(n);
        int recoveryId = (point.y().testBit(0) ? 1 : 0) | (point.x().compareTo(n) >= 0 ? 2 : 0);
        if (s.compareTo(n.shiftRight(1)) > 0) {
            s = n.subtract(s);
            recoveryId ^= 1;
        }
        return "0x" + pad(r) + pad(s) + String.format("%02x", recoveryId + 27);
    }

    private static String pad(BigInteger value) {
        String hex = value.toString(16);
        return "0".repeat(64 - hex.length()) + hex;
    }
}
