package io.github.mcagents.clientfabric.website;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcagents.clientfabric.api.storage.LocalDatabase;
import io.github.mcagents.clientfabric.api.wallet.ChainPlatform;
import io.github.mcagents.clientfabric.api.wallet.Wallet;
import io.github.mcagents.clientfabric.common.agent.StubTransport;
import io.github.mcagents.clientfabric.common.service.CentralService;
import io.github.mcagents.clientfabric.common.storage.sqlite.SqliteDatabase;
import io.github.mcagents.clientfabric.common.wallet.TestSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's wallet routes.
 *
 * <p>Two things are being checked. That the browser can do the whole MetaMask handshake
 * <strong>from the console page itself</strong> — it is already a page in the player's
 * browser, so there is no reason for a second server to exist for it — and that every write
 * names an account which is then checked rather than trusted.
 */
class WalletRoutesTest {

    private static final TestSigner PLAYER = TestSigner.PLAYER;

    private static final String MINE = "Steve";
    private static final String THEIRS = "Alex";

    @TempDir
    Path gameDirectory;

    private LocalDatabase database;
    private LocalWebConsole console;

    @BeforeEach
    void open() {
        database = await(SqliteDatabase.open(gameDirectory.resolve("MCAgents/data.db")));
        console = LocalWebConsole.start(database,
                (sessionId, prompt) -> CompletableFuture.completedFuture("answer"),
                new CentralService(new StubTransport().reply(404, "{}"), "https://example.invalid"));
    }

    @AfterEach
    void close() {
        console.close();
        database.close();
    }

    @Test
    void groupsWalletsUnderTheAccountsThatOwnThem() {
        // The page draws one panel per account, so the grouping is done here. A flat list
        // would only mean the page grouping it again, its own way.
        await(database.wallets().createWallet(MINE, "Main"));
        await(database.wallets().createWallet(MINE, "Trading"));
        await(database.wallets().createWallet(THEIRS, "Theirs"));

        JsonObject body = json("/api/wallets");

        assertEquals(2, body.get("accountCount").getAsInt());
        assertEquals(3, body.get("total").getAsInt());

        JsonArray accounts = body.getAsJsonArray("accounts");
        JsonObject theirs = accounts.get(0).getAsJsonObject();
        assertEquals(THEIRS, theirs.get("id").getAsString(), "accounts come back in name order");
        assertEquals(1, theirs.get("total").getAsInt());
        assertEquals(2, accounts.get(1).getAsJsonObject().getAsJsonArray("wallets").size());
    }

    @Test
    void hasNoAccountUntilAWalletNamesOne() {
        // Accounts have no table: one exists exactly as long as a wallet names it, which is
        // what stops a player collecting empty accounts they then have to tidy up.
        assertEquals(0, json("/api/wallets").get("accountCount").getAsInt());

        assertEquals(201, post("/api/wallets", "{\"accountId\":\"" + MINE + "\"}").statusCode());

        assertEquals(1, json("/api/wallets").get("accountCount").getAsInt());
    }

    @Test
    void refusesAWalletWithNoAccountToBelongTo() {
        assertEquals(400, post("/api/wallets", "{\"accountId\":\"  \"}").statusCode());
        assertEquals(0, json("/api/wallets").get("accountCount").getAsInt());
    }

    @Test
    void provesAnAddressWithoutAnySecondServer() {
        // The whole browser half of the handshake, over the console's own routes.
        JsonObject challenge = json("/api/wallets/challenge?accountId=" + encode(MINE)
                + "&address=" + encode(address()));
        assertTrue(challenge.get("message").getAsString().contains("Account: " + MINE));
        assertTrue(challenge.get("message").getAsString().contains("Address: " + address()));

        JsonObject result = json(post("/api/wallets/verify", verifyBody(
                MINE, challenge.get("nonce").getAsString(), address(), sign(challenge), "0x1")));

        assertEquals(address(), result.get("address").getAsString());
        assertEquals("Ethereum", result.get("platform").getAsString());

        List<Wallet> stored = await(database.wallets().findWalletsByAccount(MINE));
        assertEquals(1, stored.size());
        assertTrue(stored.getFirst().hasVerifiedAddress());
    }

    @Test
    void refusesASignatureThatDoesNotMatchTheAddressItArrivedWith() {
        JsonObject challenge = json("/api/wallets/challenge?accountId=" + encode(MINE)
                + "&address=" + encode(address()));

        HttpResponse<String> response = post("/api/wallets/verify", verifyBody(MINE,
                challenge.get("nonce").getAsString(),
                "0x0000000000000000000000000000000000000001", sign(challenge), "0x1"));

        assertEquals(400, response.statusCode());
        assertTrue(await(database.wallets().findWalletsByAccount(MINE)).isEmpty());
    }

    @Test
    void spendsANonceOnTheFirstAnswerToIt() {
        JsonObject challenge = json("/api/wallets/challenge?accountId=" + encode(MINE)
                + "&address=" + encode(address()));
        String body = verifyBody(MINE, challenge.get("nonce").getAsString(),
                address(), sign(challenge), "0x1");

        assertEquals(200, post("/api/wallets/verify", body).statusCode());
        assertEquals(400, post("/api/wallets/verify", body).statusCode(),
                "a signature captured in flight must not be worth a second wallet");
    }

    @Test
    void refusesAChallengeThatNamesNoAddressOrNoAccount() {
        assertEquals(400, get("/api/wallets/challenge?accountId=" + encode(MINE)).statusCode());
        assertEquals(400, get("/api/wallets/challenge?address=" + encode(address())).statusCode());
    }

    @Test
    void renamesAndDeletesAWalletInTheAccountThatNamesIt() {
        Wallet mine = await(database.wallets().createWallet(MINE, "Main"));

        assertEquals(200, put("/api/wallets/" + mine.id(),
                "{\"accountId\":\"" + MINE + "\",\"label\":\"Renamed\"}").statusCode());
        assertEquals("Renamed",
                await(database.wallets().findWalletById(mine.id())).orElseThrow().label());

        assertEquals(200, delete("/api/wallets/" + mine.id() + "?accountId=" + encode(MINE))
                .statusCode());
        assertTrue(await(database.wallets().findWalletById(mine.id())).isEmpty());
    }

    @Test
    void changesNothingWhenTheAccountDoesNotOwnTheWallet() {
        // The account boundary, over the wire. A request naming one account and a wallet in
        // another is answered as though there were no such wallet — which, for that caller,
        // there is not.
        Wallet theirs = await(database.wallets().createWallet(THEIRS, "Theirs"));

        assertEquals(404, put("/api/wallets/" + theirs.id(),
                "{\"accountId\":\"" + MINE + "\",\"label\":\"Mine now\"}").statusCode());
        assertEquals(404, delete("/api/wallets/" + theirs.id() + "?accountId=" + encode(MINE))
                .statusCode());
        assertEquals(400, post("/api/wallets/" + theirs.id() + "/addresses",
                "{\"accountId\":\"" + MINE + "\",\"platform\":\"ETHEREUM\","
                        + "\"address\":\"" + address() + "\"}").statusCode());

        Wallet after = await(database.wallets().findWalletById(theirs.id())).orElseThrow();
        assertEquals("Theirs", after.label());
        assertTrue(after.addresses().isEmpty());
    }

    @Test
    void recordsATypedAddressAsUnproved() {
        // A typed address is a useful record and it is not evidence. Letting the two look
        // alike would make the tick beside a proved one mean nothing.
        Wallet mine = await(database.wallets().createWallet(MINE, "Main"));

        JsonObject body = json(post("/api/wallets/" + mine.id() + "/addresses",
                "{\"accountId\":\"" + MINE + "\",\"platform\":\"BITCOIN\","
                        + "\"address\":\"bc1qexampleaddress\"}"));

        JsonObject stored = body.getAsJsonArray("addresses").get(0).getAsJsonObject();
        assertFalse(stored.get("verified").getAsBoolean());
        assertEquals("Bitcoin", stored.get("platformName").getAsString());
        assertEquals("bc1qexampleaddress", stored.get("address").getAsString());
    }

    @Test
    void removesOneChainsAddressFromAWalletInTheAccount() {
        Wallet mine = await(database.wallets().createWallet(MINE, "Main"));
        await(database.wallets().addOrUpdateAddress(mine.id(), ChainPlatform.SOLANA, "So1ana", false));

        assertEquals(200, delete("/api/wallets/" + mine.id() + "/addresses/SOLANA?accountId="
                + encode(MINE)).statusCode());

        assertTrue(await(database.wallets().findWalletById(mine.id()))
                .orElseThrow().addresses().isEmpty());
    }

    @Test
    void keepsEveryWalletRouteBehindTheToken() {
        assertEquals(403, statusOf("http://127.0.0.1:" + console.port() + "/api/wallets"));
        assertEquals(403, statusOf("http://127.0.0.1:" + console.port()
                + "/api/wallets/challenge?accountId=" + encode(MINE)));
    }

    // ------------------------------------------------------------- plumbing

    private static String address() {
        return PLAYER.address();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String verifyBody(String accountId, String nonce, String address,
                                     String signature, String chainId) {
        JsonObject body = new JsonObject();
        body.addProperty("accountId", accountId);
        body.addProperty("nonce", nonce);
        body.addProperty("address", address);
        body.addProperty("signature", signature);
        body.addProperty("chainId", chainId);
        return body.toString();
    }

    private static String sign(JsonObject challenge) {
        return PLAYER.sign(challenge.get("message").getAsString());
    }

    private String base() {
        return "http://127.0.0.1:" + console.port();
    }

    private String token() {
        return console.url().getQuery().replaceAll(".*\\bt=([0-9a-f]+).*", "$1");
    }

    private String url(String path) {
        return base() + path + (path.contains("?") ? "&" : "?") + "t=" + token();
    }

    private HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder().uri(URI.create(url(path))).GET());
    }

    private HttpResponse<String> post(String path, String body) {
        return send(HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> put(String path, String body) {
        return send(HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> delete(String path) {
        return send(HttpRequest.newBuilder().uri(URI.create(url(path))).DELETE());
    }

    private JsonObject json(String path) {
        return json(get(path));
    }

    private JsonObject json(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private int statusOf(String uri) {
        return send(HttpRequest.newBuilder().uri(URI.create(uri)).GET()).statusCode();
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder) {
        return assertDoesNotThrow(() -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }
}
